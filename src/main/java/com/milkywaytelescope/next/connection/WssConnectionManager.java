package com.milkywaytelescope.next.connection;

import com.milkywaytelescope.next.config.TelescopeProperties;
import com.milkywaytelescope.next.state.CharacterSession;
import com.milkywaytelescope.next.state.ConnectionRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WssConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(WssConnectionManager.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final ConnectionProfileStore profileStore;
    private final ConnectionRegistry registry;
    private final TelescopeProperties properties;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ManagedConnection> connections = new ConcurrentHashMap<>();

    public WssConnectionManager(
            ConnectionProfileStore profileStore,
            ConnectionRegistry registry,
            TelescopeProperties properties
    ) {
        this.profileStore = profileStore;
        this.registry = registry;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.scheduler = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "telescope-wss");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void initialize() {
        for (ConnectionProfile profile : profileStore.findAll()) {
            registry.getOrCreate(profile);
            connections.computeIfAbsent(profile.characterId(), ManagedConnection::new).profile.set(profile);
        }
        if (properties.getWss().isAutoConnect()) {
            profileStore.findAll().forEach(this::connect);
        }
    }

    public void apply(ConnectionProfile profile) {
        ManagedConnection connection = connections.computeIfAbsent(
                profile.characterId(),
                ManagedConnection::new
        );
        connection.reconnect(profile);
    }

    public boolean reconnect(String characterId) {
        ConnectionProfile profile = profileStore.find(characterId);
        if (profile == null) {
            return false;
        }
        connections.computeIfAbsent(characterId, ManagedConnection::new).reconnect(profile);
        return true;
    }

    public void remove(String characterId) {
        ManagedConnection connection = connections.remove(characterId);
        if (connection != null) {
            connection.close("configuration removed");
        }
        registry.remove(characterId);
    }

    @PreDestroy
    void shutdown() {
        for (ManagedConnection connection : connections.values()) {
            connection.close("application shutdown");
        }
        scheduler.shutdownNow();
    }

    private void connect(ConnectionProfile profile) {
        connections.computeIfAbsent(profile.characterId(), ManagedConnection::new).connect(profile);
    }

    private final class ManagedConnection {
        private final String characterId;
        private final AtomicReference<ConnectionProfile> profile = new AtomicReference<>();
        private final AtomicReference<WebSocket> socket = new AtomicReference<>();
        private final AtomicBoolean connecting = new AtomicBoolean();
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private volatile long generation;

        private ManagedConnection(String characterId) {
            this.characterId = characterId;
        }

        private void connect(ConnectionProfile nextProfile) {
            profile.set(nextProfile);
            if (!connecting.compareAndSet(false, true)) {
                return;
            }
            reconnectScheduled.set(false);
            CharacterSession session = registry.getOrCreate(nextProfile);
            generation = session.beginGeneration(nextProfile);
            long connectingGeneration = generation;

            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header("Origin", "https://www.milkywayidle.com")
                    .header("Referer", "https://www.milkywayidle.com/")
                    .header("User-Agent", "Milky-Way-Telescope-Next/0.1")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", nextProfile.cookieHeader());

            log.info("Connecting character {} to {}", characterId, nextProfile.redactedUrl());
            builder.buildAsync(nextProfile.uri(), new Listener(this, connectingGeneration))
                    .whenComplete((webSocket, failure) -> {
                        connecting.set(false);
                        ConnectionProfile requestedProfile = profile.get();
                        if (!nextProfile.equals(requestedProfile)) {
                            if (webSocket != null) {
                                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "configuration superseded");
                            }
                            if (requestedProfile != null) {
                                connect(requestedProfile);
                            }
                            return;
                        }
                        if (failure != null) {
                            session.markError(connectingGeneration, failure);
                            log.warn("WSS connection failed for character {}", characterId);
                            scheduleReconnect();
                        } else {
                            if (generation == connectingGeneration) {
                                socket.set(webSocket);
                            } else {
                                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "superseded");
                            }
                        }
                    });
        }

        private void reconnect(ConnectionProfile nextProfile) {
            close("configuration updated");
            connect(nextProfile);
        }

        private void close(String reason) {
            WebSocket active = socket.getAndSet(null);
            if (active != null) {
                active.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            }
        }

        private void scheduleReconnect() {
            if (!properties.getWss().isAutoReconnect() || !reconnectScheduled.compareAndSet(false, true)) {
                return;
            }
            long delayMillis = Math.max(1, properties.getWss().getReconnectDelay().toMillis());
            scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                ConnectionProfile current = profile.get();
                if (current != null && profileStore.find(characterId) != null) {
                    connect(current);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final ManagedConnection connection;
        private final long generation;
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        private Listener(ManagedConnection connection, long generation) {
            this.connection = connection;
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            connection.socket.set(webSocket);
            CharacterSession session = registry.get(connection.characterId);
            if (session != null) {
                session.markConnected(generation);
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                CharacterSession session = registry.get(connection.characterId);
                if (session != null) {
                    session.recordText(generation, textBuffer.toString());
                }
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.writeBytes(chunk);
            if (last) {
                CharacterSession session = registry.get(connection.characterId);
                if (session != null) {
                    session.recordBinary(generation, binaryBuffer.toByteArray());
                }
                binaryBuffer.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (connection.socket.compareAndSet(webSocket, null)) {
                CharacterSession session = registry.get(connection.characterId);
                if (session != null) {
                    session.markClosed(generation, statusCode, reason);
                }
                connection.scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (connection.socket.compareAndSet(webSocket, null)) {
                CharacterSession session = registry.get(connection.characterId);
                if (session != null) {
                    session.markError(generation, error);
                }
                connection.scheduleReconnect();
            }
        }
    }
}

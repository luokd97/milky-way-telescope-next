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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
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
    private final ConnectionControlStore controlStore;
    private final ConnectionRegistry registry;
    private final TelescopeProperties properties;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ManagedConnection> connections = new ConcurrentHashMap<>();

    public WssConnectionManager(
            ConnectionProfileStore profileStore,
            ConnectionControlStore controlStore,
            ConnectionRegistry registry,
            TelescopeProperties properties
    ) {
        this.profileStore = profileStore;
        this.controlStore = controlStore;
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
            CharacterSession session = registry.getOrCreate(profile);
            ManagedConnection connection = connections.computeIfAbsent(profile.characterId(), ManagedConnection::new);
            connection.profile.set(profile);
            ConnectionControlState control = controlStore.find(profile.characterId());
            if (control != null && control.resumeAt().isAfter(Instant.now())) {
                connection.restoreYield(control, session);
            } else if (control != null) {
                controlStore.delete(profile.characterId());
            }
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

    public void reconcile(
            List<ConnectionProfile> desiredProfiles,
            List<ConnectionControlState> desiredControls
    ) {
        Map<String, ConnectionProfile> profilesById = desiredProfiles.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConnectionProfile::characterId,
                        profile -> profile,
                        (left, right) -> right
                ));
        Map<String, ConnectionControlState> controlsById = desiredControls.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConnectionControlState::characterId,
                        control -> control,
                        (left, right) -> right
                ));

        Set<String> removedIds = connections.keySet().stream()
                .filter(characterId -> !profilesById.containsKey(characterId))
                .collect(java.util.stream.Collectors.toSet());
        removedIds.forEach(this::remove);

        profilesById.forEach((characterId, profile) -> {
            ManagedConnection connection = connections.computeIfAbsent(
                    characterId,
                    ManagedConnection::new
            );
            ConnectionProfile previous = connection.profile.getAndSet(profile);
            if (previous == null || !previous.equals(profile)) {
                connection.reconnect(profile);
            }

            ConnectionControlState control = controlsById.get(characterId);
            if (control != null && control.resumeAt().isAfter(Instant.now())) {
                CharacterSession session = registry.getOrCreate(profile);
                connection.restoreYield(control, session);
                controlStore.save(control);
            } else {
                boolean wasYielded = connection.isYielded();
                connection.clearYield();
                if (wasYielded) {
                    connection.connect(profile);
                }
                if (control != null) {
                    controlStore.delete(characterId);
                }
            }
        });
    }

    public boolean reconnect(String characterId) {
        ConnectionProfile profile = profileStore.find(characterId);
        if (profile == null) {
            return false;
        }
        connections.computeIfAbsent(characterId, ManagedConnection::new).resumeAndReconnect(profile);
        return true;
    }

    public boolean extendYield(String characterId) {
        ConnectionProfile profile = profileStore.find(characterId);
        ManagedConnection connection = connections.get(characterId);
        return profile != null && connection != null && connection.extendYield();
    }

    public void remove(String characterId) {
        ManagedConnection connection = connections.remove(characterId);
        if (connection != null) {
            connection.close("configuration removed");
        }
        controlStore.delete(characterId);
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
        private ScheduledFuture<?> pendingReconnect;
        private ScheduledFuture<?> pendingResume;
        private DesiredState desiredState = DesiredState.RUNNING;
        private Instant resumeAt;
        private volatile long generation;

        private ManagedConnection(String characterId) {
            this.characterId = characterId;
        }

        private void connect(ConnectionProfile nextProfile) {
            profile.set(nextProfile);
            synchronized (this) {
                if (desiredState != DesiredState.RUNNING
                        || socket.get() != null
                        || !connecting.compareAndSet(false, true)) {
                    return;
                }
                cancelPendingReconnect();
            }
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
                                socket.compareAndSet(webSocket, null);
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
                            if (registerSocket(webSocket, connectingGeneration)) {
                                // onOpen normally registers first; this also covers provider ordering.
                            } else {
                                socket.compareAndSet(webSocket, null);
                                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "superseded");
                            }
                        }
                    });
        }

        private void reconnect(ConnectionProfile nextProfile) {
            clearYield();
            close("configuration updated");
            connect(nextProfile);
        }

        private void resumeAndReconnect(ConnectionProfile nextProfile) {
            clearYield();
            close("manual reconnect");
            connect(nextProfile);
        }

        private void close(String reason) {
            WebSocket active = socket.getAndSet(null);
            if (active != null) {
                active.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            }
        }

        private synchronized void scheduleReconnect() {
            if (!properties.getWss().isAutoReconnect()
                    || desiredState != DesiredState.RUNNING
                    || pendingReconnect != null) {
                return;
            }
            long delayMillis = Math.max(1, properties.getWss().getReconnectDelay().toMillis());
            pendingReconnect = scheduler.schedule(() -> {
                synchronized (ManagedConnection.this) {
                    pendingReconnect = null;
                    if (desiredState != DesiredState.RUNNING) {
                        return;
                    }
                }
                ConnectionProfile current = profile.get();
                if (current != null && profileStore.find(characterId) != null) {
                    connect(current);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        }

        private synchronized void restoreYield(
                ConnectionControlState control,
                CharacterSession session
        ) {
            desiredState = DesiredState.YIELDED;
            cancelPendingReconnect();
            cancelPendingResume();
            resumeAt = control.resumeAt();
            session.restoreYielded(control.yieldedAt(), control.resumeAt(), control.reason());
            scheduleResume(control.resumeAt());
            close("configuration yielded");
        }

        private synchronized boolean isYielded() {
            return desiredState == DesiredState.YIELDED;
        }

        private synchronized void yieldToRemoteSession(long expectedGeneration, String reason) {
            if (expectedGeneration != generation) {
                return;
            }
            Instant yieldedAt = Instant.now();
            Instant nextResumeAt = yieldedAt.plus(properties.getWss().getTakeoverYieldDuration());
            ConnectionControlState control = new ConnectionControlState(
                    characterId,
                    yieldedAt,
                    nextResumeAt,
                    reason
            );
            desiredState = DesiredState.YIELDED;
            resumeAt = nextResumeAt;
            cancelPendingReconnect();
            cancelPendingResume();
            CharacterSession session = registry.get(characterId);
            if (session != null) {
                session.markYielded(expectedGeneration, yieldedAt, nextResumeAt, reason);
            }
            try {
                controlStore.save(control);
            } catch (RuntimeException exception) {
                log.error("Unable to persist takeover yield for character {}", characterId, exception);
            }
            scheduleResume(nextResumeAt);
            close("yielding to another game session");
            log.info("Yielding character {} until {} because another game session was opened",
                    characterId, nextResumeAt);
        }

        private synchronized boolean extendYield() {
            if (desiredState != DesiredState.YIELDED) {
                return false;
            }
            Instant yieldedAt = Instant.now();
            Instant nextResumeAt = yieldedAt.plus(properties.getWss().getTakeoverYieldDuration());
            ConnectionControlState control = new ConnectionControlState(
                    characterId,
                    yieldedAt,
                    nextResumeAt,
                    "Yield extended by the administrator"
            );
            controlStore.save(control);
            resumeAt = nextResumeAt;
            cancelPendingResume();
            CharacterSession session = registry.get(characterId);
            if (session != null) {
                session.restoreYielded(control.yieldedAt(), control.resumeAt(), control.reason());
            }
            scheduleResume(nextResumeAt);
            return true;
        }

        private synchronized void scheduleResume(Instant expectedResumeAt) {
            long delayMillis = Math.max(1, Duration.between(Instant.now(), expectedResumeAt).toMillis());
            pendingResume = scheduler.schedule(() -> resumeAfterYield(expectedResumeAt),
                    delayMillis, TimeUnit.MILLISECONDS);
        }

        private void resumeAfterYield(Instant expectedResumeAt) {
            ConnectionProfile current;
            synchronized (this) {
                pendingResume = null;
                if (desiredState != DesiredState.YIELDED || !expectedResumeAt.equals(resumeAt)) {
                    return;
                }
                try {
                    controlStore.delete(characterId);
                } catch (RuntimeException exception) {
                    log.error("Unable to clear takeover yield for character {}", characterId, exception);
                    pendingResume = scheduler.schedule(
                            () -> resumeAfterYield(expectedResumeAt),
                            60,
                            TimeUnit.SECONDS
                    );
                    return;
                }
                desiredState = DesiredState.RUNNING;
                resumeAt = null;
                current = profile.get();
            }
            log.info("Automatically resuming character {} after takeover yield", characterId);
            if (current != null && profileStore.find(characterId) != null) {
                connect(current);
            }
        }

        private synchronized void clearYield() {
            controlStore.delete(characterId);
            desiredState = DesiredState.RUNNING;
            resumeAt = null;
            cancelPendingResume();
            cancelPendingReconnect();
        }

        private synchronized void cancelPendingReconnect() {
            if (pendingReconnect != null) {
                pendingReconnect.cancel(false);
                pendingReconnect = null;
            }
        }

        private synchronized void cancelPendingResume() {
            if (pendingResume != null) {
                pendingResume.cancel(false);
                pendingResume = null;
            }
        }

        private synchronized boolean shouldReconnect() {
            return desiredState == DesiredState.RUNNING;
        }

        private synchronized boolean registerSocket(WebSocket webSocket, long expectedGeneration) {
            if (desiredState != DesiredState.RUNNING || generation != expectedGeneration) {
                return false;
            }
            socket.set(webSocket);
            return true;
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
            if (!connection.registerSocket(webSocket, generation)) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "connection no longer desired");
                return;
            }
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
                    var result = session.recordText(generation, textBuffer.toString());
                    if (result.shouldYield()) {
                        connection.yieldToRemoteSession(generation, result.reason());
                    }
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
                if (connection.shouldReconnect()) {
                    connection.scheduleReconnect();
                }
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
                if (connection.shouldReconnect()) {
                    connection.scheduleReconnect();
                }
            }
        }
    }

    private enum DesiredState {
        RUNNING,
        YIELDED
    }
}

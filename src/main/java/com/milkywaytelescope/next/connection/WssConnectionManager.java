package com.milkywaytelescope.next.connection;

import com.milkywaytelescope.next.settings.ApplicationConfig;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
import com.milkywaytelescope.next.settings.ConnectionSettings;
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
    private final ApplicationConfigStore configStore;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ManagedConnection> connections = new ConcurrentHashMap<>();

    public WssConnectionManager(
            ConnectionProfileStore profileStore,
            ConnectionControlStore controlStore,
            ConnectionRegistry registry,
            ApplicationConfigStore configStore
    ) {
        this.profileStore = profileStore;
        this.controlStore = controlStore;
        this.registry = registry;
        this.configStore = configStore;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.scheduler = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "telescope-wss");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void initialize() {
        ApplicationConfig config = configStore.current();
        for (ConnectionProfile profile : profileStore.findAll()) {
            CharacterSession session = registry.getOrCreate(profile);
            ManagedConnection connection = connections.computeIfAbsent(profile.characterId(), ManagedConnection::new);
            connection.profile.set(profile);
            if (config.disabledConnections().contains(profile.characterId())) {
                connection.disable("manual disconnect");
                continue;
            }
            ConnectionControlState control = controlStore.find(profile.characterId());
            if (control != null && control.resumeAt().isAfter(Instant.now())) {
                connection.restoreYield(control, session);
            } else if (control != null) {
                controlStore.delete(profile.characterId());
            }
        }
        if (config.connectionSettings().autoConnect()) {
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
        if (!configStore.current().connectionSettings().autoReconnect()) {
            connections.values().forEach(ManagedConnection::cancelReconnect);
        }
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
            boolean disabled = configStore.current().disabledConnections().contains(characterId);
            if (disabled) {
                registry.getOrCreate(profile);
                connection.disable("manual disconnect");
                return;
            }
            if (previous == null || !previous.equals(profile) || connection.isDisconnected()) {
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
        configStore.update(current -> current.withDisabledConnections(current.disabledConnections().stream()
                .filter(disabledCharacterId -> !disabledCharacterId.equals(characterId))
                .toList()));
        connections.computeIfAbsent(characterId, ManagedConnection::new).resumeAndReconnect(profile);
        return true;
    }

    public boolean disconnect(String characterId) {
        ConnectionProfile profile = profileStore.find(characterId);
        if (profile == null) {
            return false;
        }
        configStore.update(current -> current
                .withDisabledConnections(addDisabledConnection(current.disabledConnections(), characterId))
                .withConnectionControls(current.connectionControls().stream()
                        .filter(control -> !control.characterId().equals(characterId))
                        .toList()));
        ManagedConnection connection = connections.computeIfAbsent(
                characterId,
                ManagedConnection::new
        );
        connection.profile.set(profile);
        registry.getOrCreate(profile);
        connection.disable("manual disconnect");
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
            connection.disable("configuration removed");
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

    private static List<String> addDisabledConnection(List<String> current, String characterId) {
        if (current.contains(characterId)) {
            return current;
        }
        return java.util.stream.Stream.concat(current.stream(), java.util.stream.Stream.of(characterId)).toList();
    }

    private final class ManagedConnection {
        private final String characterId;
        private final AtomicReference<ConnectionProfile> profile = new AtomicReference<>();
        private final AtomicReference<WebSocket> socket = new AtomicReference<>();
        private ScheduledFuture<?> pendingReconnect;
        private ScheduledFuture<?> pendingResume;
        private DesiredState desiredState = DesiredState.RUNNING;
        private Instant resumeAt;
        private volatile long generation;
        private long attemptSequence;
        private long activeAttemptId;
        private boolean connecting;

        private ManagedConnection(String characterId) {
            this.characterId = characterId;
        }

        private void connect(ConnectionProfile nextProfile) {
            profile.set(nextProfile);
            CharacterSession session;
            long connectingGeneration;
            long connectingAttemptId;
            synchronized (this) {
                if (desiredState != DesiredState.RUNNING
                        || socket.get() != null
                        || connecting) {
                    return;
                }
                cancelPendingReconnect();
                connecting = true;
                connectingAttemptId = ++attemptSequence;
                activeAttemptId = connectingAttemptId;
                session = registry.getOrCreate(nextProfile);
                connectingGeneration = session.beginGeneration(nextProfile);
                generation = connectingGeneration;
            }

            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header("Origin", "https://www.milkywayidle.com")
                    .header("Referer", "https://www.milkywayidle.com/")
                    .header("User-Agent", "Milky-Way-Telescope-Next/0.1")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", nextProfile.cookieHeader());

            log.info("Connecting character {} to {}", characterId, nextProfile.redactedUrl());
            builder.buildAsync(nextProfile.uri(), new Listener(this, connectingGeneration, connectingAttemptId))
                    .whenComplete((webSocket, failure) -> {
                        if (!finishAttempt(connectingAttemptId, connectingGeneration)) {
                            closeSuperseded(webSocket);
                            return;
                        }
                        ConnectionProfile requestedProfile = profile.get();
                        if (!nextProfile.equals(requestedProfile)) {
                            closeSuperseded(webSocket);
                            if (requestedProfile != null) {
                                connect(requestedProfile);
                            }
                            return;
                        }
                        if (!isRunning()) {
                            closeSuperseded(webSocket);
                            return;
                        }
                        if (failure != null) {
                            session.markError(connectingGeneration, failure);
                            log.warn("WSS connection failed for character {}", characterId);
                            scheduleReconnect();
                        } else {
                            if (registerSocket(webSocket, connectingGeneration, connectingAttemptId)) {
                                // onOpen normally registers first; this also covers provider ordering.
                            } else {
                                closeSuperseded(webSocket);
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
            WebSocket active;
            synchronized (this) {
                activeAttemptId = ++attemptSequence;
                connecting = false;
                active = socket.getAndSet(null);
            }
            if (active != null) {
                active.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            }
        }

        private synchronized boolean finishAttempt(long expectedAttemptId, long expectedGeneration) {
            if (activeAttemptId != expectedAttemptId || generation != expectedGeneration) {
                return false;
            }
            connecting = false;
            return true;
        }

        private void closeSuperseded(WebSocket webSocket) {
            if (webSocket != null) {
                socket.compareAndSet(webSocket, null);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "superseded");
            }
        }

        private synchronized void scheduleReconnect() {
            ConnectionSettings settings = configStore.current().connectionSettings();
            if (!settings.autoReconnect()
                    || desiredState != DesiredState.RUNNING
                    || pendingReconnect != null) {
                return;
            }
            long delayMillis = Math.max(1, settings.reconnectDelay().toMillis());
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

        private synchronized void disable(String reason) {
            desiredState = DesiredState.DISCONNECTED;
            resumeAt = null;
            cancelPendingReconnect();
            cancelPendingResume();
            close(reason);
            CharacterSession session = registry.get(characterId);
            if (session != null) {
                session.markDisconnected(generation, reason);
            }
        }

        private synchronized boolean isYielded() {
            return desiredState == DesiredState.YIELDED;
        }

        private synchronized boolean isDisconnected() {
            return desiredState == DesiredState.DISCONNECTED;
        }

        private synchronized boolean isRunning() {
            return desiredState == DesiredState.RUNNING;
        }

        private synchronized void yieldToRemoteSession(long expectedGeneration, String reason) {
            if (expectedGeneration != generation) {
                return;
            }
            Instant yieldedAt = Instant.now();
            Instant nextResumeAt = yieldedAt.plus(
                    configStore.current().connectionSettings().takeoverYieldDuration()
            );
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
            Instant nextResumeAt = yieldedAt.plus(
                    configStore.current().connectionSettings().takeoverYieldDuration()
            );
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

        private synchronized void cancelReconnect() {
            cancelPendingReconnect();
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

        private synchronized boolean registerSocket(
                WebSocket webSocket,
                long expectedGeneration,
                long expectedAttemptId
        ) {
            if (desiredState != DesiredState.RUNNING
                    || generation != expectedGeneration
                    || activeAttemptId != expectedAttemptId) {
                return false;
            }
            socket.set(webSocket);
            return true;
        }

        private synchronized boolean isCurrentAttempt(long expectedGeneration, long expectedAttemptId) {
            return desiredState == DesiredState.RUNNING
                    && generation == expectedGeneration
                    && activeAttemptId == expectedAttemptId;
        }

        private synchronized boolean detachSocket(
                WebSocket webSocket,
                long expectedGeneration,
                long expectedAttemptId
        ) {
            if (!isCurrentAttempt(expectedGeneration, expectedAttemptId)
                    || !socket.compareAndSet(webSocket, null)) {
                return false;
            }
            activeAttemptId = ++attemptSequence;
            connecting = false;
            return true;
        }

        private void handleClosed(
                WebSocket webSocket,
                long expectedGeneration,
                long expectedAttemptId,
                int statusCode,
                String reason
        ) {
            if (!detachSocket(webSocket, expectedGeneration, expectedAttemptId)) {
                return;
            }
            CharacterSession session = registry.get(characterId);
            if (session != null) {
                session.markClosed(expectedGeneration, statusCode, reason);
            }
            if (shouldReconnect()) {
                scheduleReconnect();
            }
        }

        private void handleError(
                WebSocket webSocket,
                long expectedGeneration,
                long expectedAttemptId,
                Throwable error
        ) {
            if (!detachSocket(webSocket, expectedGeneration, expectedAttemptId)) {
                return;
            }
            CharacterSession session = registry.get(characterId);
            if (session != null) {
                session.markError(expectedGeneration, error);
            }
            if (shouldReconnect()) {
                scheduleReconnect();
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final ManagedConnection connection;
        private final long generation;
        private final long attemptId;
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        private Listener(ManagedConnection connection, long generation, long attemptId) {
            this.connection = connection;
            this.generation = generation;
            this.attemptId = attemptId;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!connection.registerSocket(webSocket, generation, attemptId)) {
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
                if (connection.isCurrentAttempt(generation, attemptId)) {
                    CharacterSession session = registry.get(connection.characterId);
                    if (session != null) {
                        var result = session.recordText(generation, textBuffer.toString());
                        if (result.shouldYield()) {
                            connection.yieldToRemoteSession(generation, result.reason());
                        }
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
                if (connection.isCurrentAttempt(generation, attemptId)) {
                    CharacterSession session = registry.get(connection.characterId);
                    if (session != null) {
                        session.recordBinary(generation, binaryBuffer.toByteArray());
                    }
                }
                binaryBuffer.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connection.handleClosed(webSocket, generation, attemptId, statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connection.handleError(webSocket, generation, attemptId, error);
        }
    }

    private enum DesiredState {
        RUNNING,
        YIELDED,
        DISCONNECTED
    }
}

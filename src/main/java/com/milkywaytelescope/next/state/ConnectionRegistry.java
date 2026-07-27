package com.milkywaytelescope.next.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.settings.DashboardSettingsStore;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class ConnectionRegistry {
    private final ObjectMapper objectMapper;
    private final int recentLimit;
    private final int maxPayloadBytes;
    private final int recentEventLimit;
    private final int inventoryHighlightLimit;
    private volatile List<String> inventoryWatchTerms;
    private final ConcurrentMap<String, CharacterSession> sessions = new ConcurrentHashMap<>();

    public ConnectionRegistry(
            ObjectMapper objectMapper,
            TelescopeProperties properties,
            DashboardSettingsStore settingsStore
    ) {
        this.objectMapper = objectMapper;
        this.recentLimit = Math.max(1, properties.getMessage().getRecentLimit());
        this.maxPayloadBytes = Math.max(1, properties.getMessage().getMaxPayloadBytes());
        this.recentEventLimit = Math.max(1, properties.getState().getRecentEventLimit());
        this.inventoryHighlightLimit = Math.max(1, properties.getInventory().getHighlightLimit());
        this.inventoryWatchTerms = List.copyOf(settingsStore.current().inventoryWatchTerms());
    }

    public CharacterSession getOrCreate(ConnectionProfile profile) {
        CharacterSession session = sessions.computeIfAbsent(
                profile.characterId(),
                characterId -> new CharacterSession(
                        characterId,
                        objectMapper,
                        recentLimit,
                        maxPayloadBytes,
                        recentEventLimit,
                        inventoryHighlightLimit,
                        inventoryWatchTerms
                )
        );
        session.markConfigured(profile);
        return session;
    }

    public CharacterSession get(String characterId) {
        return sessions.get(characterId);
    }

    public void updateInventoryWatchTerms(List<String> inventoryWatchTerms) {
        this.inventoryWatchTerms = inventoryWatchTerms == null
                ? List.of()
                : List.copyOf(inventoryWatchTerms);
        sessions.values().forEach(session -> session.updateInventoryWatchTerms(this.inventoryWatchTerms));
    }

    public boolean clearRecentEvents(String characterId) {
        CharacterSession session = sessions.get(characterId);
        if (session == null) {
            return false;
        }
        session.clearRecentEvents();
        return true;
    }

    public void remove(String characterId) {
        sessions.remove(characterId);
    }

    public List<CharacterSession.CharacterSnapshot> snapshots(int messageLimit, boolean includePayload) {
        return sessions.values().stream()
                .map(session -> session.snapshot(messageLimit, includePayload))
                .sorted(Comparator.comparing(snapshot -> snapshot.connection().characterId()))
                .toList();
    }
}

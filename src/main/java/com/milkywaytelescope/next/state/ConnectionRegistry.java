package com.milkywaytelescope.next.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import com.milkywaytelescope.next.connection.ConnectionProfile;
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
    private final ConcurrentMap<String, CharacterSession> sessions = new ConcurrentHashMap<>();

    public ConnectionRegistry(ObjectMapper objectMapper, TelescopeProperties properties) {
        this.objectMapper = objectMapper;
        this.recentLimit = Math.max(1, properties.getMessage().getRecentLimit());
        this.maxPayloadBytes = Math.max(1, properties.getMessage().getMaxPayloadBytes());
    }

    public CharacterSession getOrCreate(ConnectionProfile profile) {
        CharacterSession session = sessions.computeIfAbsent(
                profile.characterId(),
                characterId -> new CharacterSession(characterId, objectMapper, recentLimit, maxPayloadBytes)
        );
        session.markConfigured(profile);
        return session;
    }

    public CharacterSession get(String characterId) {
        return sessions.get(characterId);
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

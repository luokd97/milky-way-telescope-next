package com.milkywaytelescope.next.connection;

import com.milkywaytelescope.next.settings.ApplicationConfig;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Compatibility facade for connection CRUD. The unified config store owns persistence.
 */
@Component
public class ConnectionProfileStore {
    private final ApplicationConfigStore configStore;

    public ConnectionProfileStore(ApplicationConfigStore configStore) {
        this.configStore = configStore;
    }

    public List<ConnectionProfile> findAll() {
        return configStore.current().connections();
    }

    public ConnectionProfile find(String characterId) {
        return findAll().stream()
                .filter(profile -> profile.characterId().equals(characterId))
                .findFirst()
                .orElse(null);
    }

    public ConnectionProfile save(String url, String accessToken) {
        ConnectionProfile profile = ConnectionProfile.from(url, accessToken);
        ApplicationConfig saved = configStore.update(current -> current.withConnections(upsert(
                current.connections(),
                profile
        )));
        return find(saved, profile.characterId());
    }

    public ConnectionProfile update(String expectedCharacterId, String url, String accessToken) {
        ConnectionProfile existing = find(expectedCharacterId);
        if (existing == null) {
            return null;
        }
        ConnectionProfile profile = ConnectionProfile.from(url, accessToken);
        if (!expectedCharacterId.equals(profile.characterId())) {
            throw new IllegalArgumentException("The URL characterId cannot be changed");
        }
        ApplicationConfig saved = configStore.update(current -> current.withConnections(upsert(
                current.connections(),
                profile
        )));
        return find(saved, profile.characterId());
    }

    public ConnectionProfile delete(String characterId) {
        ConnectionProfile removed = find(characterId);
        if (removed == null) {
            return null;
        }
        configStore.update(current -> current
                .withConnections(current.connections().stream()
                        .filter(profile -> !profile.characterId().equals(characterId))
                        .toList())
                .withDisabledConnections(current.disabledConnections().stream()
                        .filter(disabledCharacterId -> !disabledCharacterId.equals(characterId))
                        .toList())
                .withConnectionControls(current.connectionControls().stream()
                        .filter(control -> !control.characterId().equals(characterId))
                        .toList()));
        return removed;
    }

    private static List<ConnectionProfile> upsert(
            List<ConnectionProfile> existing,
            ConnectionProfile replacement
    ) {
        List<ConnectionProfile> next = new ArrayList<>();
        boolean replaced = false;
        for (ConnectionProfile profile : existing) {
            if (profile.characterId().equals(replacement.characterId())) {
                next.add(replacement);
                replaced = true;
            } else {
                next.add(profile);
            }
        }
        if (!replaced) {
            next.add(replacement);
        }
        return next;
    }

    private static ConnectionProfile find(ApplicationConfig config, String characterId) {
        return config.connections().stream()
                .filter(profile -> profile.characterId().equals(characterId))
                .findFirst()
                .orElse(null);
    }
}

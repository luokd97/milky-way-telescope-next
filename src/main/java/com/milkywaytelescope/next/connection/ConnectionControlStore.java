package com.milkywaytelescope.next.connection;

import com.milkywaytelescope.next.settings.ApplicationConfig;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Compatibility facade for persisted yield state. The unified config store owns persistence.
 */
@Component
public class ConnectionControlStore {
    private final ApplicationConfigStore configStore;

    public ConnectionControlStore(ApplicationConfigStore configStore) {
        this.configStore = configStore;
    }

    public ConnectionControlState find(String characterId) {
        return configStore.current().connectionControls().stream()
                .filter(control -> control.characterId().equals(characterId))
                .findFirst()
                .orElse(null);
    }

    public ConnectionControlState save(ConnectionControlState state) {
        ApplicationConfig saved = configStore.update(current -> current.withConnectionControls(upsert(
                current.connectionControls(),
                state
        )));
        return saved.connectionControls().stream()
                .filter(control -> control.characterId().equals(state.characterId()))
                .findFirst()
                .orElseThrow();
    }

    public ConnectionControlState delete(String characterId) {
        ConnectionControlState previous = find(characterId);
        if (previous == null) {
            return null;
        }
        configStore.update(current -> current.withConnectionControls(current.connectionControls().stream()
                .filter(control -> !control.characterId().equals(characterId))
                .toList()));
        return previous;
    }

    private static List<ConnectionControlState> upsert(
            List<ConnectionControlState> existing,
            ConnectionControlState replacement
    ) {
        List<ConnectionControlState> next = new ArrayList<>();
        boolean replaced = false;
        for (ConnectionControlState control : existing) {
            if (control.characterId().equals(replacement.characterId())) {
                next.add(replacement);
                replaced = true;
            } else {
                next.add(control);
            }
        }
        if (!replaced) {
            next.add(replacement);
        }
        return next;
    }
}

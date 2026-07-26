package com.milkywaytelescope.next.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ConnectionControlStore {
    private static final TypeReference<List<ConnectionControlState>> STATE_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Map<String, ConnectionControlState> states = new LinkedHashMap<>();

    public ConnectionControlStore(ObjectMapper objectMapper, TelescopeProperties properties) {
        this.objectMapper = objectMapper;
        this.file = properties.getStorage().getControlFile().toAbsolutePath().normalize();
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<ConnectionControlState> loaded = objectMapper.readValue(file.toFile(), STATE_LIST);
            states.clear();
            for (ConnectionControlState state : loaded) {
                if (states.putIfAbsent(state.characterId(), state) != null) {
                    throw new IllegalStateException("Duplicate characterId in connection control store");
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to load connection control states", exception);
        }
    }

    public synchronized ConnectionControlState find(String characterId) {
        return states.get(characterId);
    }

    public synchronized ConnectionControlState save(ConnectionControlState state) {
        ConnectionControlState previous = states.put(state.characterId(), state);
        try {
            persist();
        } catch (RuntimeException exception) {
            restore(state.characterId(), previous);
            throw exception;
        }
        return state;
    }

    public synchronized ConnectionControlState delete(String characterId) {
        ConnectionControlState removed = states.remove(characterId);
        if (removed != null) {
            try {
                persist();
            } catch (RuntimeException exception) {
                states.put(characterId, removed);
                throw exception;
            }
        }
        return removed;
    }

    private void restore(String characterId, ConnectionControlState previous) {
        if (previous == null) {
            states.remove(characterId);
        } else {
            states.put(characterId, previous);
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new ArrayList<>(states.values()));
            restrictPermissions(temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist connection control states", exception);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems rely on their platform ACLs.
        }
    }
}

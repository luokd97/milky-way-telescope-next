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
public class ConnectionProfileStore {
    private static final TypeReference<List<ConnectionProfile>> PROFILE_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();

    public ConnectionProfileStore(ObjectMapper objectMapper, TelescopeProperties properties) {
        this.objectMapper = objectMapper;
        this.file = properties.getStorage().getConnectionFile().toAbsolutePath().normalize();
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<ConnectionProfile> loaded = objectMapper.readValue(file.toFile(), PROFILE_LIST);
            profiles.clear();
            for (ConnectionProfile profile : loaded) {
                ConnectionProfile validated = ConnectionProfile.from(profile.url(), profile.accessToken());
                if (profiles.putIfAbsent(validated.characterId(), validated) != null) {
                    throw new IllegalStateException("Duplicate characterId in connection store");
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to load connection profiles", exception);
        }
    }

    public synchronized List<ConnectionProfile> findAll() {
        return List.copyOf(profiles.values());
    }

    public synchronized ConnectionProfile find(String characterId) {
        return profiles.get(characterId);
    }

    public synchronized ConnectionProfile save(String url, String accessToken) {
        ConnectionProfile profile = ConnectionProfile.from(url, accessToken);
        ConnectionProfile previous = profiles.put(profile.characterId(), profile);
        try {
            persist();
        } catch (RuntimeException exception) {
            restore(profile.characterId(), previous);
            throw exception;
        }
        return profile;
    }

    public synchronized ConnectionProfile update(String expectedCharacterId, String url, String accessToken) {
        if (!profiles.containsKey(expectedCharacterId)) {
            return null;
        }
        ConnectionProfile profile = ConnectionProfile.from(url, accessToken);
        if (!expectedCharacterId.equals(profile.characterId())) {
            throw new IllegalArgumentException("The URL characterId cannot be changed");
        }
        ConnectionProfile previous = profiles.put(expectedCharacterId, profile);
        try {
            persist();
        } catch (RuntimeException exception) {
            restore(expectedCharacterId, previous);
            throw exception;
        }
        return profile;
    }

    public synchronized ConnectionProfile delete(String characterId) {
        ConnectionProfile removed = profiles.remove(characterId);
        if (removed != null) {
            try {
                persist();
            } catch (RuntimeException exception) {
                profiles.put(characterId, removed);
                throw exception;
            }
        }
        return removed;
    }

    private void restore(String characterId, ConnectionProfile previous) {
        if (previous == null) {
            profiles.remove(characterId);
        } else {
            profiles.put(characterId, previous);
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new ArrayList<>(profiles.values()));
            restrictPermissions(temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist connection profiles", exception);
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

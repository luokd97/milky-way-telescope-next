package com.milkywaytelescope.next.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.connection.ConnectionControlState;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.config.TelescopeProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class ApplicationConfigStore {
    private static final TypeReference<List<ConnectionProfile>> PROFILE_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ConnectionControlState>> CONTROL_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Path legacyConnectionFile;
    private final Path legacyControlFile;
    private ApplicationConfig config = ApplicationConfig.defaults();

    public ApplicationConfigStore(ObjectMapper objectMapper, TelescopeProperties properties) {
        this.objectMapper = objectMapper;
        this.file = properties.getStorage().getSettingsFile().toAbsolutePath().normalize();
        this.legacyConnectionFile = properties.getStorage().getConnectionFile().toAbsolutePath().normalize();
        this.legacyControlFile = properties.getStorage().getControlFile().toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void load() {
        try {
            if (Files.exists(file)) {
                JsonNode root = objectMapper.readTree(file.toFile());
                if (root == null || root.isNull()) {
                    throw new IllegalArgumentException("Application config file cannot be null");
                }
                if (root.has("schemaVersion")) {
                    config = objectMapper.treeToValue(root, ApplicationConfig.class);
                    return;
                }
                migrate(root);
                return;
            }

            if (Files.exists(legacyConnectionFile) || Files.exists(legacyControlFile)) {
                migrate(null);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to load application config", exception);
        }
    }

    public synchronized ApplicationConfig current() {
        return config;
    }

    public synchronized ApplicationConfig replace(ApplicationConfig next) {
        if (next == null) {
            throw new IllegalArgumentException("Application config is required");
        }
        ApplicationConfig validated = new ApplicationConfig(
                next.schemaVersion(),
                next.dashboard(),
                next.connections(),
                next.connectionControls()
        );
        ApplicationConfig previous = config;
        config = validated;
        try {
            persist();
        } catch (RuntimeException exception) {
            config = previous;
            throw exception;
        }
        return config;
    }

    public synchronized ApplicationConfig update(UnaryOperator<ApplicationConfig> updater) {
        if (updater == null) {
            throw new IllegalArgumentException("Config updater is required");
        }
        return replace(updater.apply(config));
    }

    private void migrate(JsonNode legacySettingsRoot) throws IOException {
        DashboardSettings dashboard = legacySettingsRoot == null
                ? DashboardSettings.defaults()
                : objectMapper.treeToValue(legacySettingsRoot, DashboardSettings.class);
        List<ConnectionProfile> connections = readLegacyList(legacyConnectionFile, PROFILE_LIST);
        List<ConnectionControlState> controls = readLegacyList(legacyControlFile, CONTROL_LIST);
        ApplicationConfig migrated = new ApplicationConfig(dashboard, connections, controls);

        backupIfPresent(file);
        backupIfPresent(legacyConnectionFile);
        backupIfPresent(legacyControlFile);
        config = migrated;
        persist();
    }

    private <T> List<T> readLegacyList(Path source, TypeReference<List<T>> type) throws IOException {
        if (!Files.exists(source)) {
            return List.of();
        }
        List<T> values = objectMapper.readValue(source.toFile(), type);
        return values == null ? List.of() : values;
    }

    private void backupIfPresent(Path source) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        Path backup = source.resolveSibling(source.getFileName() + ".pre-unified.bak");
        if (!Files.exists(backup)) {
            Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
            restrictPermissions(backup);
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), config);
            restrictPermissions(temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist application config", exception);
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

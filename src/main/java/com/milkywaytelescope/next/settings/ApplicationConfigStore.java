package com.milkywaytelescope.next.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class ApplicationConfigStore {
    private final ObjectMapper objectMapper;
    private final Path file;
    private ApplicationConfig config = ApplicationConfig.defaults();

    public ApplicationConfigStore(ObjectMapper objectMapper, TelescopeProperties properties) {
        this.objectMapper = objectMapper;
        this.file = properties.getStorage().getSettingsFile().toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void load() {
        try {
            if (Files.exists(file)) {
                JsonNode root = objectMapper.readTree(file.toFile());
                if (root == null || root.isNull()) {
                    throw new IllegalArgumentException("Application config file cannot be null");
                }
                ApplicationConfig loaded = readConfig(root);
                config = loaded;
                if (root.path("schemaVersion").asInt() != ApplicationConfig.CURRENT_SCHEMA_VERSION) {
                    persist();
                }
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
                next.connectionSettings(),
                next.connections(),
                next.disabledConnections(),
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

    private ApplicationConfig readConfig(JsonNode root) throws IOException {
        if (!root.isObject()) {
            throw new IllegalArgumentException("Application config must be a JSON object");
        }
        int schemaVersion = root.path("schemaVersion").asInt(-1);
        if (schemaVersion == -1 && root.has("sectionOrder")) {
            com.fasterxml.jackson.databind.node.ObjectNode upgraded = objectMapper.createObjectNode();
            upgraded.put("schemaVersion", ApplicationConfig.CURRENT_SCHEMA_VERSION);
            upgraded.set("dashboard", root);
            upgraded.set("connectionSettings", objectMapper.valueToTree(ConnectionSettings.defaults()));
            upgraded.putArray("connections");
            upgraded.putArray("disabledConnections");
            upgraded.putArray("connectionControls");
            return objectMapper.treeToValue(upgraded, ApplicationConfig.class);
        }
        if (schemaVersion == 1) {
            com.fasterxml.jackson.databind.node.ObjectNode upgraded = root.deepCopy();
            upgraded.put("schemaVersion", ApplicationConfig.CURRENT_SCHEMA_VERSION);
            if (!upgraded.has("connectionSettings")) {
                upgraded.set("connectionSettings", objectMapper.valueToTree(ConnectionSettings.defaults()));
            }
            if (!upgraded.has("disabledConnections")) {
                upgraded.putArray("disabledConnections");
            }
            return objectMapper.treeToValue(upgraded, ApplicationConfig.class);
        }
        if (schemaVersion != ApplicationConfig.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported application config schemaVersion: " + schemaVersion);
        }
        return objectMapper.treeToValue(root, ApplicationConfig.class);
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

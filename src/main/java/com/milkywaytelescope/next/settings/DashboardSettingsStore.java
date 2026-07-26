package com.milkywaytelescope.next.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DashboardSettingsStore {
    private final ObjectMapper objectMapper;
    private final Path file;
    private DashboardSettings settings = DashboardSettings.defaults();

    public DashboardSettingsStore(ObjectMapper objectMapper, TelescopeProperties properties) {
        this.objectMapper = objectMapper;
        this.file = properties.getStorage().getSettingsFile().toAbsolutePath().normalize();
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            DashboardSettings loaded = objectMapper.readValue(file.toFile(), DashboardSettings.class);
            if (loaded == null) {
                throw new IllegalArgumentException("Dashboard settings file cannot be null");
            }
            settings = loaded;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to load dashboard settings", exception);
        }
    }

    public synchronized DashboardSettings current() {
        return settings;
    }

    public synchronized DashboardSettings save(List<String> sectionOrder) {
        DashboardSettings next = new DashboardSettings(sectionOrder);
        DashboardSettings previous = settings;
        settings = next;
        try {
            persist();
        } catch (RuntimeException exception) {
            settings = previous;
            throw exception;
        }
        return settings;
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), settings);
            restrictPermissions(temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist dashboard settings", exception);
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

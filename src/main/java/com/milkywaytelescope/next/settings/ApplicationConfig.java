package com.milkywaytelescope.next.settings;

import com.milkywaytelescope.next.connection.ConnectionControlState;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ApplicationConfig(
        int schemaVersion,
        DashboardSettings dashboard,
        MessageSettings message,
        ConnectionSettings connectionSettings,
        List<ConnectionProfile> connections,
        List<String> disabledConnections,
        List<ConnectionControlState> connectionControls
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ApplicationConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported application config schemaVersion: " + schemaVersion);
        }
        dashboard = dashboard == null ? DashboardSettings.defaults() : dashboard;
        message = message == null ? MessageSettings.defaults() : message;
        connectionSettings = connectionSettings == null
                ? ConnectionSettings.defaults()
                : connectionSettings;
        connections = normalizeConnections(connections);
        disabledConnections = normalizeDisabledConnections(disabledConnections);
        connectionControls = normalizeConnectionControls(connectionControls);
    }

    public ApplicationConfig(
            DashboardSettings dashboard,
            List<ConnectionProfile> connections,
            List<ConnectionControlState> connectionControls
    ) {
        this(
                CURRENT_SCHEMA_VERSION,
                dashboard,
                MessageSettings.defaults(),
                ConnectionSettings.defaults(),
                connections,
                List.of(),
                connectionControls
        );
    }

    public ApplicationConfig(
            int schemaVersion,
            DashboardSettings dashboard,
            ConnectionSettings connectionSettings,
            List<ConnectionProfile> connections,
            List<String> disabledConnections,
            List<ConnectionControlState> connectionControls
    ) {
        this(
                schemaVersion,
                dashboard,
                MessageSettings.defaults(),
                connectionSettings,
                connections,
                disabledConnections,
                connectionControls
        );
    }

    public static ApplicationConfig defaults() {
        return new ApplicationConfig(
                CURRENT_SCHEMA_VERSION,
                DashboardSettings.defaults(),
                MessageSettings.defaults(),
                ConnectionSettings.defaults(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public ApplicationConfig withDashboard(DashboardSettings nextDashboard) {
        return new ApplicationConfig(
                schemaVersion,
                nextDashboard,
                message,
                connectionSettings,
                connections,
                disabledConnections,
                connectionControls
        );
    }

    public ApplicationConfig withConnectionSettings(ConnectionSettings nextConnectionSettings) {
        return new ApplicationConfig(
                schemaVersion,
                dashboard,
                message,
                nextConnectionSettings,
                connections,
                disabledConnections,
                connectionControls
        );
    }

    public ApplicationConfig withConnections(List<ConnectionProfile> nextConnections) {
        return new ApplicationConfig(
                schemaVersion,
                dashboard,
                message,
                connectionSettings,
                nextConnections,
                disabledConnections,
                connectionControls
        );
    }

    public ApplicationConfig withDisabledConnections(List<String> nextDisabledConnections) {
        return new ApplicationConfig(
                schemaVersion,
                dashboard,
                message,
                connectionSettings,
                connections,
                nextDisabledConnections,
                connectionControls
        );
    }

    public ApplicationConfig withConnectionControls(List<ConnectionControlState> nextControls) {
        return new ApplicationConfig(
                schemaVersion,
                dashboard,
                message,
                connectionSettings,
                connections,
                disabledConnections,
                nextControls
        );
    }

    public ApplicationConfig withMessageSettings(MessageSettings nextMessage) {
        return new ApplicationConfig(
                schemaVersion,
                dashboard,
                nextMessage,
                connectionSettings,
                connections,
                disabledConnections,
                connectionControls
        );
    }

    private static List<ConnectionProfile> normalizeConnections(List<ConnectionProfile> values) {
        if (values == null) {
            return List.of();
        }
        List<ConnectionProfile> normalized = new ArrayList<>();
        Set<String> characterIds = new LinkedHashSet<>();
        for (ConnectionProfile profile : values) {
            if (profile == null) {
                throw new IllegalArgumentException("connections cannot contain null entries");
            }
            ConnectionProfile validated = ConnectionProfile.from(profile.url(), profile.accessToken());
            if (!profile.characterId().equals(validated.characterId())) {
                throw new IllegalArgumentException(
                        "Connection characterId does not match its URL: " + profile.characterId()
                );
            }
            if (!characterIds.add(validated.characterId())) {
                throw new IllegalArgumentException("Duplicate connection characterId: " + validated.characterId());
            }
            normalized.add(validated);
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeDisabledConnections(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String characterId : values) {
            if (characterId == null || characterId.isBlank()) {
                throw new IllegalArgumentException("disabledConnections cannot contain blank characterIds");
            }
            unique.add(characterId.trim());
        }
        return List.copyOf(unique);
    }

    private static List<ConnectionControlState> normalizeConnectionControls(
            List<ConnectionControlState> values
    ) {
        if (values == null) {
            return List.of();
        }
        List<ConnectionControlState> normalized = new ArrayList<>();
        Set<String> characterIds = new LinkedHashSet<>();
        for (ConnectionControlState control : values) {
            if (control == null) {
                throw new IllegalArgumentException("connectionControls cannot contain null entries");
            }
            if (!characterIds.add(control.characterId())) {
                throw new IllegalArgumentException(
                        "Duplicate connection control characterId: " + control.characterId()
                );
            }
            normalized.add(new ConnectionControlState(
                    Objects.requireNonNull(control.characterId()),
                    control.yieldedAt(),
                    control.resumeAt(),
                    control.reason()
            ));
        }
        return List.copyOf(normalized);
    }
}

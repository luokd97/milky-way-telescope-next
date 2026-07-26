package com.milkywaytelescope.next.settings;

import com.milkywaytelescope.next.connection.ConnectionControlState;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ApplicationConfig(
        int schemaVersion,
        DashboardSettings dashboard,
        List<ConnectionProfile> connections,
        List<ConnectionControlState> connectionControls
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ApplicationConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported application config schemaVersion: " + schemaVersion);
        }
        dashboard = dashboard == null ? DashboardSettings.defaults() : dashboard;
        connections = normalizeConnections(connections);
        connectionControls = normalizeConnectionControls(connectionControls);
    }

    public ApplicationConfig(
            DashboardSettings dashboard,
            List<ConnectionProfile> connections,
            List<ConnectionControlState> connectionControls
    ) {
        this(CURRENT_SCHEMA_VERSION, dashboard, connections, connectionControls);
    }

    public static ApplicationConfig defaults() {
        return new ApplicationConfig(DashboardSettings.defaults(), List.of(), List.of());
    }

    public ApplicationConfig withDashboard(DashboardSettings nextDashboard) {
        return new ApplicationConfig(schemaVersion, nextDashboard, connections, connectionControls);
    }

    public ApplicationConfig withConnections(List<ConnectionProfile> nextConnections) {
        return new ApplicationConfig(schemaVersion, dashboard, nextConnections, connectionControls);
    }

    public ApplicationConfig withConnectionControls(List<ConnectionControlState> nextControls) {
        return new ApplicationConfig(schemaVersion, dashboard, connections, nextControls);
    }

    private static List<ConnectionProfile> normalizeConnections(List<ConnectionProfile> values) {
        if (values == null) {
            return List.of();
        }
        List<ConnectionProfile> normalized = new ArrayList<>();
        HashSet<String> characterIds = new HashSet<>();
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

    private static List<ConnectionControlState> normalizeConnectionControls(
            List<ConnectionControlState> values
    ) {
        if (values == null) {
            return List.of();
        }
        List<ConnectionControlState> normalized = new ArrayList<>();
        HashSet<String> characterIds = new HashSet<>();
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

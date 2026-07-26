package com.milkywaytelescope.next.settings;

import java.time.Duration;

public record ConnectionSettings(
        boolean autoConnect,
        boolean autoReconnect,
        Duration reconnectDelay,
        Duration takeoverYieldDuration
) {
    public ConnectionSettings {
        if (reconnectDelay == null || reconnectDelay.isZero() || reconnectDelay.isNegative()) {
            throw new IllegalArgumentException("reconnectDelay must be positive");
        }
        if (takeoverYieldDuration == null
                || takeoverYieldDuration.isZero()
                || takeoverYieldDuration.isNegative()) {
            throw new IllegalArgumentException("takeoverYieldDuration must be positive");
        }
    }

    public static ConnectionSettings defaults() {
        return new ConnectionSettings(
                false,
                true,
                Duration.ofSeconds(30),
                Duration.ofHours(2)
        );
    }
}

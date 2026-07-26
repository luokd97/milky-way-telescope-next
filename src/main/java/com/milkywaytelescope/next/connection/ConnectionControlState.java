package com.milkywaytelescope.next.connection;

import java.time.Instant;

public record ConnectionControlState(
        String characterId,
        Instant yieldedAt,
        Instant resumeAt,
        String reason
) {
    public ConnectionControlState {
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("characterId is required");
        }
        if (yieldedAt == null) {
            throw new IllegalArgumentException("yieldedAt is required");
        }
        if (resumeAt == null) {
            throw new IllegalArgumentException("resumeAt is required");
        }
        if (!resumeAt.isAfter(yieldedAt)) {
            throw new IllegalArgumentException("resumeAt must be after yieldedAt");
        }
        reason = reason == null || reason.isBlank() ? "Another game session was opened" : reason;
    }
}

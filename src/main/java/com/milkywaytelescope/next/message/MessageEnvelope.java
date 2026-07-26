package com.milkywaytelescope.next.message;

import java.time.Instant;

public record MessageEnvelope(
        long sequence,
        Instant receivedAt,
        String type,
        String opcode,
        int byteLength,
        String summary,
        String rawPayload
) {
}

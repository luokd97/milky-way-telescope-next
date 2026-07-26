package com.milkywaytelescope.next.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.message.MessageEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CharacterSession {
    private final String characterId;
    private final ObjectMapper objectMapper;
    private final int recentLimit;
    private final int maxPayloadBytes;
    private final CharacterProjection projection;
    private final Deque<MessageEnvelope> recentMessages = new ArrayDeque<>();
    private final Map<String, Long> messageCountsByType = new LinkedHashMap<>();

    private long generation;
    private long nextSequence;
    private String status = "idle";
    private String redactedUrl;
    private Instant startedAt;
    private Instant connectedAt;
    private Instant lastMessageAt;
    private Instant closedAt;
    private Integer closeCode;
    private String closeReason;
    private String error;
    private Instant yieldedAt;
    private Instant resumeAt;
    private String yieldReason;
    private long totalMessages;

    public CharacterSession(
            String characterId,
            ObjectMapper objectMapper,
            int recentLimit,
            int maxPayloadBytes
    ) {
        this(characterId, objectMapper, recentLimit, maxPayloadBytes, 50, 12, List.of());
    }

    public CharacterSession(
            String characterId,
            ObjectMapper objectMapper,
            int recentLimit,
            int maxPayloadBytes,
            int recentEventLimit,
            int inventoryHighlightLimit,
            List<String> inventoryWatchTerms
    ) {
        this.characterId = characterId;
        this.objectMapper = objectMapper;
        this.recentLimit = Math.max(1, recentLimit);
        this.maxPayloadBytes = Math.max(1, maxPayloadBytes);
        this.projection = new CharacterProjection(
                characterId,
                recentEventLimit,
                inventoryHighlightLimit,
                inventoryWatchTerms
        );
    }

    public synchronized long beginGeneration(ConnectionProfile profile) {
        generation++;
        nextSequence = 0;
        status = "connecting";
        redactedUrl = profile.redactedUrl();
        startedAt = Instant.now();
        connectedAt = null;
        lastMessageAt = null;
        closedAt = null;
        closeCode = null;
        closeReason = null;
        error = null;
        yieldedAt = null;
        resumeAt = null;
        yieldReason = null;
        totalMessages = 0;
        recentMessages.clear();
        messageCountsByType.clear();
        return generation;
    }

    public synchronized void markConfigured(ConnectionProfile profile) {
        redactedUrl = profile.redactedUrl();
    }

    public synchronized void markConnected(long expectedGeneration) {
        if (expectedGeneration != generation) {
            return;
        }
        status = "connected";
        connectedAt = Instant.now();
        closedAt = null;
        error = null;
    }

    public synchronized void markClosed(long expectedGeneration, int code, String reason) {
        if (expectedGeneration != generation) {
            return;
        }
        if (!"yielded".equals(status)) {
            status = "closed";
        }
        closedAt = Instant.now();
        closeCode = code;
        closeReason = blankToNull(reason);
    }

    public synchronized void markError(long expectedGeneration, Throwable throwable) {
        if (expectedGeneration != generation) {
            return;
        }
        if (!"yielded".equals(status)) {
            status = "error";
        }
        closedAt = Instant.now();
        error = throwable == null ? "Unknown connection error" : throwable.getClass().getSimpleName();
    }

    public synchronized void markYielded(
            long expectedGeneration,
            Instant yieldedAt,
            Instant resumeAt,
            String reason
    ) {
        if (expectedGeneration != generation) {
            return;
        }
        applyYielded(yieldedAt, resumeAt, reason);
    }

    public synchronized void restoreYielded(Instant yieldedAt, Instant resumeAt, String reason) {
        applyYielded(yieldedAt, resumeAt, reason);
    }

    private void applyYielded(Instant yieldedAt, Instant resumeAt, String reason) {
        status = "yielded";
        this.yieldedAt = yieldedAt;
        this.resumeAt = resumeAt;
        this.yieldReason = reason;
        error = null;
    }

    public synchronized TextMessageResult recordText(long expectedGeneration, String rawPayload) {
        if (expectedGeneration != generation) {
            return TextMessageResult.NONE;
        }
        byte[] bytes = rawPayload.getBytes(StandardCharsets.UTF_8);
        Instant receivedAt = Instant.now();
        lastMessageAt = receivedAt;
        totalMessages++;
        nextSequence++;

        JsonNode payload = null;
        String type = "unknown";
        String summary;
        TextMessageResult result = TextMessageResult.NONE;
        if (bytes.length > maxPayloadBytes) {
            type = "oversized";
            summary = "Payload omitted because it exceeds the configured limit";
        } else {
            try {
                payload = objectMapper.readTree(rawPayload);
                type = text(payload, "type", "unknown");
                projection.apply(generation, type, payload, receivedAt, nextSequence);
                summary = summarize(type, payload);
                JsonNode shouldReconnect = payload.path("shouldReconnect");
                if ("close_session".equals(type)
                        && shouldReconnect.isBoolean()
                        && !shouldReconnect.asBoolean()) {
                    result = new TextMessageResult(true, text(payload, "message", "Another game session was opened"));
                }
            } catch (JsonProcessingException exception) {
                type = "unparseable";
                summary = "Unable to parse JSON payload";
            }
        }

        messageCountsByType.merge(type, 1L, Long::sum);
        addMessage(new MessageEnvelope(
                nextSequence,
                receivedAt,
                type,
                "text",
                bytes.length,
                summary,
                bytes.length <= maxPayloadBytes ? rawPayload : null
        ));
        return result;
    }

    public synchronized void recordBinary(long expectedGeneration, byte[] bytes) {
        if (expectedGeneration != generation) {
            return;
        }
        Instant receivedAt = Instant.now();
        lastMessageAt = receivedAt;
        totalMessages++;
        nextSequence++;
        messageCountsByType.merge("binary", 1L, Long::sum);
        addMessage(new MessageEnvelope(
                nextSequence,
                receivedAt,
                "binary",
                "binary",
                bytes.length,
                "Binary payload",
                null
        ));
    }

    public synchronized CharacterSnapshot snapshot(int messageLimit, boolean includePayload) {
        int boundedLimit = Math.max(0, Math.min(messageLimit, recentLimit));
        List<MessageView> messages = new ArrayList<>();
        var iterator = recentMessages.descendingIterator();
        while (iterator.hasNext() && messages.size() < boundedLimit) {
            MessageEnvelope message = iterator.next();
            messages.add(new MessageView(
                    message.sequence(),
                    message.receivedAt(),
                    message.type(),
                    message.opcode(),
                    message.byteLength(),
                    message.summary(),
                    includePayload ? message.rawPayload() : null
            ));
        }
        var projected = projection.snapshot(generation, status);
        return new CharacterSnapshot(
                new ConnectionView(
                        characterId,
                        status,
                        "connected".equals(status),
                        generation,
                        redactedUrl,
                        startedAt,
                        connectedAt,
                        lastMessageAt,
                        closedAt,
                        closeCode,
                        closeReason,
                        error,
                        yieldedAt,
                        resumeAt,
                        yieldReason,
                        totalMessages
                ),
                projected.dataStatus(),
                projected.dataUpdatedAt(),
                projected.character(),
                projected.task(),
                projected.currentAction(),
                projected.currentActionDrinkSlots(),
                projected.actionQueue(),
                projected.battle(),
                projected.inventoryHighlights(),
                projected.recentEvents(),
                Map.copyOf(messageCountsByType),
                messages
        );
    }

    private void addMessage(MessageEnvelope message) {
        recentMessages.addLast(message);
        while (recentMessages.size() > recentLimit) {
            recentMessages.removeFirst();
        }
    }

    private static String summarize(String type, JsonNode payload) {
        return switch (type) {
            case "init_character_data" -> "Character baseline loaded";
            case "action_completed" -> "Action completed";
            case "actions_updated" -> "Actions updated";
            case "items_updated" -> "Items updated";
            case "new_battle" -> "New battle";
            case "battle_updated" -> "Battle updated";
            case "chat_message_received" -> "Chat message received";
            case "close_session" -> text(payload, "message", "Session closed");
            default -> type;
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CharacterSnapshot(
            ConnectionView connection,
            String dataStatus,
            Instant dataUpdatedAt,
            CharacterView character,
            TaskView task,
            ActionView currentAction,
            List<ActionDrinkSlotView> currentActionDrinkSlots,
            List<ActionView> actionQueue,
            BattleView battle,
            List<ItemView> inventoryHighlights,
            List<MonitorEventView> recentEvents,
            Map<String, Long> messageCountsByType,
            List<MessageView> recentMessages
    ) {
    }

    public record ConnectionView(
            String characterId,
            String status,
            boolean connected,
            long generation,
            String redactedUrl,
            Instant startedAt,
            Instant connectedAt,
            Instant lastMessageAt,
            Instant closedAt,
            Integer closeCode,
            String closeReason,
            String error,
            Instant yieldedAt,
            Instant resumeAt,
            String yieldReason,
            long totalMessages
    ) {
    }

    public record CharacterView(
            String id,
            String name,
            String gameMode,
            Boolean online,
            Instant serverTimestamp,
            int actionQueueSize,
            int itemStackCount
    ) {
    }

    public record TaskView(int currentCount, int maxCount, Instant overflowAt) {
    }

    public record ActionView(
            String actionHrid,
            String label,
            Integer difficultyTier,
            Long currentCount,
            Long maxCount,
            Long ordinal,
            Integer wave,
            Boolean done,
            boolean current,
            Instant updatedAt
    ) {
    }

    public record ActionDrinkSlotView(
            Integer slotIndex,
            String itemHrid,
            String label,
            Integer enhancementLevel,
            Double count
    ) {
    }

    public record BattleView(
            boolean active,
            Long battleId,
            Instant combatStartTime,
            Integer wave,
            long totalBattlesSeen,
            List<Double> foodConsumableCounts,
            List<Double> drinkConsumableCounts
    ) {
    }

    public record ItemView(
            String itemHash,
            String itemHrid,
            String label,
            String locationHrid,
            Integer enhancementLevel,
            Double count
    ) {
    }

    public record MonitorEventView(
            long id,
            long sourceMessageSequence,
            Instant occurredAt,
            String type,
            String itemHrid,
            String label,
            Integer enhancementLevel,
            Double count
    ) {
    }

    public record MessageView(
            long sequence,
            Instant receivedAt,
            String type,
            String opcode,
            int byteLength,
            String summary,
            String payload
    ) {
    }

    public record TextMessageResult(boolean shouldYield, String reason) {
        private static final TextMessageResult NONE = new TextMessageResult(false, null);
    }
}

package com.milkywaytelescope.next.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.milkywaytelescope.next.state.CharacterSession.ActionDrinkSlotView;
import com.milkywaytelescope.next.state.CharacterSession.ActionView;
import com.milkywaytelescope.next.state.CharacterSession.BattleView;
import com.milkywaytelescope.next.state.CharacterSession.CharacterView;
import com.milkywaytelescope.next.state.CharacterSession.ItemView;
import com.milkywaytelescope.next.state.CharacterSession.MonitorEventView;
import com.milkywaytelescope.next.state.CharacterSession.TaskView;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class CharacterProjection {
    private static final int CONSUMABLE_SLOT_COUNT = 3;
    private static final double LOW_ITEM_COUNT_EVENT_THRESHOLD = 20;
    private static final String LOW_ITEM_COUNT_EVENT_TYPE = "low_item_count";
    private static final String RANDOM_TASK_CATEGORY = "/quest_category/random_task";
    private static final String ACTION_HRID_PREFIX = "/actions/";
    private static final String ACTION_TYPE_HRID_PREFIX = "/action_types/";
    private static final String COMBAT_ACTION_TYPE = "/action_types/combat";

    private final String configuredCharacterId;
    private final int recentEventLimit;
    private final int inventoryHighlightLimit;
    private final List<String> inventoryWatchTerms;
    private final List<JsonNode> characterActions = new ArrayList<>();
    private final List<JsonNode> characterQuests = new ArrayList<>();
    private final Map<String, JsonNode> itemsByKey = new LinkedHashMap<>();
    private final Map<String, List<JsonNode>> drinkSlotsByActionType = new LinkedHashMap<>();
    private final Map<Integer, JsonNode> playerBattleUpdates = new LinkedHashMap<>();
    private final Map<Integer, JsonNode> monsterBattleUpdates = new LinkedHashMap<>();
    private final Deque<MonitorEventView> recentEvents = new ArrayDeque<>();

    private long baselineGeneration;
    private long nextEventId;
    private Instant dataUpdatedAt;
    private Instant serverTimestamp;
    private JsonNode character;
    private JsonNode characterInfo;
    private Long currentBattleId;
    private Instant combatStartTime;
    private Integer battleWave;
    private long totalBattlesSeen;
    private List<JsonNode> battlePlayers = List.of();
    private List<JsonNode> battleMonsters = List.of();

    CharacterProjection(
            String configuredCharacterId,
            int recentEventLimit,
            int inventoryHighlightLimit,
            List<String> inventoryWatchTerms
    ) {
        this.configuredCharacterId = Objects.requireNonNull(configuredCharacterId);
        this.recentEventLimit = Math.max(1, recentEventLimit);
        this.inventoryHighlightLimit = Math.max(1, inventoryHighlightLimit);
        this.inventoryWatchTerms = inventoryWatchTerms == null
                ? List.of()
                : inventoryWatchTerms.stream()
                        .filter(Objects::nonNull)
                        .map(CharacterProjection::normalizeInventoryText)
                        .filter(term -> !term.isBlank())
                        .toList();
    }

    void apply(long generation, String type, JsonNode message, Instant receivedAt, long sourceMessageSequence) {
        if ("init_character_data".equals(type)) {
            applyBaseline(generation, message);
            dataUpdatedAt = receivedAt;
            return;
        }
        if (baselineGeneration != generation) {
            return;
        }

        boolean handled = true;
        switch (type) {
            case "action_completed" -> applyActionCompleted(message, receivedAt, sourceMessageSequence);
            case "actions_updated" -> applyActionsUpdated(message);
            case "items_updated" -> mergeItems(message.get("endCharacterItems"));
            case "new_battle" -> applyNewBattle(message);
            case "battle_updated" -> applyBattleUpdated(message);
            default -> handled = false;
        }
        if (handled) {
            dataUpdatedAt = receivedAt;
        }
    }

    ProjectionSnapshot snapshot(long currentGeneration, String connectionStatus) {
        List<ActionView> actions = actionViews();
        ActionView currentAction = actions.stream()
                .filter(ActionView::current)
                .findFirst()
                .orElse(null);
        String dataStatus;
        if (baselineGeneration == 0) {
            dataStatus = "waiting";
        } else if (baselineGeneration == currentGeneration && "connected".equals(connectionStatus)) {
            dataStatus = "live";
        } else {
            dataStatus = "stale";
        }

        return new ProjectionSnapshot(
                dataStatus,
                dataUpdatedAt,
                characterView(),
                taskView(),
                currentAction,
                currentActionDrinkSlots(currentAction),
                actions,
                battleView(),
                inventoryViews(),
                recentEventViews()
        );
    }

    private void applyBaseline(long generation, JsonNode message) {
        baselineGeneration = generation;
        serverTimestamp = instant(message, "currentTimestamp");
        character = copyOrNull(message.get("character"));
        characterInfo = copyOrNull(message.get("characterInfo"));

        characterActions.clear();
        appendArrayCopies(characterActions, message.get("characterActions"));
        characterQuests.clear();
        appendArrayCopies(characterQuests, message.get("characterQuests"));
        itemsByKey.clear();
        mergeItems(message.get("characterItems"));
        replaceActionTypeSlots(message.get("actionTypeDrinkSlotsMap"));

        currentBattleId = null;
        combatStartTime = null;
        battleWave = null;
        totalBattlesSeen = 0;
        battlePlayers = List.of();
        battleMonsters = List.of();
        playerBattleUpdates.clear();
        monsterBattleUpdates.clear();
        recentEvents.clear();
        nextEventId = 0;

        JsonNode combatUnit = message.get("combatUnit");
        if (combatUnit != null && !combatUnit.isNull()) {
            battlePlayers = List.of(combatUnit.deepCopy());
        }
    }

    private void applyActionCompleted(JsonNode message, Instant occurredAt, long sourceMessageSequence) {
        JsonNode action = message.get("endCharacterAction");
        if (action != null && !action.isNull()) {
            mergeAction(action);
        }
        JsonNode items = message.get("endCharacterItems");
        addLowItemCountEvents(items, occurredAt, sourceMessageSequence);
        mergeItems(items);
    }

    private void applyActionsUpdated(JsonNode message) {
        JsonNode actions = message.get("endCharacterActions");
        if (actions == null || !actions.isArray()) {
            return;
        }
        for (JsonNode action : actions) {
            mergeAction(action);
        }
    }

    private void applyNewBattle(JsonNode message) {
        currentBattleId = longValue(message, "battleId");
        combatStartTime = instant(message, "combatStartTime");
        battleWave = integer(message, "wave");
        totalBattlesSeen++;
        battlePlayers = arrayCopies(message.get("players"));
        battleMonsters = arrayCopies(message.get("monsters"));
        playerBattleUpdates.clear();
        monsterBattleUpdates.clear();
    }

    private void applyBattleUpdated(JsonNode message) {
        Long battleId = longValue(message, "battleId");
        if (battleId != null) {
            currentBattleId = battleId;
        }
        mergeIndexMap(playerBattleUpdates, message.get("pMap"));
        mergeIndexMap(monsterBattleUpdates, message.get("mMap"));
    }

    private CharacterView characterView() {
        if (character == null) {
            return null;
        }
        return new CharacterView(
                text(character, "id", configuredCharacterId),
                text(character, "name", ""),
                text(character, "gameMode", ""),
                booleanValue(character, "isOnline"),
                serverTimestamp,
                characterActions.size(),
                itemsByKey.size()
        );
    }

    private TaskView taskView() {
        if (characterInfo == null || serverTimestamp == null) {
            return null;
        }
        Integer maxCount = integer(characterInfo, "taskSlotCap");
        Integer cooldownHours = integer(characterInfo, "taskCooldownHours");
        Instant lastTaskTimestamp = instant(characterInfo, "lastTaskTimestamp");
        if (maxCount == null || maxCount <= 0
                || cooldownHours == null || cooldownHours <= 0
                || lastTaskTimestamp == null) {
            return null;
        }

        int unreadTaskCount = Math.max(0, integerOrDefault(characterInfo, "unreadTaskCount", 0));
        int currentCount = randomTaskCount() + unreadTaskCount;
        Duration cooldown = Duration.ofHours(cooldownHours);
        Instant nextTaskAt = nextTaskAt(lastTaskTimestamp, serverTimestamp, cooldown);
        long slotsRemaining = Math.max(0L, (long) maxCount - currentCount);
        return new TaskView(currentCount, maxCount, nextTaskAt.plus(cooldown.multipliedBy(slotsRemaining)));
    }

    private int randomTaskCount() {
        int count = 0;
        for (JsonNode quest : characterQuests) {
            if (RANDOM_TASK_CATEGORY.equals(text(quest, "category", ""))) {
                count++;
            }
        }
        return count;
    }

    private List<ActionView> actionViews() {
        List<JsonNode> sorted = new ArrayList<>(characterActions);
        sorted.sort(Comparator
                .comparing((JsonNode action) -> longValue(action, "ordinal"), Comparator.nullsLast(Long::compareTo))
                .thenComparing(action -> text(action, "actionHrid", "")));

        Long currentOrdinal = sorted.stream()
                .filter(action -> !Boolean.TRUE.equals(booleanValue(action, "isDone")))
                .map(action -> longValue(action, "ordinal"))
                .findFirst()
                .orElse(null);

        List<ActionView> views = new ArrayList<>();
        for (JsonNode action : sorted) {
            String actionHrid = text(action, "actionHrid", "");
            Long ordinal = longValue(action, "ordinal");
            views.add(new ActionView(
                    actionHrid,
                    labelFromHrid(actionHrid),
                    integer(action, "difficultyTier"),
                    longValue(action, "currentCount"),
                    longValue(action, "maxCount"),
                    ordinal,
                    integer(action, "wave"),
                    booleanValue(action, "isDone"),
                    currentOrdinal != null && currentOrdinal.equals(ordinal),
                    instant(action, "updatedAt")
            ));
        }
        return List.copyOf(views);
    }

    private List<ActionDrinkSlotView> currentActionDrinkSlots(ActionView currentAction) {
        if (currentAction == null || isCombatAction(currentAction.actionHrid())) {
            return List.of();
        }
        String actionTypeHrid = actionTypeHrid(currentAction.actionHrid());
        return drinkSlotViews(actionTypeHrid.isBlank() ? null : drinkSlotsByActionType.get(actionTypeHrid));
    }

    private List<ActionDrinkSlotView> drinkSlotViews(List<JsonNode> slots) {
        List<ActionDrinkSlotView> views = new ArrayList<>();
        for (int i = 0; i < CONSUMABLE_SLOT_COUNT; i++) {
            views.add(null);
        }
        if (slots == null) {
            return Collections.unmodifiableList(new ArrayList<>(views));
        }
        for (int i = 0; i < slots.size(); i++) {
            JsonNode slot = slots.get(i);
            String itemHrid = text(slot, "itemHrid", "");
            if (itemHrid.isBlank()) {
                continue;
            }
            Integer enhancementLevel = integer(slot, "enhancementLevel");
            Integer configuredIndex = integer(slot, "slotIndex");
            int slotIndex = configuredIndex == null ? i : configuredIndex;
            if (slotIndex >= 0 && slotIndex < CONSUMABLE_SLOT_COUNT) {
                views.set(slotIndex, new ActionDrinkSlotView(
                        slotIndex,
                        itemHrid,
                        labelFromHrid(itemHrid),
                        enhancementLevel,
                        inventoryCount(itemHrid, enhancementLevel)
                ));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(views));
    }

    private BattleView battleView() {
        JsonNode localPlayer = localBattlePlayer();
        boolean active = currentBattleId != null
                && hasLivingUnit(battlePlayers, playerBattleUpdates)
                && hasLivingUnit(battleMonsters, monsterBattleUpdates);
        List<JsonNode> consumables = arrayCopies(localPlayer == null ? null : localPlayer.get("combatConsumables"));
        return new BattleView(
                active,
                currentBattleId,
                combatStartTime,
                battleWave,
                totalBattlesSeen,
                combatConsumableCounts(consumables, 0),
                combatConsumableCounts(consumables, CONSUMABLE_SLOT_COUNT)
        );
    }

    private JsonNode localBattlePlayer() {
        if (battlePlayers.isEmpty()) {
            return null;
        }
        String characterId = character == null ? configuredCharacterId : text(character, "id", configuredCharacterId);
        for (JsonNode player : battlePlayers) {
            if (characterId.equals(text(player.get("character"), "id", ""))) {
                return player;
            }
        }
        return battlePlayers.getFirst();
    }

    private boolean hasLivingUnit(List<JsonNode> units, Map<Integer, JsonNode> updates) {
        for (int i = 0; i < units.size(); i++) {
            JsonNode unit = units.get(i);
            JsonNode update = updates.get(i);
            Double hitpoints = compactOrFullDouble(update, unit, "cHP", "currentHitpoints");
            if (hitpoints != null && hitpoints > 0) {
                return true;
            }
        }
        return false;
    }

    private List<Double> combatConsumableCounts(List<JsonNode> consumables, int startIndex) {
        List<Double> counts = new ArrayList<>();
        for (int i = 0; i < CONSUMABLE_SLOT_COUNT; i++) {
            int index = startIndex + i;
            Double count = index < consumables.size() ? doubleValue(consumables.get(index), "count") : null;
            counts.add(count == null ? 0.0 : count);
        }
        return List.copyOf(counts);
    }

    private List<ItemView> inventoryViews() {
        List<ItemView> positiveItems = new ArrayList<>();
        for (JsonNode item : itemsByKey.values()) {
            ItemView view = itemView(item);
            if (view.count() != null && view.count() > 0) {
                positiveItems.add(view);
            }
        }

        if (inventoryWatchTerms.isEmpty()) {
            positiveItems.sort(inventoryComparator());
            return limitedCopy(positiveItems);
        }

        List<InventoryMatch> matches = new ArrayList<>();
        for (ItemView item : positiveItems) {
            int termIndex = firstWatchTermIndex(item);
            if (termIndex >= 0) {
                matches.add(new InventoryMatch(item, termIndex));
            }
        }
        matches.sort(Comparator
                .comparingInt(InventoryMatch::termIndex)
                .thenComparing(match -> match.item().count(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(match -> match.item().label()));
        return limitedCopy(matches.stream().map(InventoryMatch::item).toList());
    }

    private List<ItemView> limitedCopy(List<ItemView> items) {
        return items.size() > inventoryHighlightLimit
                ? List.copyOf(items.subList(0, inventoryHighlightLimit))
                : List.copyOf(items);
    }

    private Comparator<ItemView> inventoryComparator() {
        return Comparator
                .comparing(ItemView::count, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ItemView::label);
    }

    private int firstWatchTermIndex(ItemView item) {
        String searchText = normalizeInventoryText(
                Objects.toString(item.itemHrid(), "") + " "
                        + Objects.toString(item.label(), "") + " "
                        + Objects.toString(item.locationHrid(), "")
        );
        for (int i = 0; i < inventoryWatchTerms.size(); i++) {
            if (searchText.contains(inventoryWatchTerms.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private ItemView itemView(JsonNode item) {
        String itemHrid = text(item, "itemHrid", "");
        return new ItemView(
                text(item, "hash", itemKey(item)),
                itemHrid,
                labelFromHrid(itemHrid),
                text(item, "itemLocationHrid", ""),
                integer(item, "enhancementLevel"),
                doubleValue(item, "count")
        );
    }

    private void addLowItemCountEvents(JsonNode items, Instant occurredAt, long sourceMessageSequence) {
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String itemHrid = text(item, "itemHrid", "");
            Double itemCount = doubleValue(item, "count");
            if (itemHrid.isBlank()
                    || itemCount == null
                    || !Double.isFinite(itemCount)
                    || itemCount < 0
                    || itemCount >= LOW_ITEM_COUNT_EVENT_THRESHOLD) {
                continue;
            }
            recentEvents.addLast(new MonitorEventView(
                    ++nextEventId,
                    sourceMessageSequence,
                    occurredAt,
                    LOW_ITEM_COUNT_EVENT_TYPE,
                    itemHrid,
                    labelFromHrid(itemHrid),
                    integer(item, "enhancementLevel"),
                    itemCount
            ));
            while (recentEvents.size() > recentEventLimit) {
                recentEvents.removeFirst();
            }
        }
    }

    private List<MonitorEventView> recentEventViews() {
        List<MonitorEventView> views = new ArrayList<>();
        Iterator<MonitorEventView> iterator = recentEvents.descendingIterator();
        while (iterator.hasNext()) {
            views.add(iterator.next());
        }
        return List.copyOf(views);
    }

    private void mergeAction(JsonNode action) {
        for (int i = 0; i < characterActions.size(); i++) {
            if (sameAction(characterActions.get(i), action)) {
                characterActions.set(i, action.deepCopy());
                return;
            }
        }
        characterActions.add(action.deepCopy());
    }

    private boolean sameAction(JsonNode left, JsonNode right) {
        Long leftId = longValue(left, "id");
        Long rightId = longValue(right, "id");
        if (leftId != null && rightId != null && leftId > 0 && rightId > 0) {
            return leftId.equals(rightId);
        }
        Long leftOrdinal = longValue(left, "ordinal");
        Long rightOrdinal = longValue(right, "ordinal");
        return leftOrdinal != null && leftOrdinal.equals(rightOrdinal);
    }

    private void mergeItems(JsonNode items) {
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            itemsByKey.put(itemKey(item), item.deepCopy());
        }
    }

    private double inventoryCount(String itemHrid, Integer enhancementLevel) {
        double count = 0;
        for (JsonNode item : itemsByKey.values()) {
            if (!itemHrid.equals(text(item, "itemHrid", ""))) {
                continue;
            }
            if (enhancementLevel != null && !Objects.equals(enhancementLevel, integer(item, "enhancementLevel"))) {
                continue;
            }
            Double itemCount = doubleValue(item, "count");
            if (itemCount != null) {
                count += itemCount;
            }
        }
        return count;
    }

    private String itemKey(JsonNode item) {
        String hash = text(item, "hash", "");
        if (!hash.isBlank()) {
            return hash;
        }
        return text(item, "itemLocationHrid", "")
                + "::" + text(item, "itemHrid", "")
                + "::" + text(item, "enhancementLevel", "0");
    }

    private void replaceActionTypeSlots(JsonNode slotsMap) {
        drinkSlotsByActionType.clear();
        if (slotsMap == null || !slotsMap.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = slotsMap.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            drinkSlotsByActionType.put(field.getKey(), arrayCopies(field.getValue()));
        }
    }

    private void mergeIndexMap(Map<Integer, JsonNode> target, JsonNode mapNode) {
        if (mapNode == null || !mapNode.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = mapNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            try {
                target.put(Integer.parseInt(field.getKey()), field.getValue().deepCopy());
            } catch (NumberFormatException ignored) {
                // Compact battle maps use numeric string indexes.
            }
        }
    }

    private static boolean isCombatAction(String actionHrid) {
        return COMBAT_ACTION_TYPE.equals(actionTypeHrid(actionHrid));
    }

    private static String actionTypeHrid(String actionHrid) {
        if (actionHrid == null || !actionHrid.startsWith(ACTION_HRID_PREFIX)) {
            return "";
        }
        int typeStart = ACTION_HRID_PREFIX.length();
        int typeEnd = actionHrid.indexOf('/', typeStart);
        if (typeEnd <= typeStart) {
            return "";
        }
        return ACTION_TYPE_HRID_PREFIX + actionHrid.substring(typeStart, typeEnd);
    }

    private static List<JsonNode> arrayCopies(JsonNode array) {
        List<JsonNode> copies = new ArrayList<>();
        appendArrayCopies(copies, array);
        return List.copyOf(copies);
    }

    private static void appendArrayCopies(List<JsonNode> target, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode element : array) {
            target.add(element.deepCopy());
        }
    }

    private static JsonNode copyOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.deepCopy();
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asText(defaultValue);
    }

    private static Integer integer(JsonNode node, String field) {
        Long value = longValue(node, field);
        return value == null ? null : Math.toIntExact(value);
    }

    private static int integerOrDefault(JsonNode node, String field, int defaultValue) {
        Integer value = integer(node, field);
        return value == null ? defaultValue : value;
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asLong();
    }

    private static Double doubleValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isBoolean() ? null : value.asBoolean();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field, "");
        if (value.isBlank() || value.startsWith("0001-01-01")) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Instant nextTaskAt(Instant lastTaskTimestamp, Instant observedAt, Duration cooldown) {
        if (observedAt.isBefore(lastTaskTimestamp)) {
            return lastTaskTimestamp.plus(cooldown);
        }
        long elapsedSeconds = Duration.between(lastTaskTimestamp, observedAt).getSeconds();
        long elapsedIntervals = Math.floorDiv(elapsedSeconds, cooldown.getSeconds());
        return lastTaskTimestamp.plus(cooldown.multipliedBy(elapsedIntervals + 1));
    }

    private static String labelFromHrid(String hrid) {
        if (hrid == null || hrid.isBlank()) {
            return "";
        }
        String value = hrid;
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        value = value.replace('_', ' ').trim();
        StringBuilder result = new StringBuilder(value.length());
        boolean nextUpper = true;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                result.append(' ');
                nextUpper = true;
            } else if (nextUpper) {
                result.append(Character.toUpperCase(ch));
                nextUpper = false;
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static String normalizeInventoryText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('/', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Double compactOrFullDouble(JsonNode compact, JsonNode full, String compactKey, String fullKey) {
        Double compactValue = doubleValue(compact, compactKey);
        return compactValue != null ? compactValue : doubleValue(full, fullKey);
    }

    record ProjectionSnapshot(
            String dataStatus,
            Instant dataUpdatedAt,
            CharacterView character,
            TaskView task,
            ActionView currentAction,
            List<ActionDrinkSlotView> currentActionDrinkSlots,
            List<ActionView> actionQueue,
            BattleView battle,
            List<ItemView> inventoryHighlights,
            List<MonitorEventView> recentEvents
    ) {
    }

    private record InventoryMatch(ItemView item, int termIndex) {
    }
}

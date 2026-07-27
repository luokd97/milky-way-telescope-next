package com.milkywaytelescope.next.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterProjectionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsBaselineTasksActionsDrinksAndWatchedInventory() throws Exception {
        CharacterProjection projection = new CharacterProjection("7", 50, 12, List.of("tea", "coin"));
        projection.apply(1, "init_character_data", objectMapper.readTree("""
                {
                  "currentTimestamp": "2026-07-26T08:00:00Z",
                  "character": {
                    "id": 7,
                    "name": "Observer",
                    "gameMode": "standard",
                    "isOnline": true
                  },
                  "characterInfo": {
                    "taskSlotCap": 3,
                    "taskCooldownHours": 1,
                    "lastTaskTimestamp": "2026-07-26T06:30:00Z",
                    "unreadTaskCount": 1
                  },
                  "characterQuests": [
                    {"category": "/quest_category/random_task"},
                    {"category": "/quest_category/tutorial"}
                  ],
                  "characterActions": [
                    {
                      "id": 11,
                      "actionHrid": "/actions/foraging/forest",
                      "currentCount": 8,
                      "maxCount": 20,
                      "ordinal": 1,
                      "isDone": false
                    },
                    {
                      "id": 12,
                      "actionHrid": "/actions/woodcutting/birch",
                      "currentCount": 0,
                      "maxCount": 40,
                      "ordinal": 2,
                      "isDone": false
                    }
                  ],
                  "characterItems": [
                    {
                      "hash": "tea",
                      "itemLocationHrid": "/item_locations/inventory",
                      "itemHrid": "/items/wisdom_tea",
                      "enhancementLevel": 0,
                      "count": 280
                    },
                    {
                      "hash": "coin",
                      "itemLocationHrid": "/item_locations/inventory",
                      "itemHrid": "/items/coin",
                      "enhancementLevel": 0,
                      "count": 5000
                    },
                    {
                      "hash": "log",
                      "itemLocationHrid": "/item_locations/inventory",
                      "itemHrid": "/items/birch_log",
                      "enhancementLevel": 0,
                      "count": 9999
                    }
                  ],
                  "actionTypeDrinkSlotsMap": {
                    "/action_types/foraging": [
                      {"itemHrid": "/items/wisdom_tea", "slotIndex": 0},
                      null,
                      null
                    ]
                  }
                }
                """), Instant.parse("2026-07-26T08:00:01Z"), 1);

        var snapshot = projection.snapshot(1, "connected");

        assertThat(snapshot.dataStatus()).isEqualTo("live");
        assertThat(snapshot.character().name()).isEqualTo("Observer");
        assertThat(snapshot.character().actionQueueSize()).isEqualTo(2);
        assertThat(snapshot.task().currentCount()).isEqualTo(2);
        assertThat(snapshot.task().overflowAt()).isEqualTo(Instant.parse("2026-07-26T09:30:00Z"));
        assertThat(snapshot.currentAction().label()).isEqualTo("Forest");
        assertThat(snapshot.currentActionDrinkSlots()).hasSize(3);
        assertThat(snapshot.currentActionDrinkSlots().getFirst().count()).isEqualTo(280);
        assertThat(snapshot.currentActionDrinkSlots().get(1)).isNull();
        assertThat(snapshot.inventoryHighlights())
                .extracting(CharacterSession.ItemView::label)
                .containsExactly("Wisdom Tea", "Coin");
    }

    @Test
    void advancesActionsMergesInventoryAndRecordsBoundedLowItemEvents() throws Exception {
        CharacterProjection projection = new CharacterProjection("7", 2, 12, List.of());
        projection.apply(1, "init_character_data", objectMapper.readTree("""
                {
                  "character": {"id": 7, "name": "Observer"},
                  "characterActions": [
                    {"id": 1, "actionHrid": "/actions/foraging/forest", "ordinal": 1, "isDone": false},
                    {"id": 2, "actionHrid": "/actions/woodcutting/birch", "ordinal": 2, "isDone": false}
                  ],
                  "characterItems": [
                    {"hash": "tea", "itemHrid": "/items/wisdom_tea", "count": 30}
                  ]
                }
                """), Instant.parse("2026-07-26T08:00:00Z"), 1);

        projection.apply(1, "actions_updated", objectMapper.readTree("""
                {"endCharacterActions": [
                  {"id": 1, "actionHrid": "/actions/foraging/forest", "ordinal": 1, "isDone": true}
                ]}
                """), Instant.parse("2026-07-26T08:01:00Z"), 2);
        recordLowCount(projection, 15, "2026-07-26T08:02:00Z", 3);
        recordLowCount(projection, 10, "2026-07-26T08:03:00Z", 4);
        recordLowCount(projection, 5, "2026-07-26T08:04:00Z", 5);

        var snapshot = projection.snapshot(1, "connected");

        assertThat(snapshot.currentAction().label()).isEqualTo("Birch");
        assertThat(snapshot.inventoryHighlights()).singleElement()
                .satisfies(item -> assertThat(item.count()).isEqualTo(5));
        assertThat(snapshot.recentEvents())
                .extracting(CharacterSession.MonitorEventView::count)
                .containsExactly(5.0, 10.0);
        assertThat(snapshot.dataUpdatedAt()).isEqualTo(Instant.parse("2026-07-26T08:04:00Z"));
    }

    @Test
    void clearsRecentEventsWithoutChangingProjectedInventory() throws Exception {
        CharacterProjection projection = new CharacterProjection("7", 50, 12, List.of("tea"));
        projection.apply(1, "init_character_data", objectMapper.readTree("""
                {
                  "character": {"id": 7, "name": "Observer"},
                  "characterItems": [
                    {"hash": "tea", "itemHrid": "/items/wisdom_tea", "count": 10}
                  ]
                }
                """), Instant.parse("2026-07-26T08:00:00Z"), 1);
        recordLowCount(projection, 10, "2026-07-26T08:01:00Z", 2);

        projection.clearRecentEvents();

        var cleared = projection.snapshot(1, "connected");
        assertThat(cleared.recentEvents()).isEmpty();
        assertThat(cleared.inventoryHighlights())
                .extracting(CharacterSession.ItemView::count)
                .containsExactly(10.0);

        recordLowCount(projection, 5, "2026-07-26T08:02:00Z", 3);
        assertThat(projection.snapshot(1, "connected").recentEvents())
                .extracting(CharacterSession.MonitorEventView::count)
                .containsExactly(5.0);
    }

    @Test
    void usesTheLocalBattlePlayerAndCompactUpdatesToDetermineBattleState() throws Exception {
        CharacterProjection projection = new CharacterProjection("7", 50, 12, List.of());
        projection.apply(1, "init_character_data", objectMapper.readTree("""
                {
                  "character": {"id": 7, "name": "Observer"},
                  "characterActions": [
                    {"id": 1, "actionHrid": "/actions/combat/snake", "ordinal": 1, "isDone": false}
                  ]
                }
                """), Instant.parse("2026-07-26T08:00:00Z"), 1);
        projection.apply(1, "new_battle", objectMapper.readTree("""
                {
                  "battleId": 12,
                  "combatStartTime": "2026-07-26T08:00:00Z",
                  "wave": 2,
                  "players": [
                    {
                      "name": "Party Friend",
                      "character": {"id": 99},
                      "currentHitpoints": 500,
                      "combatConsumables": [{"itemHrid": "/items/wrong_food", "count": 1}]
                    },
                    {
                      "name": "Observer",
                      "character": {"id": 7},
                      "currentHitpoints": 600,
                      "combatConsumables": [
                        {"itemHrid": "/items/food_1", "count": 1500},
                        null,
                        {"itemHrid": "/items/food_3", "count": 1300},
                        {"itemHrid": "/items/drink_1", "count": 300},
                        null,
                        {"itemHrid": "/items/drink_3", "count": 260}
                      ]
                    }
                  ],
                  "monsters": [{"name": "Snake", "currentHitpoints": 200}]
                }
                """), Instant.parse("2026-07-26T08:00:01Z"), 2);

        var activeBattle = projection.snapshot(1, "connected").battle();
        assertThat(activeBattle.active()).isTrue();
        assertThat(activeBattle.foodConsumableCounts()).containsExactly(1500.0, 0.0, 1300.0);
        assertThat(activeBattle.drinkConsumableCounts()).containsExactly(300.0, 0.0, 260.0);
        assertThat(activeBattle.foodConsumables()).hasSize(3);
        assertThat(activeBattle.foodConsumables().getFirst())
                .satisfies(slot -> {
                    assertThat(slot.itemHrid()).isEqualTo("/items/food_1");
                    assertThat(slot.label()).isEqualTo("Food 1");
                    assertThat(slot.count()).isEqualTo(1500.0);
                });
        assertThat(activeBattle.foodConsumables().get(1)).isNull();
        assertThat(activeBattle.drinkConsumables()).hasSize(3);
        assertThat(activeBattle.drinkConsumables().getFirst().itemHrid()).isEqualTo("/items/drink_1");
        assertThat(activeBattle.drinkConsumables().get(1)).isNull();

        projection.apply(1, "battle_updated", objectMapper.readTree("""
                {
                  "battleId": 12,
                  "pMap": {"1": {"cHP": 580}},
                  "mMap": {"0": {"cHP": 0}}
                }
                """), Instant.parse("2026-07-26T08:00:10Z"), 3);

        assertThat(projection.snapshot(1, "connected").battle().active()).isFalse();
    }

    @Test
    void supportsActionOrdinalsBeyondTheIntegerRange() throws Exception {
        CharacterProjection projection = new CharacterProjection("7", 50, 12, List.of());
        projection.apply(1, "init_character_data", objectMapper.readTree("""
                {
                  "character": {"id": 7, "name": "Observer"},
                  "characterActions": [
                    {
                      "id": 2,
                      "actionHrid": "/actions/woodcutting/birch",
                      "ordinal": 3000000001,
                      "isDone": false
                    },
                    {
                      "id": 1,
                      "actionHrid": "/actions/foraging/forest",
                      "ordinal": 3000000000,
                      "isDone": false
                    }
                  ]
                }
                """), Instant.parse("2026-07-26T08:00:00Z"), 1);

        var snapshot = projection.snapshot(1, "connected");

        assertThat(snapshot.currentAction().label()).isEqualTo("Forest");
        assertThat(snapshot.currentAction().ordinal()).isEqualTo(3_000_000_000L);
        assertThat(snapshot.actionQueue())
                .extracting(CharacterSession.ActionView::ordinal)
                .containsExactly(3_000_000_000L, 3_000_000_001L);
    }

    private void recordLowCount(
            CharacterProjection projection,
            int count,
            String occurredAt,
            long sequence
    ) throws Exception {
        projection.apply(1, "action_completed", objectMapper.readTree("""
                {"endCharacterItems": [
                  {"hash": "tea", "itemHrid": "/items/wisdom_tea", "count": %d}
                ]}
                """.formatted(count)), Instant.parse(occurredAt), sequence);
    }
}

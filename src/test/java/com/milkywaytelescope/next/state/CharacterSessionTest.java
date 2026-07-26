package com.milkywaytelescope.next.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CharacterSessionTest {
    @Test
    void keepsBoundedMessagesAndProjectsCharacterBaseline() {
        CharacterSession session = new CharacterSession("7", new ObjectMapper(), 3, 4096);
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                "sample-token"
        );
        long generation = session.beginGeneration(profile);

        session.recordText(generation, """
                {"type":"init_character_data","character":{"id":7,"name":"Observer","gameMode":"standard"}}
                """);
        session.recordText(generation, "{\"type\":\"items_updated\"}");
        session.recordText(generation, "{\"type\":\"actions_updated\"}");
        session.recordText(generation, "{\"type\":\"new_battle\"}");

        var snapshot = session.snapshot(100, false);

        assertThat(snapshot.character().name()).isEqualTo("Observer");
        assertThat(snapshot.connection().totalMessages()).isEqualTo(4);
        assertThat(snapshot.recentMessages()).hasSize(3);
        assertThat(snapshot.recentMessages())
                .extracting(CharacterSession.MessageView::sequence)
                .containsExactly(4L, 3L, 2L);
        assertThat(snapshot.recentMessages()).allMatch(message -> message.payload() == null);

        assertThat(session.snapshot(1, true).recentMessages().getFirst().payload())
                .contains("new_battle");
    }

    @Test
    void ignoresMessagesFromPreviousGeneration() {
        CharacterSession session = new CharacterSession("7", new ObjectMapper(), 100, 4096);
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                "sample-token"
        );
        long oldGeneration = session.beginGeneration(profile);
        long currentGeneration = session.beginGeneration(profile);

        session.recordText(oldGeneration, "{\"type\":\"items_updated\"}");
        session.recordText(currentGeneration, "{\"type\":\"actions_updated\"}");

        assertThat(session.snapshot(100, false).connection().totalMessages()).isEqualTo(1);
    }

    @Test
    void recognizesExplicitServerTakeoverWithoutDroppingTheMessage() {
        CharacterSession session = new CharacterSession("7", new ObjectMapper(), 100, 4096);
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                "sample-token"
        );
        long generation = session.beginGeneration(profile);

        var result = session.recordText(generation, """
                {
                  "type": "close_session",
                  "message": "Disconnected. The game was opened from another device or window.",
                  "shouldReconnect": false
                }
                """);

        assertThat(result.shouldYield()).isTrue();
        assertThat(result.reason()).contains("another device or window");
        assertThat(session.snapshot(100, true).recentMessages())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.type()).isEqualTo("close_session");
                    assertThat(message.payload()).contains("\"shouldReconnect\": false");
                });
    }

    @Test
    void onlyYieldsForAnExplicitFalseReconnectDirective() {
        CharacterSession session = new CharacterSession("7", new ObjectMapper(), 100, 4096);
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                "sample-token"
        );
        long generation = session.beginGeneration(profile);

        assertThat(session.recordText(
                generation,
                "{\"type\":\"close_session\",\"shouldReconnect\":true}"
        ).shouldYield()).isFalse();
        assertThat(session.recordText(
                generation,
                "{\"type\":\"items_updated\",\"shouldReconnect\":false}"
        ).shouldYield()).isFalse();
        assertThat(session.recordText(generation, "not-json").shouldYield()).isFalse();
    }

    @Test
    void keepsYieldedStatusWhenTheSocketCloses() {
        CharacterSession session = new CharacterSession("7", new ObjectMapper(), 100, 4096);
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                "sample-token"
        );
        long generation = session.beginGeneration(profile);
        Instant yieldedAt = Instant.parse("2026-07-26T08:00:00Z");
        Instant resumeAt = Instant.parse("2026-07-26T10:00:00Z");

        session.markYielded(generation, yieldedAt, resumeAt, "another window");
        session.markClosed(generation, 1000, "closed");

        var connection = session.snapshot(0, false).connection();
        assertThat(connection.status()).isEqualTo("yielded");
        assertThat(connection.resumeAt()).isEqualTo(resumeAt);
        assertThat(connection.closeCode()).isEqualTo(1000);
    }
}

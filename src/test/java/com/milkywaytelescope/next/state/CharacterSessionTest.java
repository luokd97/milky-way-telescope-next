package com.milkywaytelescope.next.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.connection.ConnectionProfile;
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
}

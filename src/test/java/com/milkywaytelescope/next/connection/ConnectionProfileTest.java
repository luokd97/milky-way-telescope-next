package com.milkywaytelescope.next.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConnectionProfileTest {
    @Test
    void derivesCharacterIdAndRedactsHash() {
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=sample-value&characterId=12345&lang=en",
                "accessToken=sample-token"
        );

        assertThat(profile.characterId()).isEqualTo("12345");
        assertThat(profile.accessToken()).isEqualTo("sample-token");
        assertThat(profile.redactedUrl()).contains("hash=<redacted>").doesNotContain("sample-value");
    }

    @Test
    void rejectsUnexpectedHostAndMissingCharacter() {
        assertThatThrownBy(() -> ConnectionProfile.from(
                "wss://example.test/ws?characterId=123",
                "token"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder",
                "token"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}

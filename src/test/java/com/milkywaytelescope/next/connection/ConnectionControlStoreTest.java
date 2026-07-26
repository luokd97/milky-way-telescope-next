package com.milkywaytelescope.next.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionControlStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsYieldAcrossApplicationRestartsAndCanClearIt() {
        TelescopeProperties properties = new TelescopeProperties();
        Path controlFile = tempDir.resolve("connection-control.json");
        properties.getStorage().setControlFile(controlFile);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ConnectionControlState state = new ConnectionControlState(
                "42",
                Instant.parse("2026-07-26T08:00:00Z"),
                Instant.parse("2026-07-26T10:00:00Z"),
                "another device"
        );

        ConnectionControlStore store = new ConnectionControlStore(objectMapper, properties);
        store.load();
        store.save(state);

        ConnectionControlStore reloaded = new ConnectionControlStore(objectMapper, properties);
        reloaded.load();
        assertThat(reloaded.find("42")).isEqualTo(state);

        reloaded.delete("42");
        ConnectionControlStore cleared = new ConnectionControlStore(objectMapper, properties);
        cleared.load();
        assertThat(cleared.find("42")).isNull();
    }
}

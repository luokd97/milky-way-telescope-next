package com.milkywaytelescope.next.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
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
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ConnectionControlState state = new ConnectionControlState(
                "42",
                Instant.parse("2026-07-26T08:00:00Z"),
                Instant.parse("2026-07-26T10:00:00Z"),
                "another device"
        );

        ApplicationConfigStore configStore = new ApplicationConfigStore(objectMapper, properties);
        configStore.load();
        ConnectionControlStore store = new ConnectionControlStore(configStore);
        store.save(state);

        ApplicationConfigStore reloadedConfig = new ApplicationConfigStore(objectMapper, properties);
        reloadedConfig.load();
        ConnectionControlStore reloaded = new ConnectionControlStore(reloadedConfig);
        assertThat(reloaded.find("42")).isEqualTo(state);

        reloaded.delete("42");
        ApplicationConfigStore clearedConfig = new ApplicationConfigStore(objectMapper, properties);
        clearedConfig.load();
        ConnectionControlStore cleared = new ConnectionControlStore(clearedConfig);
        assertThat(cleared.find("42")).isNull();
    }
}

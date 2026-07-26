package com.milkywaytelescope.next.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionProfileStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsProfiles() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApplicationConfigStore configStore = new ApplicationConfigStore(objectMapper, properties);
        configStore.load();
        ConnectionProfileStore store = new ConnectionProfileStore(configStore);
        store.save(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=42",
                "sample-token"
        );

        ApplicationConfigStore reloadedConfig = new ApplicationConfigStore(objectMapper, properties);
        reloadedConfig.load();
        ConnectionProfileStore reloaded = new ConnectionProfileStore(reloadedConfig);

        assertThat(reloaded.findAll()).hasSize(1);
        assertThat(reloaded.find("42").redactedUrl()).contains("hash=<redacted>");

        reloadedConfig.update(current -> current.withDisabledConnections(List.of("42")));
        assertThat(reloaded.delete("42")).isNotNull();
        assertThat(reloadedConfig.current().disabledConnections()).isEmpty();
    }
}

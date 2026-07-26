package com.milkywaytelescope.next.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.config.TelescopeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void startsWithUnifiedDefaultsWhenSettingsFileIsMissing() {
        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper(), properties());

        store.load();

        assertThat(store.current().schemaVersion()).isEqualTo(2);
        assertThat(store.current().connectionSettings()).isEqualTo(ConnectionSettings.defaults());
        assertThat(store.current().disabledConnections()).isEmpty();
    }

    @Test
    void completesSchemaV1FieldsAndPersistsSchemaV2() throws Exception {
        TelescopeProperties properties = properties();
        Path settingsFile = properties.getStorage().getSettingsFile();
        Files.writeString(settingsFile, """
                {
                  "schemaVersion": 1,
                  "dashboard": {
                    "sectionOrder": [
                      "currentActivity",
                      "inventoryHighlights",
                      "actionQueue",
                      "recentAlerts"
                    ],
                    "inventoryWatchTerms": ["coin"]
                  },
                  "connections": [],
                  "connectionControls": []
                }
                """);

        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper(), properties);
        store.load();

        assertThat(store.current().schemaVersion()).isEqualTo(ApplicationConfig.CURRENT_SCHEMA_VERSION);
        assertThat(store.current().connectionSettings()).isEqualTo(ConnectionSettings.defaults());
        assertThat(store.current().disabledConnections()).isEmpty();
        assertThat(objectMapper().readTree(settingsFile.toFile()).path("schemaVersion").asInt())
                .isEqualTo(ApplicationConfig.CURRENT_SCHEMA_VERSION);
        assertThat(objectMapper().readTree(settingsFile.toFile()).has("connectionSettings")).isTrue();
        assertThat(objectMapper().readTree(settingsFile.toFile()).has("disabledConnections")).isTrue();
    }

    @Test
    void rejectsUnsupportedSchemaWithoutReplacingTheRunningConfig() throws Exception {
        TelescopeProperties properties = properties();
        Path settingsFile = properties.getStorage().getSettingsFile();
        Files.writeString(settingsFile, "{\"schemaVersion\": 99}");
        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper(), properties);

        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to load application config");
    }

    @Test
    void replacesConfigAtomicallyAndReloadsTheNewShape() throws Exception {
        TelescopeProperties properties = properties();
        ObjectMapper objectMapper = objectMapper();
        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper, properties);
        store.load();

        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                "sample-token"
        );
        ApplicationConfig next = new ApplicationConfig(
                ApplicationConfig.CURRENT_SCHEMA_VERSION,
                new DashboardSettings(
                        DashboardSettings.DEFAULT_SECTION_ORDER,
                        List.of("coin")
                ),
                new ConnectionSettings(false, false, Duration.ofSeconds(5), Duration.ofHours(1)),
                List.of(profile),
                List.of(profile.characterId()),
                List.of()
        );
        store.replace(next);

        ApplicationConfigStore reloaded = new ApplicationConfigStore(objectMapper, properties);
        reloaded.load();
        assertThat(reloaded.current()).isEqualTo(next);
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private TelescopeProperties properties() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));
        return properties;
    }
}

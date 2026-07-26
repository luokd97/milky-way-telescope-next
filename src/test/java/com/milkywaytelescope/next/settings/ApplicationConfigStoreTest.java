package com.milkywaytelescope.next.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.connection.ConnectionControlState;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.config.TelescopeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyFilesIntoOneConfigAndKeepsBackups() throws Exception {
        TelescopeProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path settingsFile = properties.getStorage().getSettingsFile();
        Path connectionsFile = properties.getStorage().getConnectionFile();
        Path controlsFile = properties.getStorage().getControlFile();

        Files.writeString(settingsFile, """
                {
                  "sectionOrder": [
                    "inventoryHighlights",
                    "currentActivity",
                    "actionQueue",
                    "recentAlerts"
                  ],
                  "inventoryWatchTerms": ["wisdom_tea"]
                }
                """);
        objectMapper.writeValue(connectionsFile.toFile(), List.of(ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=42",
                "sample-token"
        )));
        objectMapper.writeValue(controlsFile.toFile(), List.of(new ConnectionControlState(
                "42",
                Instant.parse("2026-07-26T08:00:00Z"),
                Instant.parse("2026-07-26T10:00:00Z"),
                "another device"
        )));

        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper, properties);
        store.load();

        assertThat(store.current().schemaVersion()).isEqualTo(ApplicationConfig.CURRENT_SCHEMA_VERSION);
        assertThat(store.current().dashboard().inventoryWatchTerms()).containsExactly("wisdom_tea");
        assertThat(store.current().connections()).extracting(ConnectionProfile::characterId)
                .containsExactly("42");
        assertThat(store.current().connectionControls()).extracting(ConnectionControlState::characterId)
                .containsExactly("42");
        assertThat(objectMapper.readTree(settingsFile.toFile()).path("schemaVersion").asInt())
                .isEqualTo(ApplicationConfig.CURRENT_SCHEMA_VERSION);
        assertThat(Files.exists(Path.of(settingsFile + ".pre-unified.bak"))).isTrue();
        assertThat(Files.exists(Path.of(connectionsFile + ".pre-unified.bak"))).isTrue();
        assertThat(Files.exists(Path.of(controlsFile + ".pre-unified.bak"))).isTrue();
    }

    @Test
    void rejectsDuplicateLegacyConnectionsWithoutOverwritingTheSource() throws Exception {
        TelescopeProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path settingsFile = properties.getStorage().getSettingsFile();
        Path connectionsFile = properties.getStorage().getConnectionFile();
        Files.writeString(settingsFile, """
                {"sectionOrder":["currentActivity","inventoryHighlights","actionQueue","recentAlerts"]}
                """);
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=42",
                "sample-token"
        );
        objectMapper.writeValue(connectionsFile.toFile(), List.of(profile, profile));
        String before = Files.readString(settingsFile);

        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper, properties);

        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to load application config");
        assertThat(Files.readString(settingsFile)).isEqualTo(before);
        assertThat(objectMapper.readTree(settingsFile.toFile()).has("schemaVersion")).isFalse();
    }

    @Test
    void rejectsInvalidLegacyControlTimesWithoutOverwritingTheSource() throws Exception {
        TelescopeProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path settingsFile = properties.getStorage().getSettingsFile();
        Path controlsFile = properties.getStorage().getControlFile();
        Files.writeString(settingsFile, """
                {"sectionOrder":["currentActivity","inventoryHighlights","actionQueue","recentAlerts"]}
                """);
        Files.writeString(controlsFile, """
                [{
                  "characterId":"42",
                  "yieldedAt":"2026-07-26T10:00:00Z",
                  "resumeAt":"2026-07-26T09:00:00Z",
                  "reason":"invalid"
                }]
                """);
        String before = Files.readString(settingsFile);

        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper, properties);

        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to load application config");
        assertThat(Files.readString(settingsFile)).isEqualTo(before);
        assertThat(Files.exists(Path.of(settingsFile + ".pre-unified.bak"))).isFalse();
    }

    @Test
    void replacesConfigAtomicallyAndReloadsTheNewShape() throws Exception {
        TelescopeProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApplicationConfigStore store = new ApplicationConfigStore(objectMapper, properties);
        store.load();

        ApplicationConfig next = new ApplicationConfig(
                new DashboardSettings(
                        DashboardSettings.DEFAULT_SECTION_ORDER,
                        List.of("coin")
                ),
                List.of(ConnectionProfile.from(
                        "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=7",
                        "sample-token"
                )),
                List.of()
        );
        store.replace(next);

        ApplicationConfigStore reloaded = new ApplicationConfigStore(objectMapper, properties);
        reloaded.load();
        assertThat(reloaded.current()).isEqualTo(next);
    }

    private TelescopeProperties properties() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));
        properties.getStorage().setConnectionFile(tempDir.resolve("connections.json"));
        properties.getStorage().setControlFile(tempDir.resolve("connection-control.json"));
        return properties;
    }
}

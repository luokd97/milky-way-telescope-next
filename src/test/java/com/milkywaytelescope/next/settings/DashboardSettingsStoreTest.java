package com.milkywaytelescope.next.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardSettingsStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void usesDefaultOrderAndPersistsGlobalOrder() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));
        properties.getStorage().setConnectionFile(tempDir.resolve("connections.json"));
        properties.getStorage().setControlFile(tempDir.resolve("connection-control.json"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApplicationConfigStore configStore = new ApplicationConfigStore(objectMapper, properties);
        configStore.load();
        DashboardSettingsStore store = new DashboardSettingsStore(configStore);

        assertThat(store.current().sectionOrder()).containsExactlyElementsOf(
                DashboardSettings.DEFAULT_SECTION_ORDER
        );
        assertThat(store.current().inventoryWatchTerms()).isEmpty();

        List<String> customOrder = List.of(
                DashboardSettings.INVENTORY_HIGHLIGHTS,
                DashboardSettings.CURRENT_ACTIVITY,
                DashboardSettings.RECENT_ALERTS,
                DashboardSettings.ACTION_QUEUE
        );
        store.save(customOrder, List.of(" wisdom_tea ", "", "coin", "coin"));

        assertThat(store.current().inventoryWatchTerms())
                .containsExactly("wisdom_tea", "coin");

        ApplicationConfigStore reloadedConfig = new ApplicationConfigStore(objectMapper, properties);
        reloadedConfig.load();
        DashboardSettingsStore reloaded = new DashboardSettingsStore(reloadedConfig);
        assertThat(reloaded.current().sectionOrder()).containsExactlyElementsOf(customOrder);
        assertThat(reloaded.current().inventoryWatchTerms())
                .containsExactly("wisdom_tea", "coin");
    }

    @Test
    void loadsLegacySettingsWithoutInventoryWatchTerms() throws Exception {
        TelescopeProperties properties = new TelescopeProperties();
        Path settingsFile = tempDir.resolve("legacy-settings.json");
        properties.getStorage().setSettingsFile(settingsFile);
        properties.getStorage().setConnectionFile(tempDir.resolve("connections.json"));
        properties.getStorage().setControlFile(tempDir.resolve("connection-control.json"));
        java.nio.file.Files.writeString(settingsFile, """
                {
                  "sectionOrder": [
                    "currentActivity",
                    "inventoryHighlights",
                    "actionQueue",
                    "recentAlerts"
                  ]
                }
                """);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApplicationConfigStore configStore = new ApplicationConfigStore(objectMapper, properties);
        configStore.load();
        DashboardSettingsStore store = new DashboardSettingsStore(configStore);

        assertThat(store.current().inventoryWatchTerms()).isEmpty();
    }

    @Test
    void rejectsIncompleteDuplicateAndUnknownSectionsWithoutChangingCurrentOrder() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));
        properties.getStorage().setConnectionFile(tempDir.resolve("connections.json"));
        properties.getStorage().setControlFile(tempDir.resolve("connection-control.json"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApplicationConfigStore configStore = new ApplicationConfigStore(objectMapper, properties);
        configStore.load();
        DashboardSettingsStore store = new DashboardSettingsStore(configStore);

        assertThatThrownBy(() -> store.save(List.of(
                DashboardSettings.CURRENT_ACTIVITY,
                DashboardSettings.INVENTORY_HIGHLIGHTS,
                DashboardSettings.ACTION_QUEUE
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.save(List.of(
                DashboardSettings.CURRENT_ACTIVITY,
                DashboardSettings.CURRENT_ACTIVITY,
                DashboardSettings.ACTION_QUEUE,
                DashboardSettings.RECENT_ALERTS
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> store.save(List.of(
                DashboardSettings.CURRENT_ACTIVITY,
                DashboardSettings.INVENTORY_HIGHLIGHTS,
                DashboardSettings.ACTION_QUEUE,
                "unknown"
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThat(store.current().sectionOrder()).containsExactlyElementsOf(
                DashboardSettings.DEFAULT_SECTION_ORDER
        );
    }
}

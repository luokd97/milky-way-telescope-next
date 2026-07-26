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
        DashboardSettingsStore store = new DashboardSettingsStore(new ObjectMapper(), properties);
        store.load();

        assertThat(store.current().sectionOrder()).containsExactlyElementsOf(
                DashboardSettings.DEFAULT_SECTION_ORDER
        );

        List<String> customOrder = List.of(
                DashboardSettings.INVENTORY_HIGHLIGHTS,
                DashboardSettings.CURRENT_ACTIVITY,
                DashboardSettings.RECENT_ALERTS,
                DashboardSettings.ACTION_QUEUE
        );
        store.save(customOrder);

        DashboardSettingsStore reloaded = new DashboardSettingsStore(new ObjectMapper(), properties);
        reloaded.load();
        assertThat(reloaded.current().sectionOrder()).containsExactlyElementsOf(customOrder);
    }

    @Test
    void rejectsIncompleteDuplicateAndUnknownSectionsWithoutChangingCurrentOrder() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setSettingsFile(tempDir.resolve("settings.json"));
        DashboardSettingsStore store = new DashboardSettingsStore(new ObjectMapper(), properties);
        store.load();

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

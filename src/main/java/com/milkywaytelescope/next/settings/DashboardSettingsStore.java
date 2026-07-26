package com.milkywaytelescope.next.settings;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Compatibility facade for dashboard settings. The unified config store owns persistence.
 */
@Component
public class DashboardSettingsStore {
    private final ApplicationConfigStore configStore;

    public DashboardSettingsStore(ApplicationConfigStore configStore) {
        this.configStore = configStore;
    }

    public DashboardSettings current() {
        return configStore.current().dashboard();
    }

    public DashboardSettings save(List<String> sectionOrder) {
        return save(sectionOrder, current().inventoryWatchTerms());
    }

    public DashboardSettings save(List<String> sectionOrder, List<String> inventoryWatchTerms) {
        ApplicationConfig saved = configStore.update(current -> current.withDashboard(
                new DashboardSettings(sectionOrder, inventoryWatchTerms)
        ));
        return saved.dashboard();
    }
}

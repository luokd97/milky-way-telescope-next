package com.milkywaytelescope.next.api;

import com.milkywaytelescope.next.settings.DashboardSettings;
import com.milkywaytelescope.next.settings.DashboardSettingsStore;
import com.milkywaytelescope.next.state.ConnectionRegistry;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {
    private final DashboardSettingsStore settingsStore;
    private final ConnectionRegistry registry;

    public AdminSettingsController(DashboardSettingsStore settingsStore, ConnectionRegistry registry) {
        this.settingsStore = settingsStore;
        this.registry = registry;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardSettings> dashboard() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(settingsStore.current());
    }

    @PutMapping("/dashboard")
    public ResponseEntity<DashboardSettings> updateDashboard(@RequestBody DashboardSettingsInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Dashboard settings are required");
        }
        DashboardSettings current = settingsStore.current();
        List<String> sectionOrder = input.sectionOrder() == null
                ? current.sectionOrder()
                : input.sectionOrder();
        List<String> inventoryWatchTerms = input.inventoryWatchTerms() == null
                ? current.inventoryWatchTerms()
                : input.inventoryWatchTerms();
        DashboardSettings saved = settingsStore.save(sectionOrder, inventoryWatchTerms);
        registry.updateInventoryWatchTerms(saved.inventoryWatchTerms());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(saved);
    }

    public record DashboardSettingsInput(
            List<String> sectionOrder,
            List<String> inventoryWatchTerms
    ) {
    }
}

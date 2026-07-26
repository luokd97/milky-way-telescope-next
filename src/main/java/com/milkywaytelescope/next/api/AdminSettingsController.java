package com.milkywaytelescope.next.api;

import com.milkywaytelescope.next.settings.DashboardSettings;
import com.milkywaytelescope.next.settings.DashboardSettingsStore;
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

    public AdminSettingsController(DashboardSettingsStore settingsStore) {
        this.settingsStore = settingsStore;
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
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(settingsStore.save(input.sectionOrder()));
    }

    public record DashboardSettingsInput(List<String> sectionOrder) {
    }
}

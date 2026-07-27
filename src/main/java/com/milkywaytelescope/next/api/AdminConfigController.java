package com.milkywaytelescope.next.api;

import com.milkywaytelescope.next.settings.ApplicationConfig;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
import com.milkywaytelescope.next.state.ConnectionRegistry;
import com.milkywaytelescope.next.connection.WssConnectionManager;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {
    private final ApplicationConfigStore configStore;
    private final ConnectionRegistry registry;
    private final WssConnectionManager connectionManager;

    public AdminConfigController(
            ApplicationConfigStore configStore,
            ConnectionRegistry registry,
            WssConnectionManager connectionManager
    ) {
        this.configStore = configStore;
        this.registry = registry;
        this.connectionManager = connectionManager;
    }

    @GetMapping
    public ResponseEntity<ApplicationConfig> current() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(configStore.current());
    }

    @PutMapping
    public ResponseEntity<ApplicationConfig> replace(@RequestBody ApplicationConfig input) {
        ApplicationConfig saved = configStore.replace(input);
        registry.updateInventoryWatchTerms(saved.dashboard().inventoryWatchTerms());
        registry.updateMessageFilter(saved.message().filter());
        connectionManager.reconcile(saved.connections(), saved.connectionControls());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(saved);
    }
}

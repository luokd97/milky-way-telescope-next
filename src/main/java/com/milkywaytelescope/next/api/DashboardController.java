package com.milkywaytelescope.next.api;

import com.milkywaytelescope.next.state.CharacterSession.CharacterSnapshot;
import com.milkywaytelescope.next.state.CharacterSession.MessageView;
import com.milkywaytelescope.next.state.ConnectionRegistry;
import com.milkywaytelescope.next.settings.DashboardSettings;
import com.milkywaytelescope.next.settings.DashboardSettingsStore;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final ConnectionRegistry registry;
    private final DashboardSettingsStore settingsStore;

    public DashboardController(ConnectionRegistry registry, DashboardSettingsStore settingsStore) {
        this.registry = registry;
        this.settingsStore = settingsStore;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardView> dashboard() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new DashboardView(Instant.now(), settingsStore.current(), registry.snapshots(20, false)));
    }

    @GetMapping("/characters/{characterId}/messages")
    public ResponseEntity<List<MessageView>> messages(
            @PathVariable String characterId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean includePayload
    ) {
        var session = registry.get(characterId);
        if (session == null) {
            throw new ResponseStatusException(NOT_FOUND, "Character session not found");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(session.snapshot(boundedLimit, includePayload).recentMessages());
    }

    public record DashboardView(
            Instant generatedAt,
            DashboardSettings settings,
            List<CharacterSnapshot> characters
    ) {
    }
}

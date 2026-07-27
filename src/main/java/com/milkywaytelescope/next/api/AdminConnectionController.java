package com.milkywaytelescope.next.api;

import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.connection.ConnectionProfileStore;
import com.milkywaytelescope.next.connection.WssConnectionManager;
import com.milkywaytelescope.next.state.ConnectionRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/connections")
public class AdminConnectionController {
    private final ConnectionProfileStore profileStore;
    private final WssConnectionManager connectionManager;
    private final ConnectionRegistry registry;

    public AdminConnectionController(
            ConnectionProfileStore profileStore,
            WssConnectionManager connectionManager,
            ConnectionRegistry registry
    ) {
        this.profileStore = profileStore;
        this.connectionManager = connectionManager;
        this.registry = registry;
    }

    @GetMapping
    public List<ConnectionView> list() {
        return profileStore.findAll().stream().map(this::view).toList();
    }

    @PostMapping
    public ResponseEntity<ConnectionView> create(@Valid @RequestBody ConnectionInput input) {
        ConnectionProfile candidate = ConnectionProfile.from(input.url(), input.accessToken());
        if (profileStore.find(candidate.characterId()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A connection for this character already exists");
        }
        ConnectionProfile saved = profileStore.save(input.url(), input.accessToken());
        connectionManager.apply(saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved));
    }

    @PutMapping("/{characterId}")
    public ConnectionView update(
            @PathVariable String characterId,
            @Valid @RequestBody ConnectionInput input
    ) {
        ConnectionProfile saved = profileStore.update(characterId, input.url(), input.accessToken());
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found");
        }
        connectionManager.apply(saved);
        return view(saved);
    }

    @DeleteMapping("/{characterId}")
    public ResponseEntity<Void> delete(@PathVariable String characterId) {
        if (profileStore.delete(characterId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found");
        }
        connectionManager.remove(characterId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{characterId}/recent-alerts/clear")
    public ResponseEntity<Void> clearRecentAlerts(@PathVariable String characterId) {
        if (profileStore.find(characterId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found");
        }
        registry.clearRecentEvents(characterId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{characterId}/reconnect")
    public ResponseEntity<Void> reconnect(@PathVariable String characterId) {
        if (!connectionManager.reconnect(characterId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{characterId}/disconnect")
    public ResponseEntity<Void> disconnect(@PathVariable String characterId) {
        if (!connectionManager.disconnect(characterId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{characterId}/yield/extend")
    public ResponseEntity<Void> extendYield(@PathVariable String characterId) {
        if (profileStore.find(characterId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found");
        }
        if (!connectionManager.extendYield(characterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection is not yielded");
        }
        return ResponseEntity.accepted().build();
    }

    private ConnectionView view(ConnectionProfile profile) {
        var session = registry.get(profile.characterId());
        var snapshot = session == null ? null : session.snapshot(0, false);
        return new ConnectionView(
                profile.characterId(),
                profile.redactedUrl(),
                true,
                snapshot == null ? "idle" : snapshot.connection().status(),
                snapshot == null ? null : snapshot.connection().error(),
                snapshot == null ? null : snapshot.connection().yieldedAt(),
                snapshot == null ? null : snapshot.connection().resumeAt(),
                snapshot == null ? null : snapshot.connection().yieldReason()
        );
    }

    public record ConnectionInput(@NotBlank String url, @NotBlank String accessToken) {
    }

    public record ConnectionView(
            String characterId,
            String redactedUrl,
            boolean hasAccessToken,
            String status,
            String error,
            java.time.Instant yieldedAt,
            java.time.Instant resumeAt,
            String yieldReason
    ) {
    }
}

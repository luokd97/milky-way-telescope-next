package com.milkywaytelescope.next.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.TelescopeNextApplication;
import com.milkywaytelescope.next.connection.ConnectionProfile;
import com.milkywaytelescope.next.settings.ApplicationConfig;
import com.milkywaytelescope.next.settings.ApplicationConfigStore;
import com.milkywaytelescope.next.settings.ConnectionSettings;
import com.milkywaytelescope.next.settings.DashboardSettings;
import com.milkywaytelescope.next.state.ConnectionRegistry;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TelescopeNextApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "telescope.site-password=test-password",
        "telescope.storage.settings-file=${java.io.tmpdir}/telescope-next-security-settings-test.json",
})
class SecurityIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ConnectionRegistry registry;

    @Autowired
    ApplicationConfigStore configStore;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void redirectsAnonymousDashboardAndAllowsAuthenticatedOwner() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/dashboard").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/settings").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin").with(user("owner").roles("OWNER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"));
    }

    @Test
    void protectsMutatingRequestsWithCsrf() throws Exception {
        mockMvc.perform(post("/api/admin/connections/1/reconnect")
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/connections/1/reconnect")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/admin/connections/1/disconnect")
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/connections/1/disconnect")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/admin/connections/1/recent-alerts/clear")
                        .with(user("owner").roles("OWNER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/connections/1/recent-alerts/clear")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearsRecentAlertsForConfiguredCharacter() throws Exception {
        String characterId = "77001";
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=" + characterId,
                "clear-alerts-token"
        );
        ApplicationConfig previous = configStore.current();
        configStore.replace(previous.withConnections(List.of(profile)));
        var session = registry.getOrCreate(profile);
        long generation = session.beginGeneration(profile);
        session.recordText(generation, """
                {
                  "type": "init_character_data",
                  "character": {"id": 77001, "name": "Alert Observer"}
                }
                """);
        session.recordText(generation, """
                {
                  "type": "action_completed",
                  "endCharacterItems": [
                    {"itemHrid": "/items/wisdom_tea", "count": 10}
                  ]
                }
                """);
        try {
            assertThat(session.snapshot(100, false).recentEvents()).hasSize(1);

            mockMvc.perform(post("/api/admin/connections/" + characterId + "/recent-alerts/clear")
                            .with(user("owner").roles("OWNER"))
                            .with(csrf()))
                    .andExpect(status().isNoContent());

            var cleared = session.snapshot(100, false);
            assertThat(cleared.recentEvents()).isEmpty();
            assertThat(cleared.recentMessages()).hasSize(2);

            session.recordText(generation, """
                    {
                      "type": "action_completed",
                      "endCharacterItems": [
                        {"itemHrid": "/items/wisdom_tea", "count": 5}
                      ]
                    }
                    """);
            assertThat(session.snapshot(100, false).recentEvents()).hasSize(1);
        } finally {
            registry.remove(characterId);
            configStore.replace(previous);
        }
    }

    @Test
    void disconnectPreservesProfileAndReconnectClearsDisabledState() throws Exception {
        String characterId = "73001";
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=" + characterId,
                "disconnect-token"
        );
        ApplicationConfig previous = configStore.current();
        configStore.replace(new ApplicationConfig(
                ApplicationConfig.CURRENT_SCHEMA_VERSION,
                DashboardSettings.defaults(),
                new ConnectionSettings(false, false, java.time.Duration.ofSeconds(1), java.time.Duration.ofHours(1)),
                List.of(profile),
                List.of(),
                List.of()
        ));
        try {
            mockMvc.perform(post("/api/admin/connections/" + characterId + "/disconnect")
                            .with(user("owner").roles("OWNER"))
                            .with(csrf()))
                    .andExpect(status().isAccepted());

            assertThat(configStore.current().connections()).extracting(ConnectionProfile::characterId)
                    .containsExactly(characterId);
            assertThat(configStore.current().disabledConnections()).containsExactly(characterId);
            mockMvc.perform(get("/api/admin/connections")
                            .with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("disconnected"));

            mockMvc.perform(post("/api/admin/connections/" + characterId + "/reconnect")
                            .with(user("owner").roles("OWNER"))
                            .with(csrf()))
                    .andExpect(status().isAccepted());

            assertThat(configStore.current().connections()).extracting(ConnectionProfile::characterId)
                    .containsExactly(characterId);
            assertThat(configStore.current().disabledConnections()).doesNotContain(characterId);
        } finally {
            configStore.replace(previous);
        }
    }

    @Test
    void protectsPersistsAndPublishesGlobalDashboardSettings() throws Exception {
        String orderJson = """
                {
                  "sectionOrder": [
                    "inventoryHighlights",
                    "currentActivity",
                    "actionQueue",
                    "recentAlerts"
                  ]
                }
                """;
        String watchTermsJson = """
                {
                  "inventoryWatchTerms": ["wisdom_tea", "coin"]
                }
                """;

        mockMvc.perform(put("/api/admin/settings/dashboard")
                        .with(user("owner").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings/dashboard")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.sectionOrder[0]").value("inventoryHighlights"));

        mockMvc.perform(put("/api/admin/settings/dashboard")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(watchTermsJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryWatchTerms[0]").value("wisdom_tea"))
                .andExpect(jsonPath("$.inventoryWatchTerms[1]").value("coin"));

        mockMvc.perform(get("/api/dashboard").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.sectionOrder[0]").value("inventoryHighlights"))
                .andExpect(jsonPath("$.settings.inventoryWatchTerms[0]").value("wisdom_tea"));
    }

    @Test
    void exposesFullConfigOnlyThroughAuthenticatedConfigEndpoint() throws Exception {
        String secretHash = "full-config-secret-hash";
        String accessToken = "full-config-secret-token";
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=" + secretHash + "&characterId=42",
                accessToken
        );
        ApplicationConfig previous = configStore.current();
        configStore.replace(new ApplicationConfig(
                new DashboardSettings(DashboardSettings.DEFAULT_SECTION_ORDER, List.of("coin")),
                List.of(profile),
                List.of()
        ));
        try {
            mockMvc.perform(get("/api/admin/config"))
                    .andExpect(status().is3xxRedirection());

            mockMvc.perform(get("/api/admin/config").with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value(2))
                    .andExpect(jsonPath("$.connections[0].accessToken").value(accessToken))
                    .andExpect(jsonPath("$.connections[0].url").value(profile.url()));

            String dashboard = mockMvc.perform(get("/api/dashboard").with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(dashboard)
                    .doesNotContain(secretHash)
                    .doesNotContain(accessToken)
                    .doesNotContain("connections");
        } finally {
            configStore.replace(previous);
        }
    }

    @Test
    void protectsFullConfigReplacementWithCsrf() throws Exception {
        String configJson = """
                {
                    "schemaVersion": 2,
                  "dashboard": {
                    "sectionOrder": [
                      "currentActivity",
                      "inventoryHighlights",
                      "actionQueue",
                      "recentAlerts"
                    ],
                    "inventoryWatchTerms": []
                  },
                  "connectionSettings": {
                    "autoConnect": false,
                    "autoReconnect": false,
                    "reconnectDelay": "PT30S",
                    "takeoverYieldDuration": "PT2H"
                  },
                  "connections": [],
                  "disabledConnections": [],
                  "connectionControls": []
                }
                """;

        mockMvc.perform(put("/api/admin/config")
                        .with(user("owner").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/config")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(2))
                .andExpect(jsonPath("$.connections").isEmpty());
    }

    @Test
    void rejectsInvalidFullConfigWithoutReplacingTheRunningConfig() throws Exception {
        ApplicationConfig previous = configStore.current();
        String invalidConfigJson = """
                {
                  "schemaVersion": 2,
                  "dashboard": {
                    "sectionOrder": [
                      "currentActivity",
                      "inventoryHighlights",
                      "actionQueue",
                      "recentAlerts"
                    ],
                    "inventoryWatchTerms": []
                  },
                  "connectionSettings": {
                    "autoConnect": false,
                    "autoReconnect": false,
                    "reconnectDelay": "PT30S",
                    "takeoverYieldDuration": "PT2H"
                  },
                  "connections": [{
                    "characterId": "42",
                    "url": "https://not-a-websocket.example/ws?characterId=42",
                    "accessToken": "sample-token"
                  }],
                  "disabledConnections": [],
                  "connectionControls": []
                }
                """;

        mockMvc.perform(put("/api/admin/config")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidConfigJson))
                .andExpect(status().isBadRequest());

        assertThat(configStore.current()).isEqualTo(previous);
    }

    @Test
    void dashboardReturnsProjectedFieldsWithoutSecretsOrRawPayloads() throws Exception {
        String characterId = "99001";
        String secretHash = "dashboard-secret-hash";
        String accessToken = "dashboard-secret-token";
        ConnectionProfile profile = ConnectionProfile.from(
                "wss://api.milkywayidle.com/ws?hash=" + secretHash + "&characterId=" + characterId,
                accessToken
        );
        var session = registry.getOrCreate(profile);
        long generation = session.beginGeneration(profile);
        session.markConnected(generation);
        session.recordText(generation, """
                {
                  "type": "init_character_data",
                  "character": {"id": 99001, "name": "API Observer", "gameMode": "standard"},
                  "characterActions": [],
                  "characterItems": []
                }
                """);

        try {
            var result = mockMvc.perform(get("/api/dashboard").with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                    .andReturn();
            String responseBody = result.getResponse().getContentAsString();
            JsonNode target = findCharacter(objectMapper.readTree(responseBody), characterId);

            assertThat(target).isNotNull();
            assertThat(target.path("dataStatus").asText()).isEqualTo("live");
            assertThat(target.path("currentAction").isNull()).isTrue();
            assertThat(target.path("actionQueue").isArray()).isTrue();
            assertThat(target.path("battle").isObject()).isTrue();
            assertThat(target.path("inventoryHighlights").isArray()).isTrue();
            assertThat(target.path("recentMessages").get(0).path("payload").isTextual()).isFalse();
            assertThat(responseBody)
                    .doesNotContain(secretHash)
                    .doesNotContain(accessToken)
                    .doesNotContain("\"characterActions\"");
        } finally {
            registry.remove(characterId);
        }
    }

    private static JsonNode findCharacter(JsonNode dashboard, String characterId) {
        for (JsonNode character : dashboard.path("characters")) {
            if (characterId.equals(character.path("connection").path("characterId").asText())) {
                return character;
            }
        }
        return null;
    }
}

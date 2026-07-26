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
import com.milkywaytelescope.next.state.ConnectionRegistry;
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
        "telescope.storage.connection-file=${java.io.tmpdir}/telescope-next-security-test.json",
        "telescope.storage.control-file=${java.io.tmpdir}/telescope-next-security-control-test.json",
        "telescope.storage.settings-file=${java.io.tmpdir}/telescope-next-security-settings-test.json",
        "telescope.wss.auto-connect=false"
})
class SecurityIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ConnectionRegistry registry;

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
    }

    @Test
    void protectsPersistsAndPublishesGlobalDashboardOrder() throws Exception {
        String settingsJson = """
                {
                  "sectionOrder": [
                    "inventoryHighlights",
                    "currentActivity",
                    "actionQueue",
                    "recentAlerts"
                  ]
                }
                """;

        mockMvc.perform(put("/api/admin/settings/dashboard")
                        .with(user("owner").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsJson))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings/dashboard")
                        .with(user("owner").roles("OWNER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsJson))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.sectionOrder[0]").value("inventoryHighlights"));

        mockMvc.perform(get("/api/dashboard").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.sectionOrder[0]").value("inventoryHighlights"));
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

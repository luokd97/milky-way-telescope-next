package com.milkywaytelescope.next.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.milkywaytelescope.next.TelescopeNextApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TelescopeNextApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "telescope.site-password=test-password",
        "telescope.storage.connection-file=${java.io.tmpdir}/telescope-next-security-test.json",
        "telescope.storage.control-file=${java.io.tmpdir}/telescope-next-security-control-test.json",
        "telescope.wss.auto-connect=false"
})
class SecurityIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void redirectsAnonymousDashboardAndAllowsAuthenticatedOwner() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/dashboard").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk());
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
}

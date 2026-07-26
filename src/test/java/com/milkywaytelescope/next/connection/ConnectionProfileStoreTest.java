package com.milkywaytelescope.next.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.milkywaytelescope.next.config.TelescopeProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionProfileStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsProfiles() {
        TelescopeProperties properties = new TelescopeProperties();
        properties.getStorage().setConnectionFile(tempDir.resolve("connections.json"));

        ConnectionProfileStore store = new ConnectionProfileStore(new ObjectMapper(), properties);
        store.load();
        store.save(
                "wss://api.milkywayidle.com/ws?hash=placeholder&characterId=42",
                "sample-token"
        );

        ConnectionProfileStore reloaded = new ConnectionProfileStore(new ObjectMapper(), properties);
        reloaded.load();

        assertThat(reloaded.findAll()).hasSize(1);
        assertThat(reloaded.find("42").redactedUrl()).contains("hash=<redacted>");
    }
}

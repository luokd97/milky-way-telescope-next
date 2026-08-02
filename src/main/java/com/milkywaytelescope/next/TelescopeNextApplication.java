package com.milkywaytelescope.next;

import com.milkywaytelescope.next.config.TelescopeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = TelescopeProperties.class)
public class TelescopeNextApplication {
    private static final Logger log = LoggerFactory.getLogger(TelescopeNextApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TelescopeNextApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    void logDashboardUrl(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof WebServerApplicationContext context
                && context.getWebServer() != null) {
            log.info("Dashboard ready: http://127.0.0.1:{}", context.getWebServer().getPort());
        }
    }
}

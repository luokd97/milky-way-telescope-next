package com.milkywaytelescope.next;

import com.milkywaytelescope.next.config.TelescopeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = TelescopeProperties.class)
public class TelescopeNextApplication {
    public static void main(String[] args) {
        SpringApplication.run(TelescopeNextApplication.class, args);
    }
}

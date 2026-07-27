package com.milkywaytelescope.next.security;

import com.milkywaytelescope.next.config.TelescopeProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private static final int REMEMBER_ME_VALIDITY_SECONDS = (int) Duration.ofDays(30).toSeconds();

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(TelescopeProperties properties, PasswordEncoder encoder) {
        String passwordHash = properties.getSitePasswordHash();
        if (passwordHash == null || passwordHash.isBlank()) {
            String password = properties.getSitePassword();
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "MONITOR_SITE_PASSWORD_HASH must be set (MONITOR_SITE_PASSWORD is legacy fallback)"
                );
            }
            passwordHash = encoder.encode(password);
        }
        return new InMemoryUserDetailsManager(User.withUsername("owner")
                .password(passwordHash)
                .roles("OWNER")
                .build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TelescopeProperties properties) throws Exception {
        String configuredRememberMeKey = properties.getRememberMeKey();
        final String rememberMeKey = configuredRememberMeKey == null || configuredRememberMeKey.isBlank()
                ? fallbackRememberMeKey(properties)
                : configuredRememberMeKey;
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/styles.css", "/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .rememberMe(rememberMe -> rememberMe
                        .key(rememberMeKey)
                        .tokenValiditySeconds(REMEMBER_ME_VALIDITY_SECONDS)
                        .alwaysRemember(true))
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());
        return http.build();
    }

    private static String fallbackRememberMeKey(TelescopeProperties properties) {
        if (properties.getSitePasswordHash() != null && !properties.getSitePasswordHash().isBlank()) {
            return properties.getSitePasswordHash();
        }
        if (properties.getSitePassword() != null && !properties.getSitePassword().isBlank()) {
            return properties.getSitePassword();
        }
        throw new IllegalStateException("MONITOR_REMEMBER_ME_KEY must be set");
    }
}

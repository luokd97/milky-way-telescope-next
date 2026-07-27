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
        String password = properties.getSitePassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("MONITOR_SITE_PASSWORD must be set");
        }
        return new InMemoryUserDetailsManager(User.withUsername("owner")
                .password(encoder.encode(password))
                .roles("OWNER")
                .build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TelescopeProperties properties) throws Exception {
        String configuredRememberMeKey = properties.getRememberMeKey();
        final String rememberMeKey = configuredRememberMeKey == null || configuredRememberMeKey.isBlank()
                ? properties.getSitePassword()
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
}

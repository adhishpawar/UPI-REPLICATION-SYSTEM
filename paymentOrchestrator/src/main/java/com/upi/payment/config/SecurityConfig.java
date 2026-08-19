package com.upi.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security for the Payment Orchestrator.
 *
 * <p>This class was previously an empty {@code public class SecurityConfig {}}
 * with {@code spring-boot-starter-security} on the classpath. Spring Boot's
 * default then applied: every endpoint behind HTTP Basic with a generated
 * password printed at startup. The service was unusable and unsecured at the
 * same time.
 *
 * <p><b>Current posture, stated honestly.</b> Identity arrives as an
 * {@code X-User-Id} header that an API gateway is supposed to set from a
 * validated JWT. No gateway exists yet, so this header is <em>trusted, not
 * verified</em>: any caller can claim to be any user. That is acceptable in a
 * local learning environment and nowhere else.
 *
 * <p>The fix is available and small: {@code psp-service} already publishes
 * {@code /.well-known/jwks.json}, so this service could validate RS256 tokens
 * itself via {@code spring-security-oauth2-resource-server}. Tracked as open
 * question Q-5.
 *
 * <p>CSRF is disabled because this is a stateless token API with no cookies
 * and no browser form posts. On a session-cookie application that would be a
 * serious mistake; here it protects nothing.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${showcase.allowed-origins:http://localhost:8090}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * CORS for the backend showcase.
     *
     * <p>The showcase runs on its own origin and is deliberately not served by
     * this application, so that it stays deletable without affecting the
     * backend. Allowing its origin is an ordinary server-side concern, not a
     * coupling: the backend names an origin, knows nothing about what runs
     * there, and would behave identically if the showcase were deleted.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

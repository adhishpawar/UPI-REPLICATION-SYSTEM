package com.upi.psp.security;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @org.springframework.beans.factory.annotation.Value(
            "${showcase.allowed-origins:http://localhost:8090}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // CORS for the backend showcase, which now signs in here
                // rather than asserting an identity to the payment API.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Disable CSRF — we use JWT (stateless), not session cookies
                // CSRF attacks exploit browser cookie behavior. No cookies = no CSRF risk.
                .csrf(csrf -> csrf.disable())

                // Stateless: no HttpSession created. Every request is self-contained.
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Pre-flight carries no credentials by definition.
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        // Public endpoints — no JWT required
                        .requestMatchers("/api/v1/auth/register").permitAll()
                        .requestMatchers("/api/v1/auth/setup-mpin").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/validate").permitAll()  // Called by Gateway
                        // The controller serves this under the class-level
                        // /api/v1/auth prefix. Permitting only the bare path
                        // meant the real URL fell through to `.authenticated()`
                        // and returned 403 -- so the endpoint that exists
                        // precisely to let other services fetch the public key
                        // without credentials required credentials. Both forms
                        // are permitted now: the second is what a gateway
                        // would expose.
                        .requestMatchers("/api/v1/auth/.well-known/jwks.json").permitAll()
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Everything else requires valid JWT
                        .anyRequest().authenticated()
                )

                // Insert JWT filter BEFORE Spring's username/password filter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // No form login — this is a pure API service
                .formLogin(fl -> fl.disable())
                .httpBasic(hb -> hb.disable())

                .build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        // Strength 12 = 2^12 = 4096 BCrypt iterations
        // ~300ms per hash on modern hardware — intentionally slow to resist brute force
        // Trade-off: slower login (acceptable), much harder to crack if DB leaked
        return new BCryptPasswordEncoder(12);
    }

    /**
     * CORS for the showcase origin.
     *
     * <p>Note what is <b>not</b> here: {@code allowCredentials(true)}. This API
     * uses bearer tokens in a header, not cookies, so the browser has no
     * ambient credentials to send. Enabling credentialed CORS would widen the
     * surface for no benefit.
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration config =
                new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(java.util.Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("*"));
        config.setMaxAge(3600L);

        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

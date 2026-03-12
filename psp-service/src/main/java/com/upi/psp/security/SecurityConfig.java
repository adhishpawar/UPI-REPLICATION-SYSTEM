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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Disable CSRF — we use JWT (stateless), not session cookies
                // CSRF attacks exploit browser cookie behavior. No cookies = no CSRF risk.
                .csrf(csrf -> csrf.disable())

                // Stateless: no HttpSession created. Every request is self-contained.
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — no JWT required
                        .requestMatchers("/api/v1/auth/register").permitAll()
                        .requestMatchers("/api/v1/auth/setup-mpin").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/validate").permitAll()  // Called by Gateway
                        .requestMatchers("/.well-known/jwks.json").permitAll() // Public key
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
}




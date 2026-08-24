package com.upi.payment.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security for the Payment Orchestrator.
 *
 * <h3>What changed, and why it mattered</h3>
 *
 * Identity used to arrive as an {@code X-User-Id} header, documented as
 * "set by the API Gateway". No gateway existed. The header was therefore
 * <em>trusted but never verified</em>: any caller could name any user and move
 * that user's money. On an API whose entire purpose is moving money, that was
 * the weakest thing in the platform.
 *
 * <p>This service now validates the RS256 JWTs that {@code psp-service} issues,
 * using the JWK Set that service already published. Three properties follow,
 * and all three are why asymmetric signing is worth the extra machinery over a
 * shared secret:
 *
 * <ul>
 *   <li><b>No shared secret.</b> This service holds only a public key. Even
 *       fully compromised, it cannot mint a token.</li>
 *   <li><b>No call to psp on the request path.</b> Verification is local
 *       signature maths. psp being down does not stop payments from
 *       authenticating — only from issuing new tokens.</li>
 *   <li><b>Rotation without redeployment.</b> Tokens carry a {@code kid}; the
 *       JWK Set can publish several keys at once, so psp can roll its key and
 *       this service follows automatically.</li>
 * </ul>
 *
 * <h3>What is still not enforced, stated plainly</h3>
 *
 * <ul>
 *   <li>The observability endpoints under {@code /api/v1/execution/**} are
 *       open. They expose operational data about <em>all</em> payments and
 *       would sit behind an operator boundary in production. They are open
 *       here for two reasons: they are an operator surface rather than a
 *       customer one, and the browser's {@code EventSource} cannot send an
 *       {@code Authorization} header — so securing the stream would mean
 *       putting a token in a query string, which is a worse trade than an
 *       open read-only endpoint on localhost.</li>
 *   <li>Tokens are not checked against psp's revocation table. A logged-out
 *       token stays valid until it expires (default one hour). That is the
 *       standard trade-off of stateless JWTs; a short expiry is the mitigation,
 *       and introspection is the fix if revocation ever needs to be
 *       immediate.</li>
 * </ul>
 *
 * <p>CSRF is disabled because this is a stateless bearer-token API with no
 * cookies. On a session-cookie application that would be a serious mistake;
 * here it protects nothing.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${showcase.allowed-origins:http://localhost:8090}")
    private String allowedOrigins;

    @Value("${security.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${security.jwt.issuer:upi-psp-service}")
    private String issuer;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Pre-flight must not require a token: the browser sends it
                    // without one by definition.
                    .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()

                    // Operator surface. See the class comment.
                    .requestMatchers("/api/v1/execution/**").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                    // Everything that touches money requires a verified token.
                    .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.decoder(jwtDecoder()))
                    // Default is a 401 with an empty body, which is
                    // indistinguishable from the service being broken. Say why.
                    .authenticationEntryPoint((req, res, ex) -> {
                        res.setStatus(HttpStatus.UNAUTHORIZED.value());
                        res.setContentType("application/json");
                        res.getWriter().write(
                            "{\"errorCode\":\"UNAUTHENTICATED\",\"message\":"
                            + "\"A valid Bearer token from psp-service is required. "
                            + "POST /api/v1/auth/login on port 8082 to obtain one.\"}");
                    }))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * Decoder that fetches psp-service's public key from its JWK Set.
     *
     * <p>The issuer check is added explicitly. {@code withJwkSetUri} verifies
     * only that the signature is valid and the token has not expired — it does
     * not care who claims to have issued it. Without the extra validator, any
     * token signed by that key would be accepted regardless of its {@code iss}
     * claim, which matters the moment a second issuer shares infrastructure.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> withIssuer = new JwtIssuerValidator(issuer);
        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withTimestamp, withIssuer));
        return decoder;
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

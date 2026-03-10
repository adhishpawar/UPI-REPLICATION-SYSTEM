package com.upi.psp.security;

import com.upi.psp.exception.InvalidTokenException;
import com.upi.psp.exception.TokenExpiredException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    // Paths that should skip JWT validation
    // Using a Set for O(1) lookup — same HashSet pattern as VPA service
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/setup-mpin",
            "/api/v1/auth/login",
            "/api/v1/auth/validate",
            "/.well-known/jwks.json",
            "/actuator/health"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip filter for public paths
        if (PUBLIC_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract JWT from Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No token — let Security's access denied handler handle it
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);  // Remove 'Bearer ' prefix

        try {
            Claims claims = jwtTokenProvider.validateAndExtractClaims(token);
            String userId  = claims.getSubject();
            String deviceId = claims.get("deviceId", String.class);

            // Build Spring Security authentication object
            // UsernamePasswordAuthenticationToken used as a generic auth holder
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,          // principal (who is this?)
                            null,            // credentials (null — token already validated)
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );

            // Attach request details to authentication
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            // SET the SecurityContext — this is what marks the user as authenticated
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Forward userId in header for downstream services
            // Note: In our system this is done by API Gateway, not PSP Service
            // But useful for internal PSP service endpoint authorization
        } catch (TokenExpiredException | InvalidTokenException ex) {
            log.warn("JWT filter rejected token: {}", ex.getMessage());
            // Don't set SecurityContext — request will be rejected as unauthenticated
        }

        filterChain.doFilter(request, response);  // Always continue the chain
    }
}


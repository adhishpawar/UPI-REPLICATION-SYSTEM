package com.upi.psp.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.upi.psp.domain.dto.*;
import com.upi.psp.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.security.auth.login.AccountLockedException;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Auth", description = "User authentication and token management")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user with device binding")
    public UserRegistrationResponse register(
            @Valid @RequestBody UserRegistrationRequest request) {
        return authService.registerUser(request);
    }

    @PostMapping("/setup-mpin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set MPIN for a registered user")
    public void setupMpin(@Valid @RequestBody MpinSetupRequest request) {
        // Note: 204 No Content — never return MPIN in response
        authService.setupMpin(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Login and receive JWT access token")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) throws AccountLockedException {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke current JWT token")
    public void logout(
            @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        authService.logout(token);
    }

    @GetMapping("/validate")
    @Operation(summary = "Validate JWT — called by API Gateway")
    public TokenValidationResponse validateToken(
            @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        return authService.validateToken(token);
    }

    // Expose RSA public key as JWK Set — used by other services for token verification
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get RSA public key in JWK format")
    public Map<String, Object> getJwks(
            @Autowired RSAPublicKey publicKey) {
        // Build JWK (JSON Web Key) representation of the RSA public key
        // Other services use this to independently validate JWTs
        RSAKey jwk = new RSAKey.Builder(publicKey)
                .keyID("upi-psp-key-1")
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new JWKSet(jwk).toJSONObject();
    }
}

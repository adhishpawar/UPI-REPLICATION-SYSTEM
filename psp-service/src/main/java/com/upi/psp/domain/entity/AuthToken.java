package com.upi.psp.domain.entity;

import com.upi.psp.domain.enums.TokenType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;


import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "token_id", updatable = false, nullable = false)
    private UUID tokenId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;               // Not a FK join — just the UUID value

    @Column(name = "token_hash", nullable = false, length = 512)
    private String tokenHash;          // SHA-256(rawJwt) — not the JWT itself

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 10)
    private TokenType tokenType;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "is_revoked", nullable = false)
    private Boolean isRevoked = false;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public void setTokenType(TokenType tokenType) {
        this.tokenType = tokenType;
    }
}

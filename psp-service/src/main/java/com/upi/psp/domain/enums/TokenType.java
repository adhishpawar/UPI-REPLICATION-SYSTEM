package com.upi.psp.domain.enums;

// ─────────────────────────────────────────────────────────────────────────────
// ROLE: Distinguishes between the two types of tokens the system issues.
//       Stored in auth_tokens.token_type as a VARCHAR.
//
// LOGIC:
//   ACCESS  — short-lived (1 hour). Sent in every API request Authorization header.
//             Validated by API Gateway on every request.
//   REFRESH — long-lived (7 days). Stored securely by the client.
//             Used ONLY to get a new ACCESS token via POST /auth/refresh.
//             Never sent to downstream microservices.
//
// FUTURE: Refresh token flow is Phase 2 enhancement. The enum is defined now
//         so the schema and entity are ready when that feature is added.
// ─────────────────────────────────────────────────────────────────────────────
public enum TokenType {

    /** Short-lived bearer token for API authorization (1 hour TTL). */
    ACCESS,

    /** Long-lived token used only to obtain new access tokens (7 day TTL). */
    REFRESH
}

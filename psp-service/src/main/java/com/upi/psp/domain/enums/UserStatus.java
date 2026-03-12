package com.upi.psp.domain.enums;

public enum UserStatus {
    PENDING_MPIN,  // Registered but MPIN not set yet
    ACTIVE,        // Fully set up, can login
    SUSPENDED,     // Admin-suspended
    LOCKED         // Too many failed attempts — auto-locked

}

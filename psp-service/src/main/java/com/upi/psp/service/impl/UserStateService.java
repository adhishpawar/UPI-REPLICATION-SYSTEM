package com.upi.psp.service.impl;

import com.upi.psp.domain.entity.User;
import com.upi.psp.domain.enums.UserStatus;
import com.upi.psp.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// UserStateService.java — owns failed-attempt counter updates
// Must commit independently so failed count persists even when parent rolls back
@Service
@RequiredArgsConstructor
public class UserStateService {

    private final UserRepository userRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(User user) {
        user.setFailedLoginCount(user.getFailedLoginCount() + 1);
        user.setLastFailedLoginAt(LocalDateTime.now());
        if (user.getFailedLoginCount() >= 10) {
            user.setStatus(UserStatus.LOCKED);
//            log.warn("Account auto-locked: userId={}", user.getUserId());
        }
        userRepo.save(user); // commits in its own txn — survives parent rollback
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailedCount(User user) {
        user.setFailedLoginCount(0);
        user.setLastFailedLoginAt(null);
        userRepo.save(user);
    }
}

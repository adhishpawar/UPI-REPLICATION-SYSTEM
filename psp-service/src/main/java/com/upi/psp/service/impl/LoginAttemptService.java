package com.upi.psp.service.impl;

import com.upi.psp.domain.entity.LoginAttempt;
import com.upi.psp.repo.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {
    private final LoginAttemptRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(String mobile, String deviceId,
                              boolean success, String reason) {

        repo.save(new LoginAttempt(mobile, deviceId, null, success, reason));
    }
}

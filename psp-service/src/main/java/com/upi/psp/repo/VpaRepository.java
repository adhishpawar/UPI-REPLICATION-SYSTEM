package com.upi.psp.repo;

import com.upi.psp.model.VPA;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VpaRepository extends JpaRepository<VPA, String> {
    Optional<VPA> findByVpaAddress(String vpaAddress);

    boolean existsByVpaAddress(String vpaAddress);

    List<VPA> findByUserId(String userId);
}
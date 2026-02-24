package com.upi.vpa_service.repository;

import com.upi.vpa_service.domain.entity.VpaRegistration;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VpaRepository {

    Optional<VpaRegistration> findByVpaAddress(String vpaAddress);

    Optional<VpaRegistration> findByVpaAddressAndIsActive(String vpaAddress);

    List<VpaRegistration> findByUserId(UUID userId);

    boolean existsByVpaAddress(String vpaAddress);

    boolean existsByVpaAddressAndIsActiveTrue(String vpaAddress);

    //custom Query for case insensitive search
    @Query("Select v From VpaRegistration v where Lower(v.vpaAddress) = Lower(:address) AND v.isActive = true")
    Optional<VpaRegistration> findActiveVpaCaseInsensitive(@Param("address")String address);


    VpaRegistration save(VpaRegistration vpa);
}

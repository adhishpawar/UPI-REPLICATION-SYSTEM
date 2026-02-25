package com.upi.vpa_service.config;

//This class enables the @CreatedDate and @LastModifiedDate annotations

import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing   // Tells Spring to populate @CreatedDate, @LastModifiedDate
public class JpaAuditConfig {
}

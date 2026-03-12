package com.upi.psp.config;

// ─────────────────────────────────────────────────────────────────────────────
//
// ROLE: Enables Spring Data JPA's automatic audit field population.
//       Without this config class, the @EnableJpaAuditing on the main
//       application class might not find the AuditorAware bean (needed
//       if you have @CreatedBy and @LastModifiedBy fields).
//
// LOGIC:
//   Spring looks for an AuditorAwareImpl bean to populate @CreatedBy.
//   Since we don't use @CreatedBy in PSP Service (we use userId from JWT),
//   we provide a simple AuditorAware that returns the system name.
//   This prevents Spring from throwing NoSuchBeanDefinitionException.
//
//   @CreatedDate    → Populated on INSERT (first save)
//   @LastModifiedDate → Updated on every UPDATE (save after modification)
//   Both are set automatically by Spring — you never set them manually.
// ─────────────────────────────────────────────────────────────────────────────

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

@Configuration
public class JpaAuditConfig {

    /**
     * AuditorAware: tells Spring who is making the change (for @CreatedBy fields).
     * PSP Service entities don't use @CreatedBy, but Spring requires this bean
     * if @EnableJpaAuditing is active and any entity uses @EntityListeners.
     * We return "system" as a safe default.
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        // In a service with @CreatedBy, you'd extract userId from SecurityContext:
        // return () -> Optional.ofNullable(SecurityContextHolder.getContext()
        //     .getAuthentication()).map(Authentication::getName);
        return () -> Optional.of("psp-service");
    }
}

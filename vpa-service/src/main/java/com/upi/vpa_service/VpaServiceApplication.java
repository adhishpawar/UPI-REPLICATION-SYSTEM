package com.upi.vpa_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
public class VpaServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(VpaServiceApplication.class, args);
	}

}

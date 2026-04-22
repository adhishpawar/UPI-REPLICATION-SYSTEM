package com.upi.payment;

import com.upi.payment.domain.enums.TransactionStatus;
import com.upi.payment.statemachine.TransactionStateMachine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Set;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
public class PaymentOrchestratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentOrchestratorApplication.class, args);
		}

}

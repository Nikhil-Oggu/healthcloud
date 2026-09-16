package com.healthcloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling powers the transactional-outbox relay poller (§Phase 8). The relay bean itself is
// conditional on healthcloud.outbox.relay.enabled, so enabling scheduling here is harmless when it is off.
@SpringBootApplication
@EnableScheduling
public class HealthcloudApplication {

	public static void main(String[] args) {
		SpringApplication.run(HealthcloudApplication.class, args);
	}

}

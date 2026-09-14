package com.finpay.gateway.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background workers: outbox fan-out and webhook delivery, the review-queue
 * drain, ground-truth resolution, auto-clear calibration and the settlement batch.
 *
 * <p>On by default; set {@code finpay.scheduling.enabled=false} to run the application with
 * every scheduler dormant, which is what the test profile does.
 *
 * <p>Why that switch exists: the workers poll on a wall clock, so in tests they fire in the
 * background at arbitrary moments — including while a fixture is truncating the tables they
 * are reading. That produced a genuine {@code TRUNCATE}-vs-reader deadlock and a suite that
 * failed perhaps one run in three. Tests that need a worker now invoke it directly, which is
 * both deterministic and a more honest assertion: it tests the work, not the clock.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "finpay.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

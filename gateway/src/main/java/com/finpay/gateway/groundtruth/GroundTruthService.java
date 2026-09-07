package com.finpay.gateway.groundtruth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class GroundTruthService {

    private final FraudGroundTruthRepository repo;
    private final long resolveAfterSeconds;

    public GroundTruthService(FraudGroundTruthRepository repo,
                              @org.springframework.beans.factory.annotation.Value("${finpay.groundtruth.resolve-after-seconds:60}") long resolveAfterSeconds) {
        this.repo = repo;
        this.resolveAfterSeconds = resolveAfterSeconds;
    }

    /** Record the hidden label at payment time. Called inside the payment transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void plant(UUID paymentId, boolean injectedFraud, String fraudType) {
        if (repo.findByPaymentId(paymentId).isPresent()) return;
        FraudGroundTruth g = new FraudGroundTruth();
        g.setPaymentId(paymentId);
        g.setInjectedFraud(injectedFraud);
        g.setFraudType(injectedFraud ? fraudType : null);
        g.setResolveAt(Instant.now().plus(Duration.ofSeconds(resolveAfterSeconds)));
        repo.save(g);
    }
}

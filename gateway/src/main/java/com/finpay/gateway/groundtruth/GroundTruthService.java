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

    /**
     * Record the hidden label at payment time, inside the payment transaction.
     *
     * @param label authored by the simulation harness — the only author of fraud in this
     *              sandbox. Null for real API / checkout traffic, which is recorded as LIVE and
     *              assumed legitimate: nobody who can reach those endpoints is entitled to say
     *              a transaction is fraud.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void plant(UUID paymentId, GroundTruthLabel label) {
        if (repo.findByPaymentId(paymentId).isPresent()) return;
        FraudGroundTruth g = new FraudGroundTruth();
        g.setPaymentId(paymentId);
        if (label == null) {
            g.setIntendedClass(GroundTruthLabel.IntendedClass.LEGIT);
            g.setLabelSource(FraudGroundTruth.LabelSource.LIVE);
        } else {
            g.setIntendedClass(label.intendedClass());
            g.setLabelSource(FraudGroundTruth.LabelSource.SIMULATION);
            g.setScenarioPattern(label.scenarioPattern());
            g.setDisguiseLevel(label.disguiseLevel());
            if (label.isFraud()) {
                g.setFraudType(label.scenarioPattern());
            }
        }
        g.setResolveAt(Instant.now().plus(Duration.ofSeconds(resolveAfterSeconds)));
        repo.save(g);
    }
}

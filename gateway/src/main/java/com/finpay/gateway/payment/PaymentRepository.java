package com.finpay.gateway.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /**
     * SELECT ... FOR UPDATE. Serializes concurrent capture/refund/void on one payment row.
     * This is the lock that makes double-capture / double-refund impossible.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    Page<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<Payment> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, PaymentState status, Pageable pageable);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Payment> findByStatusOrderByCreatedAtDesc(PaymentState status, Pageable pageable);

    /**
     * List ergonomics for the dashboard's Payments spine (interview sprint Day 11 — the audit's
     * own C.3 item 5: "every list has time range + status + free-text ... server-driven filter
     * + cursor pagination"). Every filter is optional; {@code merchantId} null means "every
     * merchant" (the analyst/admin view) — the controller, not this query, decides who's
     * allowed to pass null. {@code idPrefix} matches the payment id OR the order id, formatted
     * by the caller as a Postgres {@code LIKE} pattern (e.g. {@code "a3f-%"}).
     *
     * <p>Native, not JPQL: PostgreSQL's JDBC driver can't infer a bind parameter's type when
     * its only usage is {@code ? IS NULL} — "could not determine data type of parameter" — so
     * every optional filter needs an explicit cast, which JPQL's {@code cast(x as string)}
     * doesn't give enough control over to fix.
     */
    @Query(value = """
            SELECT p.* FROM payments p
            WHERE (CAST(:merchantId AS uuid) IS NULL OR p.merchant_id = CAST(:merchantId AS uuid))
              AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))
              AND (CAST(:from AS timestamptz) IS NULL OR p.created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR p.created_at <= CAST(:to AS timestamptz))
              AND (CAST(:idPrefix AS text) IS NULL
                   OR CAST(p.id AS text) LIKE CAST(:idPrefix AS text)
                   OR CAST(p.order_id AS text) LIKE CAST(:idPrefix AS text))
            ORDER BY p.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM payments p
            WHERE (CAST(:merchantId AS uuid) IS NULL OR p.merchant_id = CAST(:merchantId AS uuid))
              AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))
              AND (CAST(:from AS timestamptz) IS NULL OR p.created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR p.created_at <= CAST(:to AS timestamptz))
              AND (CAST(:idPrefix AS text) IS NULL
                   OR CAST(p.id AS text) LIKE CAST(:idPrefix AS text)
                   OR CAST(p.order_id AS text) LIKE CAST(:idPrefix AS text))
            """,
            nativeQuery = true)
    Page<Payment> search(@Param("merchantId") UUID merchantId, @Param("status") String status,
                         @Param("from") Instant from, @Param("to") Instant to,
                         @Param("idPrefix") String idPrefix, Pageable pageable);

    /** The payment currently occupying an order, if any: anything not in a dead state. */
    Optional<Payment> findFirstByOrderIdAndStatusNotIn(UUID orderId, java.util.Collection<PaymentState> deadStates);

    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant after);

    long countByCustomerIdAndStatusAndCreatedAtAfter(UUID customerId, PaymentState status, Instant after);

    /**
     * A payment's remaining, un-refunded liability — what settlement or write-off actually has
     * to move, not the original capture amount (a partial refund already reduced what's owed).
     */
    interface SettlementCandidate {
        UUID getPaymentId();
        UUID getMerchantId();
        String getCurrency();
        long getNetAmountMinor();
    }

    /**
     * Captured, unsettled payments whose ground truth confirmed them fraudulent — whatever
     * path captured them, review-based auto-clear or the LOW-bucket auto-capture shortcut
     * (interview sprint Day 7's finding). Written off before they can ever reach a merchant.
     */
    @Query(value = """
            SELECT p.id AS payment_id, p.merchant_id AS merchant_id, p.currency AS currency,
                   p.amount_minor - COALESCE(r.refunded, 0) AS net_amount_minor
            FROM payments p
            JOIN fraud_ground_truth g ON g.payment_id = p.id
            LEFT JOIN (SELECT payment_id, SUM(amount_minor) AS refunded FROM refunds
                       WHERE status = 'SUCCEEDED' GROUP BY payment_id) r ON r.payment_id = p.id
            WHERE p.status = 'CAPTURED' AND p.settled_at IS NULL AND p.written_off_at IS NULL
              AND g.resolved = true AND g.actually_fraudulent = true
              AND p.amount_minor - COALESCE(r.refunded, 0) > 0
            """, nativeQuery = true)
    java.util.List<SettlementCandidate> findConfirmedFraudPendingWriteOff();

    /**
     * Captured, unsettled, not-written-off payments whose ground truth has actually resolved
     * as legitimate — the settlement holdback (Day 9): a payment is eligible for payout only
     * once its dispute window has had a real chance to catch it, not the instant it captures.
     */
    @Query(value = """
            SELECT p.id AS payment_id, p.merchant_id AS merchant_id, p.currency AS currency,
                   p.amount_minor - COALESCE(r.refunded, 0) AS net_amount_minor
            FROM payments p
            JOIN fraud_ground_truth g ON g.payment_id = p.id
            LEFT JOIN (SELECT payment_id, SUM(amount_minor) AS refunded FROM refunds
                       WHERE status = 'SUCCEEDED' GROUP BY payment_id) r ON r.payment_id = p.id
            WHERE p.status = 'CAPTURED' AND p.settled_at IS NULL AND p.written_off_at IS NULL
              AND g.resolved = true AND g.actually_fraudulent = false
              AND p.amount_minor - COALESCE(r.refunded, 0) > 0
            ORDER BY p.merchant_id, p.currency
            """, nativeQuery = true)
    java.util.List<SettlementCandidate> findSettlementEligible();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update Payment p set p.settledAt = :now, p.settlementId = :settlementId where p.id in :ids")
    int markSettled(@Param("ids") java.util.List<UUID> ids, @Param("settlementId") UUID settlementId, @Param("now") Instant now);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update Payment p set p.writtenOffAt = :now where p.id in :ids")
    int markWrittenOff(@Param("ids") java.util.List<UUID> ids, @Param("now") Instant now);
}

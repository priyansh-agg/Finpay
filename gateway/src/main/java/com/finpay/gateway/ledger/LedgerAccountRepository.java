package com.finpay.gateway.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByAccountTypeAndOwnerIdAndCurrency(String accountType, UUID ownerId, String currency);

    List<LedgerAccount> findByAccountType(String accountType);

    @Query("select a from LedgerAccount a where a.accountType = :type and a.ownerId is null and a.currency = :ccy")
    Optional<LedgerAccount> findPlatformAccount(@Param("type") String type, @Param("ccy") String currency);
}

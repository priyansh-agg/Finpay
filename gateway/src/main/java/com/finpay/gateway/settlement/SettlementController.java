package com.finpay.gateway.settlement;

import com.finpay.gateway.auth.CurrentCaller;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/settlements")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    public record SettlementView(UUID id, UUID batchId, long amountMinor, String currency,
                                 String status, Instant createdAt) {
        static SettlementView of(Settlement s) {
            return new SettlementView(s.getId(), s.getBatchId(), s.getAmountMinor(), s.getCurrency(),
                    s.getStatus(), s.getCreatedAt());
        }
    }

    /** A merchant's own payout history. */
    @GetMapping
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
    public List<SettlementView> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        return service.forMerchant(CurrentCaller.requireMerchantId(),
                        PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))))
                .map(SettlementView::of).getContent();
    }

    /** Trigger a settlement run now (normally scheduled). */
    @PostMapping("/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SettlementService.BatchResult run() {
        return service.runBatch();
    }
}

package com.finpay.gateway.payment;

import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.auth.FinPayPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /v1/transactions} — paginated payment history.
 * A merchant sees only their own; a RISK_ANALYST / SUPER_ADMIN sees all.
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionsController {

    private final PaymentRepository payments;

    public TransactionsController(PaymentRepository payments) {
        this.payments = payments;
    }

    public record Page_<T>(List<T> items, int page, int size, long total) {}

    @GetMapping
    public Page_<PaymentView> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "25") int size,
                                   @RequestParam(required = false) String status) {
        FinPayPrincipal caller = CurrentCaller.get();
        boolean analyst = caller.role().equals("RISK_ANALYST") || caller.role().equals("SUPER_ADMIN");
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        PaymentState st = status == null ? null : PaymentState.valueOf(status.toUpperCase());

        Page<Payment> result;
        if (analyst) {
            result = st == null ? payments.findAllByOrderByCreatedAtDesc(pr)
                    : payments.findByStatusOrderByCreatedAtDesc(st, pr);
        } else {
            var merchantId = CurrentCaller.requireMerchantId();
            result = st == null ? payments.findByMerchantIdOrderByCreatedAtDesc(merchantId, pr)
                    : payments.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, st, pr);
        }
        return new Page_<>(result.map(PaymentView::of).getContent(), pr.getPageNumber(), pr.getPageSize(),
                result.getTotalElements());
    }
}

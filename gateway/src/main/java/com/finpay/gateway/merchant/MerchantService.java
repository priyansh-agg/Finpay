package com.finpay.gateway.merchant;

import com.finpay.gateway.auth.ApiKeyService;
import com.finpay.gateway.common.audit.AuditService;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.identity.MerchantRepository;
import com.finpay.gateway.identity.UserRepository;
import com.finpay.gateway.identity.Merchant;
import com.finpay.gateway.identity.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final UserRepository users;
    private final ApiKeyService apiKeyService;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public MerchantService(MerchantRepository merchants, UserRepository users, ApiKeyService apiKeyService,
                           PasswordEncoder encoder, AuditService audit) {
        this.merchants = merchants;
        this.users = users;
        this.apiKeyService = apiKeyService;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record OnboardResult(UUID merchantId, String name, ApiKeyService.IssuedKey apiKey,
                                UUID adminUserId, String adminPassword) {}

    /** Onboards a merchant: creates the merchant row, a MERCHANT_ADMIN dashboard user, and a live API key. */
    @Transactional
    public OnboardResult onboard(String name, String email, String adminPassword) {
        merchants.findByEmail(email).ifPresent(m -> { throw ApiException.conflict("Merchant email already registered"); });

        Merchant merchant = merchants.save(new Merchant(name, email));

        User adminUser = new User();
        adminUser.setMerchantId(merchant.getId());
        adminUser.setEmail(email);
        adminUser.setPasswordHash(encoder.encode(adminPassword));
        adminUser.setRole("MERCHANT_ADMIN");
        users.save(adminUser);

        ApiKeyService.IssuedKey key = apiKeyService.issue(merchant.getId(), "MERCHANT", "default");

        audit.record("USER", "system", "MERCHANT_ONBOARDED", "MERCHANT", merchant.getId().toString(),
                null, Map.of("name", name, "email", email));

        return new OnboardResult(merchant.getId(), name, key, adminUser.getId(), adminPassword);
    }

    @Transactional
    public UUID createAnalyst(String email, String password) {
        users.findByEmail(email).ifPresent(u -> { throw ApiException.conflict("Email already in use"); });
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setRole("RISK_ANALYST");
        users.save(u);
        return u.getId();
    }
}

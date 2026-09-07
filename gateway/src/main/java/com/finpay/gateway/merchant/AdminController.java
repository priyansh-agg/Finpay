package com.finpay.gateway.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** SUPER_ADMIN-only provisioning endpoints. */
@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    private final MerchantService merchantService;

    public AdminController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    public record OnboardMerchantRequest(
            @NotBlank String name,
            @Email String email,
            @Size(min = 8, message = "admin password must be >= 8 chars") String adminPassword) {}

    @PostMapping("/merchants")
    public MerchantService.OnboardResult onboard(@RequestBody @Valid OnboardMerchantRequest req) {
        return merchantService.onboard(req.name(), req.email(), req.adminPassword());
    }

    public record CreateAnalystRequest(@Email String email, @Size(min = 8) String password) {}

    public record CreateAnalystResponse(UUID userId, String email) {}

    @PostMapping("/analysts")
    public CreateAnalystResponse createAnalyst(@RequestBody @Valid CreateAnalystRequest req) {
        return new CreateAnalystResponse(merchantService.createAnalyst(req.email(), req.password()), req.email());
    }
}

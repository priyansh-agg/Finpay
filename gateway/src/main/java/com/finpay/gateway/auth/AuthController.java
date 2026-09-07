package com.finpay.gateway.auth;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.identity.UserRepository;
import com.finpay.gateway.identity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record LoginRequest(@Email String email, @NotBlank String password) {}

    public record LoginResponse(String token, String role, String email) {}

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @jakarta.validation.Valid LoginRequest req) {
        User user = users.findByEmail(req.email())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Bad credentials"));
        return new LoginResponse(jwt.issue(user), user.getRole(), user.getEmail());
    }

    @GetMapping("/me")
    public FinPayPrincipal me() {
        return CurrentCaller.get();
    }
}

package com.wtc.identity.api;

import com.wtc.identity.application.AuthService;
import com.wtc.identity.application.LoginResult;
import com.wtc.shared.web.ClientContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentification (routes publiques du contrat, sauf la déconnexion). */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;
    private final ClientContext client;

    AuthController(AuthService auth, ClientContext client) {
        this.auth = auth;
        this.client = client;
    }

    @PostMapping("/register")
    ResponseEntity<UserSummaryResponse> register(@Valid @RequestBody AuthRequests.Register body) {
        var view =
                auth.register(
                        new AuthService.RegisterCommand(
                                body.email(),
                                body.password(),
                                body.firstName(),
                                body.lastName(),
                                body.phone(),
                                body.countryCode()),
                        Requests.meta(client));
        return ResponseEntity.status(HttpStatus.CREATED).body(UserSummaryResponse.from(view));
    }

    @PostMapping("/login")
    ResponseEntity<Object> login(@Valid @RequestBody AuthRequests.Login body) {
        LoginResult result = auth.login(body.email(), body.password(), Requests.meta(client));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        result.isMfaRequired()
                                ? new MfaChallengeResponse(true, result.mfaToken())
                                : TokenResponse.from(result.session()));
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<TokenResponse> verifyMfa(@Valid @RequestBody AuthRequests.MfaVerify body) {
        return noStore(
                TokenResponse.from(
                        auth.verifyMfa(body.mfaToken(), body.code(), Requests.meta(client))));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody AuthRequests.Refresh body) {
        return noStore(
                TokenResponse.from(auth.refresh(body.refreshToken(), Requests.meta(client))));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody AuthRequests.Refresh body) {
        auth.logout(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody AuthRequests.PasswordResetRequest body) {
        auth.requestPasswordReset(body.email(), Requests.meta(client));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody AuthRequests.PasswordResetConfirm body) {
        auth.confirmPasswordReset(body.token(), body.newPassword(), Requests.meta(client));
        return ResponseEntity.noContent().build();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}

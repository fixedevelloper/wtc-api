package com.wtc.identity.api;

import com.wtc.customer.application.CustomerProfile;
import com.wtc.identity.application.AccountService;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Mon compte. L'identité vient toujours du jeton, jamais d'un paramètre : on ne peut pas viser le
 * compte d'un autre.
 */
@RestController
@RequestMapping("/api/me")
class MeController {

    private final AccountService accounts;

    MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    record MeResponse(UserSummaryResponse user, CustomerProfile customer) {}

    record MfaEnrollmentResponse(String secret, String otpauthUri) {}

    @GetMapping
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        AccountService.Me me = accounts.me(Requests.userId(jwt));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        new MeResponse(
                                UserSummaryResponse.from(me.user()), me.customer().orElse(null)));
    }

    @PutMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    UserSummaryResponse uploadPhoto(
            @AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file)
            throws IOException {
        return UserSummaryResponse.from(
                accounts.uploadPhoto(Requests.userId(jwt), file.getBytes()));
    }

    @PutMapping("/password")
    ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AuthRequests.ChangePassword body) {
        accounts.changePassword(
                Requests.userId(jwt),
                Requests.sessionId(jwt),
                body.currentPassword(),
                body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/enrollment")
    ResponseEntity<MfaEnrollmentResponse> startMfaEnrollment(@AuthenticationPrincipal Jwt jwt) {
        AccountService.MfaEnrollment enrollment = accounts.startMfaEnrollment(Requests.userId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store")
                .body(new MfaEnrollmentResponse(enrollment.secret(), enrollment.otpauthUri()));
    }

    @PostMapping("/mfa/confirmation")
    ResponseEntity<Void> confirmMfaEnrollment(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthRequests.MfaCode body) {
        accounts.confirmMfaEnrollment(Requests.userId(jwt), body.code());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/mfa")
    ResponseEntity<Void> disableMfa(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthRequests.MfaCode body) {
        accounts.disableMfa(Requests.userId(jwt), body.code());
        return ResponseEntity.noContent().build();
    }
}

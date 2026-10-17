package com.wtc.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Corps des requêtes d'authentification (mêmes contraintes que le contrat OpenAPI). */
final class AuthRequests {

    private AuthRequests() {}

    private static final String PHONE = "^\\+[1-9][0-9]{6,14}$";

    record Register(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Pattern(regexp = PHONE) String phone,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {}

    record Login(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {}

    record MfaVerify(
            @NotBlank @Size(max = 200) String mfaToken,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {}

    record Refresh(@NotBlank @Size(max = 200) String refreshToken) {}

    record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

    record PasswordResetConfirm(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    record ChangePassword(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    record MfaCode(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {}
}

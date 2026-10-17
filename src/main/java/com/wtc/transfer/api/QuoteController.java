package com.wtc.transfer.api;

import com.wtc.shared.security.JwtSubject;
import com.wtc.transfer.application.QuoteService;
import com.wtc.transfer.application.QuoteService.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Devis de transfert (client connecté et back-office). */
@RestController
@RequestMapping("/api")
class QuoteController {

    private static final String DECIMAL = "^\\d{1,15}(\\.\\d{1,4})?$";

    private final QuoteService quotes;

    QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    record QuoteRequest(
            @NotNull UUID destinationId, @NotNull @Pattern(regexp = DECIMAL) String sourceAmount) {}

    record AdminQuoteRequest(
            @NotNull UUID destinationId,
            @NotNull @Pattern(regexp = DECIMAL) String sourceAmount,
            @NotNull UUID customerId) {}

    record Money(String amount, String currency) {
        static Money of(BigDecimal amount, String currency) {
            return new Money(amount.toPlainString(), currency);
        }
    }

    record QuoteResponse(
            Money sourceAmount,
            Money fee,
            BigDecimal feePercent,
            Money totalDebit,
            Money destinationAmount,
            BigDecimal fxRate,
            Money availableBalance,
            boolean sufficientFunds) {

        static QuoteResponse from(Quote q) {
            var p = q.pricing();
            return new QuoteResponse(
                    Money.of(p.sourceAmount(), p.sourceCurrency()),
                    Money.of(p.fee(), p.sourceCurrency()),
                    p.feePercent().stripTrailingZeros(),
                    Money.of(p.totalDebit(), p.sourceCurrency()),
                    Money.of(p.destinationAmount(), p.destinationCurrency()),
                    p.fxRate().stripTrailingZeros(),
                    Money.of(q.availableBalance(), p.sourceCurrency()),
                    q.sufficientFunds());
        }
    }

    @PostMapping("/me/transfers/quote")
    ResponseEntity<QuoteResponse> quote(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest body) {
        UUID customerId = customerId(jwt);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        QuoteResponse.from(
                                quotes.forCustomer(
                                        customerId,
                                        body.destinationId(),
                                        new BigDecimal(body.sourceAmount()))));
    }

    @PostMapping("/admin/transfers/quote")
    ResponseEntity<QuoteResponse> adminQuote(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AdminQuoteRequest body) {
        JwtSubject.userId(jwt); // jeton valide exigé ; la permission est vérifiée par le service
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        QuoteResponse.from(
                                quotes.forCustomerByStaff(
                                        body.customerId(),
                                        body.destinationId(),
                                        new BigDecimal(body.sourceAmount()))));
    }

    private static UUID customerId(Jwt jwt) {
        String id = jwt.getClaimAsString("customer_id");
        if (id == null) {
            throw new com.wtc.shared.error.DomainException(
                    com.wtc.shared.error.ErrorCode.ACCESS_DENIED,
                    "Ce compte n'a pas de fiche client.");
        }
        return UUID.fromString(id);
    }
}

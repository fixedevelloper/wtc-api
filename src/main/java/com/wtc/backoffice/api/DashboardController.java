package com.wtc.backoffice.api;

import com.wtc.backoffice.application.DashboardService;
import com.wtc.backoffice.application.port.DashboardRepository.Month;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Chiffres du tableau de bord du back-office (permission {@code TRANSFER_READ}). */
@RestController
@RequestMapping("/api/admin/dashboard")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** Montants en chaînes décimales (jamais de flottants), dans {@code currency}. */
    record MonthResponse(
            String month,
            String transferVolume,
            long transferCount,
            String fees,
            String topUpVolume,
            long topUpCount,
            long newCustomers) {}

    record DashboardResponse(
            String currency,
            Instant generatedAt,
            List<MonthResponse> months,
            long customers,
            long activeCustomers,
            String walletBalance,
            long pendingTopUps,
            Map<String, Long> transfersByStatus) {}

    @GetMapping
    DashboardResponse get(
            @RequestParam(defaultValue = "XAF") @Pattern(regexp = "[A-Z]{3}") String currency) {
        var d = dashboard.dashboard(currency);
        return new DashboardResponse(
                d.currency(),
                d.generatedAt(),
                d.months().stream().map(DashboardController::month).toList(),
                d.totals().customers(),
                d.totals().activeCustomers(),
                d.totals().walletBalance().toPlainString(),
                d.totals().pendingTopUps(),
                d.totals().transfersByStatus());
    }

    private static MonthResponse month(Month m) {
        return new MonthResponse(
                m.month(),
                m.transferVolume().toPlainString(),
                m.transferCount(),
                m.fees().toPlainString(),
                m.topUpVolume().toPlainString(),
                m.topUpCount(),
                m.newCustomers());
    }
}

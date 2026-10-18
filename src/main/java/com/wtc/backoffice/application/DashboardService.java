package com.wtc.backoffice.application;

import com.wtc.backoffice.application.port.DashboardRepository;
import com.wtc.backoffice.application.port.DashboardRepository.Month;
import com.wtc.backoffice.application.port.DashboardRepository.Totals;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chiffres du tableau de bord. Les montants sont additionnés dans une seule devise à la fois (pas
 * de conversion implicite) ; la permission est celle de la consultation des transferts.
 */
@Service
public class DashboardService {

    public record Dashboard(
            String currency, Instant generatedAt, List<Month> months, Totals totals) {
        public Dashboard {
            months = List.copyOf(months);
        }
    }

    private final DashboardRepository repository;
    private final Clock clock;

    DashboardService(DashboardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('TRANSFER_READ')")
    @Transactional(readOnly = true)
    public Dashboard dashboard(String currency) {
        Instant now = clock.instant();
        return new Dashboard(
                currency, now, repository.lastMonths(currency, now), repository.totals(currency));
    }
}

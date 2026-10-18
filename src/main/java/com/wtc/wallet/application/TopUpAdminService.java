package com.wtc.wallet.application;

import com.wtc.wallet.application.port.TopUpRepository;
import com.wtc.wallet.application.port.TopUpRepository.Filter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultation des recharges par le personnel (permission {@code TOPUP_REVIEW}). */
@Service
public class TopUpAdminService {

    private final TopUpRepository topUps;

    TopUpAdminService(TopUpRepository topUps) {
        this.topUps = topUps;
    }

    @PreAuthorize("hasAuthority('TOPUP_REVIEW')")
    @Transactional(readOnly = true)
    public Page<TopUpView> search(Filter filter, int page, int size, boolean newestFirst) {
        return topUps.search(filter, page, size, newestFirst);
    }
}

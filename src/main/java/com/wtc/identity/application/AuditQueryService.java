package com.wtc.identity.application;

import com.wtc.identity.application.port.AuditLogRepository;
import com.wtc.identity.application.port.LoginAuditRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultation des journaux (permission {@code AUDIT_READ}). Lecture seule : les tables sont
 * immuables en base.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLog;
    private final LoginAuditRepository loginAudit;

    AuditQueryService(AuditLogRepository auditLog, LoginAuditRepository loginAudit) {
        this.auditLog = auditLog;
        this.loginAudit = loginAudit;
    }

    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Transactional(readOnly = true)
    public AuditLogRepository.EntryPage auditLogs(
            AuditLogRepository.Filter filter, int page, int size, boolean newestFirst) {
        return auditLog.search(filter, page, size, newestFirst);
    }

    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Transactional(readOnly = true)
    public LoginAuditRepository.EntryPage loginAudits(int page, int size, boolean newestFirst) {
        return loginAudit.list(page, size, newestFirst);
    }
}

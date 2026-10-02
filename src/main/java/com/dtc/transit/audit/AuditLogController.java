package com.dtc.transit.audit;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The searchable audit trail.
 *
 * <p>Keyset-paginated: the log grows forever and is read newest-first, so a deep offset would make the database
 * walk everything more recent than the page being asked for. The cursor is a timestamp and an id together,
 * because two records can share a timestamp.
 *
 * <p>Payloads are redacted for anyone who is not an administrator. A before-and-after pair is a whole entity
 * state, and a crew record carries a name, an employee code and a licence number.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * @param beforeAt and beforeId come from the previous page's {@code nextBeforeAt} and {@code nextBeforeId}
     */
    @GetMapping
    public AuditLogService.Page list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Instant beforeAt,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {

        return auditLogService.list(
                actor,
                action,
                entityType,
                entityId,
                from,
                to,
                beforeAt,
                beforeId,
                size == null ? AuditLogService.DEFAULT_PAGE_SIZE : size);
    }
}

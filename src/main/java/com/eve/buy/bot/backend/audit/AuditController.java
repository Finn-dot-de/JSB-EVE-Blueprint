package com.eve.buy.bot.backend.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lesezugriff auf das Protokoll für den Admin-Bereich. */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN')")
@RequiredArgsConstructor
public class AuditController {

    /** Obergrenze pro Abfrage, damit die Oberfläche nicht versehentlich alles zieht. */
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEntryRepository repository;

    /**
     * Ein Seitenausschnitt des Protokolls.
     *
     * @param entries die Einträge dieser Seite
     * @param total   Gesamtzahl passender Einträge
     */
    public record AuditPage(List<AuditEntry> entries, long total) {}

    /**
     * Liest Protokolleinträge, neueste zuerst.
     *
     * @param category    optionale Einschränkung auf eine Kategorie
     * @param minSeverity optionale Mindestschwere
     * @param limit       gewünschte Anzahl, höchstens {@value #MAX_PAGE_SIZE}
     * @return die passenden Einträge samt Gesamtzahl
     */
    @GetMapping
    public ResponseEntity<AuditPage> list(@RequestParam(required = false) AuditCategory category,
                                          @RequestParam(required = false) AuditSeverity minSeverity,
                                          @RequestParam(defaultValue = "50") int limit) {
        Page<AuditEntry> page = repository.search(category, minSeverity,
                PageRequest.of(0, Math.clamp(limit, 1, MAX_PAGE_SIZE)));
        return ResponseEntity.ok(new AuditPage(page.getContent(), page.getTotalElements()));
    }
}

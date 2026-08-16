package com.eve.buy.bot.backend.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Schreibt veröffentlichte {@link AuditEvent}s in die Datenbank.
 *
 * <p>Der Listener läuft asynchron auf dem Audit-Executor, damit weder eine langsame noch
 * eine ausgefallene Datenbank den eigentlichen Aufruf blockiert. Scheitert das Schreiben,
 * bleibt die Meldung wenigstens im Anwendungslog.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditEntryRepository repository;

    /**
     * Nimmt ein Ereignis entgegen und legt es als Protokolleintrag ab.
     *
     * @param event das zu speichernde Ereignis
     */
    @Async("auditExecutor")
    @EventListener
    public void onAuditEvent(AuditEvent event) {
        try {
            repository.save(toEntry(event));
        } catch (Exception e) {
            log.error("Protokolleintrag konnte nicht gespeichert werden [{}] {}: {}",
                    event.category(), event.message(), e.getMessage());
        }
    }

    /**
     * Überträgt das Ereignis in die Entität.
     *
     * @param event das Ereignis
     * @return der zu speichernde Eintrag
     */
    private AuditEntry toEntry(AuditEvent event) {
        AuditEntry entry = new AuditEntry();
        entry.setOccurredAt(event.occurredAt());
        entry.setCategory(event.category());
        entry.setSeverity(event.severity());
        entry.setMessage(event.message());
        entry.setRequestId(event.requestId());
        entry.setActorCharacterId(event.actorCharacterId());
        entry.setActorName(event.actorName());
        entry.setClientIp(event.clientIp());
        entry.setUserAgent(event.userAgent());
        entry.setHttpMethod(event.httpMethod());
        entry.setPath(event.path());
        entry.setStatusCode(event.statusCode());
        entry.setDurationMs(event.durationMs());
        entry.setDetails(event.details());
        return entry;
    }
}

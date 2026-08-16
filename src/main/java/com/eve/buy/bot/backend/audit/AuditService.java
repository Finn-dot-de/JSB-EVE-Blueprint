package com.eve.buy.bot.backend.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Nimmt Protokollmeldungen entgegen und veröffentlicht sie als Anwendungsereignis.
 *
 * <p>Aufrufer müssen weder IP-Adresse noch Auslöser kennen: beides wird aus dem
 * {@link AuditContext} des laufenden Requests ergänzt. Geschrieben wird erst im
 * {@link AuditEventListener}, damit die Datenbank den Aufruf nicht ausbremst.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    /** Anzeigename für nicht angemeldete Spieler. */
    public static final String ANONYMOUS = "anonym";

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Schreibt eine Meldung ohne HTTP-Bezug, etwa aus einem Hintergrundlauf.
     *
     * @param category fachliche Einordnung
     * @param severity Schweregrad
     * @param message  kurze Zusammenfassung
     * @param details  Zusatzangaben oder {@code null}
     */
    public void record(AuditCategory category, AuditSeverity severity, String message, String details) {
        record(category, severity, message, details, null, null);
    }

    /**
     * Schreibt eine Meldung mit HTTP-Bezug.
     *
     * @param category   fachliche Einordnung
     * @param severity   Schweregrad
     * @param message    kurze Zusammenfassung
     * @param details    Zusatzangaben oder {@code null}
     * @param statusCode HTTP-Status der Antwort oder {@code null}
     * @param durationMs Bearbeitungsdauer in Millisekunden oder {@code null}
     */
    public void record(AuditCategory category,
                       AuditSeverity severity,
                       String message,
                       String details,
                       Integer statusCode,
                       Long durationMs) {
        AuditContext.Data context = AuditContext.current();

        eventPublisher.publishEvent(new AuditEvent(
                Instant.now(),
                category,
                severity,
                trim(message, 500),
                context == null ? null : context.getRequestId(),
                context == null ? null : context.getActorCharacterId(),
                actorName(context),
                context == null ? null : context.getClientIp(),
                context == null ? null : trim(context.getUserAgent(), 300),
                context == null ? null : context.getHttpMethod(),
                context == null ? null : trim(context.getPath(), 300),
                statusCode,
                durationMs,
                details
        ));
    }

    /**
     * Bestimmt den anzuzeigenden Auslöser.
     *
     * @param context Kontext des laufenden Aufrufs oder {@code null}
     * @return Charaktername, {@code anonym} oder {@code System} bei Hintergrundläufen
     */
    private String actorName(AuditContext.Data context) {
        if (context == null) {
            return "System";
        }
        return context.getActorName() != null ? context.getActorName() : ANONYMOUS;
    }

    /**
     * Kürzt einen Text auf die Spaltenbreite der Datenbank.
     *
     * @param value der Text, darf {@code null} sein
     * @param max   erlaubte Länge
     * @return der gekürzte Text
     */
    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}

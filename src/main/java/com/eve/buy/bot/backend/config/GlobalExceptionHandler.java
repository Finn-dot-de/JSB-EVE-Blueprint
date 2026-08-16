package com.eve.buy.bot.backend.config;

import com.eve.buy.bot.backend.audit.AuditCategory;
import com.eve.buy.bot.backend.audit.AuditContext;
import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.audit.AuditSeverity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Wandelt Ausnahmen in eine einheitliche Fehlerantwort und schreibt sie ins Protokoll.
 *
 * <p>Der Buybot wird überwiegend von nicht angemeldeten Spielern genutzt. Damit ein
 * gemeldeter Fehler trotzdem auffindbar ist, enthält jede Antwort die Aufruf-ID; unter
 * derselben ID steht der Vorgang mit IP-Adresse und Zeitstempel im Protokoll.
 *
 * <p>Interne Einzelheiten wie Stacktraces verlassen den Server nicht.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditService auditService;

    /**
     * Fehlerantwort an den Client.
     *
     * @param timestamp Zeitpunkt des Fehlers
     * @param status    HTTP-Status
     * @param error     kurze, unverfängliche Beschreibung
     * @param requestId Aufruf-ID, die der Nutzer bei einer Meldung angeben kann
     */
    public record ErrorResponse(Instant timestamp, int status, String error, String requestId) {}

    /**
     * Behandelt bewusst ausgelöste Fehler mit eigenem HTTP-Status, etwa "nicht gefunden".
     *
     * @param exception die ausgelöste Ausnahme
     * @param request   der betroffene Aufruf
     * @return die Fehlerantwort mit dem gewünschten Status
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception,
                                                              HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String reason = exception.getReason() != null ? exception.getReason() : status.getReasonPhrase();

        auditService.record(AuditCategory.REQUEST, AuditSeverity.WARN,
                "%s %s abgelehnt: %s".formatted(request.getMethod(), request.getRequestURI(), reason),
                exception.getClass().getSimpleName());

        return build(status, reason);
    }

    /**
     * Behandelt fehlende Berechtigungen.
     *
     * @param exception die ausgelöste Ausnahme
     * @param request   der betroffene Aufruf
     * @return die Fehlerantwort mit Status 403
     */
    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception exception, HttpServletRequest request) {
        auditService.record(AuditCategory.SECURITY, AuditSeverity.WARN,
                "Zugriff verweigert auf %s %s".formatted(request.getMethod(), request.getRequestURI()),
                exception.getMessage());

        return build(HttpStatus.FORBIDDEN, "Zugriff verweigert.");
    }

    /**
     * Behandelt fehlerhafte Eingaben.
     *
     * @param exception die ausgelöste Ausnahme
     * @param request   der betroffene Aufruf
     * @return die Fehlerantwort mit Status 400
     */
    @ExceptionHandler({IllegalArgumentException.class, NumberFormatException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
        auditService.record(AuditCategory.REQUEST, AuditSeverity.WARN,
                "Ungültige Anfrage an %s %s".formatted(request.getMethod(), request.getRequestURI()),
                describe(exception));

        return build(HttpStatus.BAD_REQUEST, "Die Anfrage konnte nicht verarbeitet werden.");
    }

    /**
     * Auffangnetz für alles Unerwartete.
     *
     * @param exception die ausgelöste Ausnahme
     * @param request   der betroffene Aufruf
     * @return die Fehlerantwort mit Status 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = AuditContext.currentRequestId();
        log.error("Unbehandelter Fehler [{}] bei {} {}", requestId, request.getMethod(), request.getRequestURI(), exception);

        auditService.record(AuditCategory.ERROR, AuditSeverity.ERROR,
                "Unbehandelter Fehler bei %s %s".formatted(request.getMethod(), request.getRequestURI()),
                describe(exception));

        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unerwarteter Fehler. Bitte die Fehler-ID melden.");
    }

    /**
     * Baut die Antwort samt Aufruf-ID.
     *
     * @param status der HTTP-Status
     * @param error  die Beschreibung für den Client
     * @return die fertige Antwort
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), error, AuditContext.currentRequestId()));
    }

    /**
     * Fasst eine Ausnahme für das Protokoll zusammen, ohne den vollen Stacktrace zu speichern.
     *
     * @param exception die Ausnahme
     * @return Typ, Meldung und die erste Codestelle innerhalb der Anwendung
     */
    private String describe(Exception exception) {
        StringBuilder details = new StringBuilder(exception.getClass().getName());
        if (exception.getMessage() != null) {
            details.append(": ").append(exception.getMessage());
        }
        for (StackTraceElement element : exception.getStackTrace()) {
            if (element.getClassName().startsWith("com.eve.buy.bot")) {
                details.append(" @ ").append(element);
                break;
            }
        }
        return details.toString();
    }
}

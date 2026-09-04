package com.eve.own.auth.backend.common;

import com.eve.own.auth.backend.domain.fleet.service.FleetPingAbgeschaltetException;
import com.eve.own.auth.backend.domain.fleet.service.FleetPingWartezeitException;
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Uebersetzt Ausnahmen aus der Fachschicht in HTTP-Antworten.
 *
 * <p>Damit muss kein Controller mehr eigene try-catch-Bloecke tragen, um daraus
 * ein {@code ResponseEntity<?>} mit handgebauter {@code Map.of("message", ...)}
 * zu formen. Die Controller geben ihren fachlichen Typ zurueck, Fehler landen hier.</p>
 *
 * <p>Die Reihenfolge im Quelltext spielt keine Rolle: Spring waehlt immer den
 * Handler mit dem speziellsten passenden Ausnahmetyp. Deshalb faengt der
 * Auffangbehandler ganz unten die Rechtepruefung nicht mit weg.</p>
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String UNEXPECTED_MESSAGE =
            "Unerwarteter Fehler bei der Verarbeitung der Anfrage.";

    /** Ungueltige Eingabe des Aufrufers - etwa ein Charakter, der ihm nicht gehoert. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException e) {
        log.debug("Ungueltige Anfrage: {}", e.getMessage());
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    /**
     * Fehlende Berechtigung.
     *
     * <p>Muss ausdruecklich behandelt werden: sonst zoege der Auffangbehandler
     * weiter unten aus einem 403 eine 500 - die Anwendung wuerde eine korrekt
     * abgewiesene Anfrage als eigenen Fehler ausweisen.</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        log.debug("Zugriff verweigert: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("Fuer diese Aktion fehlen dir die noetigen Rechte."));
    }

    /** ESI verweigert den Zugriff - dem Token-Charakter fehlt eine Ingame-Rolle. */
    @ExceptionHandler(EsiAccessDeniedException.class)
    public ResponseEntity<ApiError> handleEsiAccessDenied(EsiAccessDeniedException e) {
        log.info("ESI verweigert den Zugriff: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(e.getMessage()));
    }

    /**
     * Eine Funktion ist nicht eingerichtet - derzeit die Flotten-Pings ohne
     * Discord-Kanal.
     *
     * <p>Ausdruecklich <b>503</b> und nicht die 500, die der Auffangbehandler
     * daraus machte: Die Anwendung ist in Ordnung, es fehlt eine
     * Umgebungsvariable. Als 500 liefe der Fehlerzaehler mit, im Protokoll
     * stuende ein Stacktrace, und wer ihn untersucht, suchte den Fehler im Code
     * statt in der Konfiguration. Die Meldung geht unveraendert nach aussen,
     * weil sie genau sagt, welche Variable fehlt.</p>
     */
    @ExceptionHandler(FleetPingAbgeschaltetException.class)
    public ResponseEntity<ApiError> handleAbgeschaltet(FleetPingAbgeschaltetException e) {
        log.info("Abgeschaltete Funktion angefragt: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(e.getMessage()));
    }

    /**
     * Eine Bremse hat gegriffen - derzeit die Wartezeit zwischen zwei
     * Flotten-Pings.
     *
     * <p><b>429</b> und nicht die 500, die eine {@code IllegalStateException}
     * ergaebe: Die Bremse hat funktioniert, wie sie soll. Als Serverfehler
     * gemeldet liefe der Fehlerzaehler mit, und der Aufrufer wuesste nicht, ob
     * seine Aktion nun ausgefuehrt wurde oder nicht - bei einem Ping die
     * wichtigste aller Fragen.</p>
     */
    @ExceptionHandler(FleetPingWartezeitException.class)
    public ResponseEntity<ApiError> handleWartezeit(FleetPingWartezeitException e) {
        log.debug("Wartezeit greift: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiError(e.getMessage()));
    }

    /**
     * Ein Zustand, mit dem der Aufrufer nichts anfangen kann - fehlende
     * ESI-Daten etwa, oder ein Sicherheitskontext ohne Charakter.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException e) {
        log.warn("Unerwarteter Zustand: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(e.getMessage()));
    }

    /**
     * Alles Uebrige.
     *
     * <p>Der Stacktrace geht ins Protokoll, nach aussen geht ein neutraler Text:
     * eine Ausnahmemeldung kann Interna preisgeben, die niemanden ausserhalb
     * etwas angehen.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unbehandelte Ausnahme", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(UNEXPECTED_MESSAGE));
    }
}

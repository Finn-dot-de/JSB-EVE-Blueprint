package com.eve.own.auth.backend.esi;

/**
 * ESI verweigert den Zugriff, weil dem Token-Charakter etwas fehlt.
 *
 * <p>Kein Fehler der Anwendung, sondern eine Aussage ueber die Rechtelage in
 * EVE - und damit etwas, das der Aufrufer als Klartext lesen koennen sollte.</p>
 *
 * <p>Die Ursache gehoert mitgereicht. Ohne sie stand im Protokoll nur der Satz,
 * den diese Anwendung sich selbst ausgedacht hat; die Antwort von CCP, der
 * Statuscode und die Aufrufstelle waren verloren - und damit die Antwort auf
 * die Frage, <em>warum</em> ESI abgelehnt hat.</p>
 */
public class EsiAccessDeniedException extends RuntimeException {

    public EsiAccessDeniedException(String message) {
        super(message);
    }

    public EsiAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}

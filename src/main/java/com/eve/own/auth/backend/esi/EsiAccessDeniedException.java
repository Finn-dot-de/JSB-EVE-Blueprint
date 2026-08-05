package com.eve.own.auth.backend.esi;

/**
 * ESI verweigert den Zugriff, weil dem Token-Charakter eine Ingame-Rolle fehlt.
 *
 * <p>Kein Fehler der Anwendung, sondern eine Aussage ueber die Rechtelage in
 * EVE - und damit etwas, das der Aufrufer als Klartext lesen koennen sollte.</p>
 */
public class EsiAccessDeniedException extends RuntimeException {

    public EsiAccessDeniedException(String message) {
        super(message);
    }
}

package com.eve.own.auth.backend.domain.fleet.service;

/**
 * Der zweite Ping kam zu schnell nach dem ersten.
 *
 * <p>Wie {@link FleetPingAbgeschaltetException} ein eigener Typ, und aus
 * demselben Grund: Als {@link IllegalStateException} macht der
 * {@code ApiExceptionHandler} daraus eine <b>500</b> - also "die Anwendung ist
 * kaputt" fuer eine Bremse, die genau so funktioniert hat, wie sie soll. Der
 * Fehlerzaehler liefe mit, und im Protokoll stuende eine Warnung ueber einen
 * Vorgang, der voellig in Ordnung ist.</p>
 *
 * <p>Daraus wird eine <b>429</b>. Das ist nicht nur die ehrlichere Zahl,
 * sondern auch die brauchbarere: Das Frontend kann daran einen Wartehinweis
 * festmachen, statt einen Serverfehler anzuzeigen und den FC im Ungewissen zu
 * lassen, ob sein Ping nun rausging oder nicht.</p>
 */
public class FleetPingWartezeitException extends RuntimeException {

    public FleetPingWartezeitException(String message) {
        super(message);
    }
}

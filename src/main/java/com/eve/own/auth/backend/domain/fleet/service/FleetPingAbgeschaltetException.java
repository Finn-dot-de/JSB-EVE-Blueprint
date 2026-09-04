package com.eve.own.auth.backend.domain.fleet.service;

/**
 * Die Flotten-Pings sind nicht eingerichtet.
 *
 * <p>Ein eigener Typ und keine {@link IllegalStateException}, obwohl die
 * naeherliegt. Der Unterschied ist die Antwort nach aussen: {@code ApiExceptionHandler}
 * macht aus einer {@code IllegalStateException} eine <b>500</b> samt
 * ERROR-Protokollzeile - also die Meldung "die Anwendung ist kaputt" fuer einen
 * Zustand, in dem sie voellig in Ordnung ist und nur eine Umgebungsvariable
 * fehlt. Wer den Fehler untersucht, suchte dann an der falschen Stelle, und die
 * Fehlerzaehler liefen mit.</p>
 *
 * <p>Daraus wird stattdessen eine <b>503</b> mit dem Text, der sagt, was zu tun
 * ist. Zusammen mit dem Statusendpunkt und der Warnung beim Start ist das die
 * geforderte saubere Abschaltung: Die Funktion meldet sich ab, statt bei jedem
 * Versuch nach einem Programmfehler auszusehen.</p>
 */
public class FleetPingAbgeschaltetException extends RuntimeException {

    public FleetPingAbgeschaltetException(String message) {
        super(message);
    }
}

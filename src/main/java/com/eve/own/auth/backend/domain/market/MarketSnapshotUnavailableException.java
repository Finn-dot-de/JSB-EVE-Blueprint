package com.eve.own.auth.backend.domain.market;

/**
 * Der Marktabzug kam nicht zustande - es gibt nichts zu schreiben.
 *
 * <p>Eine eigene Ausnahme, damit der Aufrufer den Unterschied sieht: das hier
 * ist der geordnete Abbruch mit der ausdruecklichen Anweisung "alte Preise
 * stehenlassen", kein unerwarteter Fehler. Wer sie faengt, darf danach
 * <em>nichts</em> in {@code market_prices} schreiben.</p>
 */
public class MarketSnapshotUnavailableException extends RuntimeException {

    public MarketSnapshotUnavailableException(String message) {
        super(message);
    }

    public MarketSnapshotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

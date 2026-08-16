package com.eve.own.auth.backend.domain.industry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Transport und Verpackung: wie kommt die Ware von Jita zum Bauort.
 *
 * <p>Die Zahlen entscheiden mehr als der reine Einkaufspreis. Fuer fuenf
 * Millionen Tritanium kostet die Ware rund 20 Millionen ISK, der Transport im
 * Sprungfrachter aber 24 Millionen - der Weg ist teurer als der Inhalt. Ein
 * Werkzeug, das nur Einkaufspreise vergleicht, empfiehlt deshalb regelmaessig
 * das Falsche.</p>
 */
public final class LogisticsMath {

    /**
     * Ladevolumen eines Frachters in Kubikmetern.
     *
     * <p>Massgeblich fuer das Schnueren der Paeckchen: was in Jita gekauft wird,
     * muss in Ladungen passen, sonst steht der Einkauf herum.</p>
     */
    public static final long FREIGHTER_CAPACITY = 350_000L;

    /**
     * Ladevolumen eines Sprungfrachters.
     *
     * <p>Deutlich kleiner als ein normaler Frachter - wer nach Nullsec liefert,
     * braucht also mehr Fahrten fuer dieselbe Menge.</p>
     */
    public static final long JUMP_FREIGHTER_CAPACITY = 337_500L;

    /** Ueblicher Satz eines Transportdienstes im Sprungfrachter. */
    public static final BigDecimal JUMP_FREIGHT_PER_M3 = new BigDecimal("460");

    /**
     * Ueblicher Satz fuer Highsec-Transporte.
     *
     * <p>Deutlich guenstiger, weil das Risiko kleiner ist und ein normaler
     * Frachter genuegt.</p>
     */
    public static final BigDecimal HIGHSEC_FREIGHT_PER_M3 = new BigDecimal("120");

    /** Ab diesem Sicherheitsstatus gilt ein System als Highsec. */
    private static final double HIGHSEC_THRESHOLD = 0.45;

    /** Ab diesem Sicherheitsstatus gilt ein System als Lowsec. */
    private static final double LOWSEC_THRESHOLD = 0.0;

    private LogisticsMath() {
        throw new AssertionError("Rechenhilfe, nicht instanziierbar.");
    }

    /** Wie die Ware zum Bauort kommt. */
    public enum Transport {
        /** Innerhalb von Jita - nichts zu fahren. */
        NONE("Kein Transport nötig", BigDecimal.ZERO, FREIGHTER_CAPACITY),

        /** Normaler Frachter durch Highsec. */
        FREIGHTER("Frachter durch Highsec", HIGHSEC_FREIGHT_PER_M3, FREIGHTER_CAPACITY),

        /**
         * Sprungfrachter.
         *
         * <p>Nach Lowsec und Nullsec der uebliche Weg - ein Frachter durch
         * Tore waere dort ein Geschenk an jeden, der zuschaut.</p>
         */
        JUMP_FREIGHTER("Sprungfrachter", JUMP_FREIGHT_PER_M3, JUMP_FREIGHTER_CAPACITY);

        private final String label;
        private final BigDecimal perCubicMeter;
        private final long capacity;

        Transport(String label, BigDecimal perCubicMeter, long capacity) {
            this.label = label;
            this.perCubicMeter = perCubicMeter;
            this.capacity = capacity;
        }

        public String label() {
            return label;
        }

        public BigDecimal perCubicMeter() {
            return perCubicMeter;
        }

        public long capacity() {
            return capacity;
        }
    }

    /**
     * Welcher Transport zum Bauort passt.
     *
     * <p>Der Sicherheitsstatus entscheidet, nicht die Entfernung: nach Lowsec
     * fuehrt schon ein Sprung, und trotzdem faehrt dort niemand mit einem
     * Frachter durch die Tore. In Highsec ist es umgekehrt - ein Sprungfrachter
     * kann dort gar nicht springen.</p>
     *
     * @param security      Sicherheitsstatus des Zielsystems
     * @param jumpsFromJita Sprungentfernung, {@code null} wenn unerreichbar
     */
    public static Transport transportFor(Double security, Integer jumpsFromJita) {
        if (jumpsFromJita != null && jumpsFromJita == 0) {
            return Transport.NONE;
        }
        if (security == null) {
            // Ohne Kenntnis des Ziels die teurere Annahme - eine zu niedrig
            // geschaetzte Rechnung faellt erst beim Bezahlen auf.
            return Transport.JUMP_FREIGHTER;
        }
        return security >= HIGHSEC_THRESHOLD ? Transport.FREIGHTER : Transport.JUMP_FREIGHTER;
    }

    /** Ob ein System als Highsec gilt. */
    public static boolean isHighsec(Double security) {
        return security != null && security >= HIGHSEC_THRESHOLD;
    }

    /** Ob ein System als Lowsec gilt. */
    public static boolean isLowsec(Double security) {
        return security != null && security < HIGHSEC_THRESHOLD && security > LOWSEC_THRESHOLD;
    }

    /** Die Transportkosten fuer ein Volumen. */
    public static BigDecimal freightCost(double cubicMeters, Transport transport) {
        if (cubicMeters <= 0 || transport == Transport.NONE) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(cubicMeters)
                .multiply(transport.perCubicMeter())
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Wie viele Ladungen ein Volumen ergibt.
     *
     * <p>Aufgerundet, denn eine halbe Fahrt gibt es nicht - und genau daran
     * scheitern Einkaeufe, die auf dem Papier passten.</p>
     */
    public static long loads(double cubicMeters, Transport transport) {
        if (cubicMeters <= 0) {
            return 0;
        }
        return (long) Math.ceil(cubicMeters / transport.capacity());
    }
}

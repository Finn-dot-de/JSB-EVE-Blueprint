package com.eve.own.auth.backend.domain.industry.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Wiederaufbereitung: wie viel Erz es braucht, um eine Menge Mineral zu bekommen.
 *
 * <p>Die Frage entscheidet regelmaessig ueber Millionen. Fuenf Millionen
 * Tritanium kosten als Mineral rund 20 Millionen ISK; dieselbe Menge aus
 * Veldspar kostet knapp 18 Millionen - und komprimiert nimmt sie ein Vierzigstel
 * des Platzes ein, was den Transport von 24 Millionen auf unter eine drueckt.</p>
 *
 * <p>Der Haken liegt in der Ausbeute. Sie ist nie hundert Prozent, und wer mit
 * hundert rechnet, kauft zu wenig ein.</p>
 */
public final class ReprocessingMath {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    /** Grundausbeute einer NPC-Station mit vollem Standing. */
    public static final BigDecimal NPC_STATION_BASE = new BigDecimal("0.50");

    /** Grundausbeute einer Athanor. */
    public static final BigDecimal ATHANOR_BASE = new BigDecimal("0.50");

    /**
     * Grundausbeute einer Tatara.
     *
     * <p>Gleich der Athanor - der Unterschied der Tatara liegt in den Boni auf
     * Reaktionen und in den staerkeren Rigs, nicht in der Grundrate.</p>
     */
    public static final BigDecimal TATARA_BASE = new BigDecimal("0.50");

    private ReprocessingMath() {
        throw new AssertionError("Rechenhilfe, nicht instanziierbar.");
    }

    /**
     * Die Gesamtausbeute.
     *
     * <p>{@code basis * (1 + 0.03*Reprocessing) * (1 + 0.02*ReprocessingEfficiency)
     * * (1 + 0.02*Erzskill) * Rig}</p>
     *
     * <p>Multiplikativ verkettet, wie bei den Fertigungsboni auch. Das Ergebnis
     * wird bei 1,0 gedeckelt: mehr als das Erz enthaelt, kommt nicht heraus.</p>
     *
     * @param base            Grundrate der Anlage
     * @param reprocessing    Stufe des Skills Reprocessing (0-5)
     * @param efficiency      Stufe des Skills Reprocessing Efficiency (0-5)
     * @param oreSpecific     Stufe des erztypspezifischen Skills (0-5)
     * @param rigMultiplier   Faktor der Struktur-Rigs, 1.0 ohne
     */
    public static BigDecimal yield(BigDecimal base, int reprocessing, int efficiency,
                                   int oreSpecific, BigDecimal rigMultiplier) {
        BigDecimal grund = base == null ? NPC_STATION_BASE : base;
        BigDecimal rig = rigMultiplier == null ? BigDecimal.ONE : rigMultiplier;

        BigDecimal ergebnis = grund
                .multiply(faktor("0.03", reprocessing), MC)
                .multiply(faktor("0.02", efficiency), MC)
                .multiply(faktor("0.02", oreSpecific), MC)
                .multiply(rig, MC);

        return ergebnis.min(BigDecimal.ONE);
    }

    private static BigDecimal faktor(String proStufe, int stufe) {
        int begrenzt = Math.clamp(stufe, 0, 5);
        return BigDecimal.ONE.add(new BigDecimal(proStufe).multiply(BigDecimal.valueOf(begrenzt)));
    }

    /**
     * Wie viele Einheiten Erz noetig sind, um eine Menge Mineral zu erhalten.
     *
     * <p>Erz wird in Portionen aufbereitet - bei den meisten Erzen hundert
     * Einheiten. Wer 150 Einheiten in den Ofen legt, bekommt die Ausbeute fuer
     * hundert; der Rest bleibt liegen. Deshalb wird auf ganze Portionen
     * aufgerundet, nicht auf ganze Einheiten.</p>
     *
     * @param wantedMineral  gewuenschte Mineralmenge
     * @param mineralPerBatch wie viel Mineral eine Portion liefert (aus invTypeMaterials)
     * @param portionSize    Groesse einer Portion (aus invTypes.portionSize)
     * @param yield          Ausbeute als Anteil, siehe {@link #yield}
     * @return benoetigte Erzmenge in Einheiten
     */
    public static long oreUnitsFor(long wantedMineral, long mineralPerBatch,
                                   long portionSize, BigDecimal yield) {
        if (wantedMineral <= 0 || mineralPerBatch <= 0) {
            return 0;
        }
        BigDecimal ausbeute = yield == null || yield.signum() <= 0 ? BigDecimal.ONE : yield;
        long portion = Math.max(1, portionSize);

        // Mineral je Portion nach Ausbeute - das ist, was tatsaechlich herauskommt.
        BigDecimal jePortion = BigDecimal.valueOf(mineralPerBatch).multiply(ausbeute, MC);
        if (jePortion.signum() <= 0) {
            return 0;
        }
        long portionen = BigDecimal.valueOf(wantedMineral)
                .divide(jePortion, 0, RoundingMode.CEILING)
                .longValueExact();

        return portionen * portion;
    }

    /**
     * Wie viel Mineral aus einer Erzmenge herauskommt.
     *
     * <p>Die Gegenrichtung, fuer die Frage "reicht das, was ich schon habe".
     * Angebrochene Portionen zaehlen nicht mit - genau das uebersieht man leicht
     * und wundert sich dann ueber die fehlenden letzten Einheiten.</p>
     */
    public static long mineralFromOre(long oreUnits, long mineralPerBatch,
                                      long portionSize, BigDecimal yield) {
        if (oreUnits <= 0 || mineralPerBatch <= 0) {
            return 0;
        }
        BigDecimal ausbeute = yield == null || yield.signum() <= 0 ? BigDecimal.ONE : yield;
        long portion = Math.max(1, portionSize);
        long portionen = oreUnits / portion;

        return BigDecimal.valueOf(portionen)
                .multiply(BigDecimal.valueOf(mineralPerBatch), MC)
                .multiply(ausbeute, MC)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
    }
}

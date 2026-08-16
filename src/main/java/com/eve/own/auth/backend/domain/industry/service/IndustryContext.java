package com.eve.own.auth.backend.domain.industry.service;

import java.math.BigDecimal;

/**
 * Alles, was die Fertigungsrechnung an Randbedingungen braucht - als ein Wert.
 *
 * <p>Bewusst ein reiner Datensatz ohne Verhalten und ohne Herkunft: der
 * Rechenkern soll sich testen lassen, ohne dass eine Datenbank, ein ESI-Konto
 * oder ein angemeldeter Charakter existiert. Wer die Werte beschafft, ist eine
 * andere Frage als wie gerechnet wird.</p>
 *
 * @param activityId          Aktivitaet in SDE-Zaehlung, siehe
 *                            {@link com.eve.own.auth.backend.domain.industry.IndustryActivity}
 * @param materialEfficiency  ME der Blaupause in Prozent (0 bis 10)
 * @param timeEfficiency      TE der Blaupause in Prozent (0 bis 20)
 * @param structureMaterial   Materialfaktor der Struktur, z.B. 0.99 fuer eine
 *                            Raitaru, 1.00 fuer eine NPC-Station
 * @param structureTime       Zeitfaktor der Struktur, z.B. 0.85 fuer eine Raitaru
 * @param rigMaterialPercent  Materialbonus des Rigs in Prozent (0, 2.0 oder 2.4)
 * @param rigTimePercent      Zeitbonus des Rigs in Prozent
 * @param securityMultiplier  Sicherheitsfaktor des Rigs: 1.0 Highsec, 1.9 Lowsec,
 *                            2.1 Nullsec und Wurmloch. Muss aus den Attributen des
 *                            konkreten Rigs kommen, nie als Konstante geraten werden.
 * @param industrySkill       Stufe des Skills Industry (0 bis 5)
 * @param advancedIndustry    Stufe des Skills Advanced Industry (0 bis 5)
 * @param implantTimeFactor   Faktor des Zeit-Implantats, z.B. 0.96 fuer ein BX-804,
 *                            1.0 ohne Implantat
 * @param systemCostIndex     Kostenindex des Systems fuer diese Aktivitaet
 * @param structureCostBonus  Kostenfaktor der Struktur, z.B. 0.97 fuer eine Raitaru
 * @param facilityTax         Steuersatz der Anlage als Anteil, ingame auf 0.10 gedeckelt
 * @param alphaCloneTax       Zuschlag fuer Alpha-Konten (0.00 oder 0.25)
 */
public record IndustryContext(
        int activityId,
        int materialEfficiency,
        int timeEfficiency,
        BigDecimal structureMaterial,
        BigDecimal structureTime,
        BigDecimal rigMaterialPercent,
        BigDecimal rigTimePercent,
        BigDecimal securityMultiplier,
        int industrySkill,
        int advancedIndustry,
        BigDecimal implantTimeFactor,
        BigDecimal systemCostIndex,
        BigDecimal structureCostBonus,
        BigDecimal facilityTax,
        BigDecimal alphaCloneTax) {

    /** Der ingame geltende Deckel der Anlagensteuer. */
    public static final BigDecimal MAX_FACILITY_TAX = new BigDecimal("0.10");

    public IndustryContext {
        structureMaterial = orOne(structureMaterial);
        structureTime = orOne(structureTime);
        implantTimeFactor = orOne(implantTimeFactor);
        structureCostBonus = orOne(structureCostBonus);
        securityMultiplier = securityMultiplier == null ? BigDecimal.ONE : securityMultiplier;
        rigMaterialPercent = orZero(rigMaterialPercent);
        rigTimePercent = orZero(rigTimePercent);
        systemCostIndex = orZero(systemCostIndex);
        alphaCloneTax = orZero(alphaCloneTax);
        // Ingame laesst sich keine hoehere Steuer einstellen. Ein groesserer Wert
        // kaeme nur aus einem Eingabefehler und wuerde die Gebuehr aufblaehen.
        facilityTax = orZero(facilityTax).min(MAX_FACILITY_TAX).max(BigDecimal.ZERO);
    }

    private static BigDecimal orOne(BigDecimal value) {
        return value == null ? BigDecimal.ONE : value;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Der einfachste Fall: NPC-Station, keine Skills, keine Blaupausenforschung.
     * Nuetzlich als Ausgangspunkt und in Tests.
     */
    public static IndustryContext plain(int activityId) {
        return new IndustryContext(
                activityId, 0, 0,
                BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                0, 0, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

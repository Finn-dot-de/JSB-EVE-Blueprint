package com.eve.own.auth.backend.domain.industry;

/**
 * Uebersetzt zwischen den Aktivitaets-Nummern von ESI und denen der SDE.
 *
 * <p>Die beiden zaehlen Reaktionen unterschiedlich. ESI meldet fuer einen
 * Reaktionsjob {@code activity_id = 9}, die SDE kennt diese Nummer gar nicht -
 * dort stehen Reaktionen unter {@code 11}. Nachgeprueft:
 * {@code SELECT DISTINCT "activityID" FROM evesde."industryActivity"} liefert
 * 1, 3, 4, 5, 8 und 11. Alle uebrigen Nummern stimmen ueberein.</p>
 *
 * <p>Wer die Nummern eins zu eins uebernimmt, verbindet den Jobspiegel blind
 * mit {@code industryActivity} und verliert dabei <em>jeden</em> Reaktionsjob -
 * ohne Fehlermeldung, die Zeile fehlt einfach. Deshalb steht die Uebersetzung
 * als eigene Schicht am Eingang und nicht verstreut in den Abfragen.</p>
 *
 * <p>Unbekannte Nummern werfen bewusst nicht. CCP kann jederzeit eine neue
 * Aktivitaet einfuehren; dann soll der Job als "unbekannte Aktivitaet"
 * durchgereicht und angezeigt werden, statt den ganzen Abgleich scheitern zu
 * lassen.</p>
 */
public final class IndustryActivity {

    /** Fertigung - der einzige Fall, den die erste Fassung auch plant. */
    public static final int MANUFACTURING = 1;

    /** Forschung an der Zeiteffizienz (Skill: Research). */
    public static final int TIME_EFFICIENCY_RESEARCH = 3;

    /** Forschung an der Materialeffizienz (Skill: Metallurgy). */
    public static final int MATERIAL_EFFICIENCY_RESEARCH = 4;

    /** Kopieren einer Blaupause. */
    public static final int COPYING = 5;

    /** Invention - aus einer T1-Kopie wird eine T2-Kopie. */
    public static final int INVENTION = 8;

    /** Reaktionen, wie die SDE sie zaehlt. */
    public static final int REACTION_SDE = 11;

    /** Reaktionen, wie ESI sie zaehlt. */
    public static final int REACTION_ESI = 9;

    private IndustryActivity() {
        throw new AssertionError("Konstantenhalter, nicht instanziierbar.");
    }

    /**
     * Die SDE-Nummer zu einer von ESI gemeldeten Aktivitaet.
     *
     * @return {@code null}, wenn die Nummer unbekannt ist - der Aufrufer soll den
     *         Job dann trotzdem anzeigen, nur eben ohne Stammdaten dazu.
     */
    public static Integer sdeFromEsi(Integer esiActivityId) {
        if (esiActivityId == null) {
            return null;
        }
        if (esiActivityId == REACTION_ESI) {
            return REACTION_SDE;
        }
        return isKnownSdeActivity(esiActivityId) ? esiActivityId : null;
    }

    /**
     * Die ESI-Nummer zu einer SDE-Aktivitaet - die Gegenrichtung.
     *
     * @return {@code null} bei unbekannter Nummer.
     */
    public static Integer esiFromSde(Integer sdeActivityId) {
        if (sdeActivityId == null) {
            return null;
        }
        if (sdeActivityId == REACTION_SDE) {
            return REACTION_ESI;
        }
        return isKnownSdeActivity(sdeActivityId) ? sdeActivityId : null;
    }

    /** Ob die SDE zu dieser Nummer ueberhaupt Stammdaten fuehrt. */
    public static boolean isKnownSdeActivity(int sdeActivityId) {
        return sdeActivityId == MANUFACTURING
                || sdeActivityId == TIME_EFFICIENCY_RESEARCH
                || sdeActivityId == MATERIAL_EFFICIENCY_RESEARCH
                || sdeActivityId == COPYING
                || sdeActivityId == INVENTION
                || sdeActivityId == REACTION_SDE;
    }

    /** Ob aus dieser Aktivitaet ein Gegenstand hervorgeht - nur dann zaehlt sie fuer einen Auftrag. */
    public static boolean producesItems(int sdeActivityId) {
        return sdeActivityId == MANUFACTURING || sdeActivityId == REACTION_SDE;
    }

    /** Ein lesbarer Name fuer die Oberflaeche. */
    public static String label(Integer sdeActivityId) {
        if (sdeActivityId == null) {
            return "Unbekannte Aktivität";
        }
        return switch (sdeActivityId) {
            case MANUFACTURING -> "Fertigung";
            case TIME_EFFICIENCY_RESEARCH -> "Zeitforschung";
            case MATERIAL_EFFICIENCY_RESEARCH -> "Materialforschung";
            case COPYING -> "Kopieren";
            case INVENTION -> "Invention";
            case REACTION_SDE -> "Reaktion";
            default -> "Unbekannte Aktivität";
        };
    }
}

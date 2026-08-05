package com.eve.own.auth.backend.common;

/**
 * Baut die URLs des offiziellen EVE-Bildservers.
 *
 * <p>Frueher stand das Schema an einem guten Dutzend Stellen ausgeschrieben im
 * Code. Bei einer Aenderung am Bildserver - oder auch nur an der gewuenschten
 * Kantenlaenge - musste man sie alle finden. Deshalb gibt es genau hier die
 * einzige Beschreibung dieser Adressen.</p>
 */
public final class EveImageUrls {

    private static final String BASE_URL = "https://images.evetech.net";

    /** Uebliche Kantenlaenge fuer Listen- und Tabellendarstellungen. */
    public static final int SIZE_SMALL = 64;

    /** Kantenlaenge fuer hervorgehobene Darstellungen, etwa den Kopfbereich. */
    public static final int SIZE_LARGE = 128;

    /** Kantenlaenge fuer die grossflaechigen Schiffsansichten. */
    public static final int SIZE_RENDER = 256;

    private EveImageUrls() {
        throw new AssertionError("Utility-Klasse, nicht instanziierbar.");
    }

    public static String portrait(Long characterId) {
        return portrait(characterId, SIZE_SMALL);
    }

    public static String portrait(Long characterId, int size) {
        return BASE_URL + "/characters/" + characterId + "/portrait?size=" + size;
    }

    public static String corporationLogo(Long corporationId) {
        return corporationLogo(corporationId, SIZE_SMALL);
    }

    public static String corporationLogo(Long corporationId, int size) {
        return BASE_URL + "/corporations/" + corporationId + "/logo?size=" + size;
    }

    public static String allianceLogo(Long allianceId, int size) {
        return BASE_URL + "/alliances/" + allianceId + "/logo?size=" + size;
    }

    public static String typeIcon(Long typeId) {
        return typeIcon(typeId, SIZE_SMALL);
    }

    public static String typeIcon(Long typeId, int size) {
        return BASE_URL + "/types/" + typeId + "/icon?size=" + size;
    }

    public static String typeRender(Long typeId) {
        return BASE_URL + "/types/" + typeId + "/render?size=" + SIZE_RENDER;
    }

    /**
     * Bild fuer eine Besitzer-Zeile: Corp-Hangars tragen das Corporation-Logo,
     * Spieler-Accounts das Charakter-Portraet.
     *
     * <p>Charakter- und Corporation-IDs stammen aus demselben ID-Raum, deshalb
     * genuegt die eine ID plus die Herkunft der Zeile.</p>
     */
    public static String ownerImage(Long ownerId, boolean corporation) {
        return corporation ? corporationLogo(ownerId) : portrait(ownerId);
    }
}

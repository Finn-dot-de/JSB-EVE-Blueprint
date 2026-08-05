package com.eve.own.auth.backend.domain.character.entity;

/**
 * Die Kennzahlen, die je Charakter fortgeschrieben werden.
 *
 * <p>Der Name der Konstante ist zugleich der in {@code character_activity}
 * gespeicherte Wert. Die Spalte bleibt bewusst eine Zeichenkette: dort koennen
 * auch von Hand gepflegte Eintraege liegen, die eine strikte Enum-Abbildung beim
 * Lesen sprengen wuerden.</p>
 */
public enum ActivityType {

    /** Abgebautes Volumen in m^3 seit dem letzten Sync. */
    MINING_VOLUME,

    /** Kopfgelder aus dem Wallet-Journal. */
    PVE_ISK,

    /** Anzahl der Kopfgeld-Gutschriften - ein grober Zaehler fuer erlegte NPCs. */
    RAT_KILLS,

    /**
     * Ueberweisung an die Corporation, die als Steuerzahlung erkannt wurde.
     *
     * <p>Wird beim Sync nie geloescht: die Zahlungshistorie ist die Grundlage
     * der Mining-Bilanz und laesst sich aus ESI nicht wiederherstellen.</p>
     */
    TAX_PAYMENT;

    /** Der in der Datenbank abgelegte Wert. */
    public String dbValue() {
        return name();
    }

    public boolean matches(String storedValue) {
        return name().equals(storedValue);
    }
}

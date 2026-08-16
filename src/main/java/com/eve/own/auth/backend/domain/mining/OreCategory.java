package com.eve.own.auth.backend.domain.mining;

import java.util.Arrays;
import java.util.Set;

/**
 * Die Steuerklassen abbaubarer Typen.
 *
 * <p>Die Zuordnung haengt allein an der SDE-Gruppe des Typs. Diese Regel stand
 * vorher zweimal als if-else-Kette im Code - einmal beim Anlegen der Steuersaetze,
 * einmal beim Nachtragen unbekannter Erze. Zwei Kopien einer Regel bedeuten
 * frueher oder spaeter zwei verschiedene Regeln.</p>
 */
public enum OreCategory {

    /** Eis. Eine einzige Gruppe. */
    ICE(Set.of(423L)),

    /** Gaswolken. */
    GAS(Set.of(711L)),

    /** Mondmaterialien in ihren vier Seltenheitsstufen plus Ubiquitous. */
    MOON(Set.of(1884L, 1920L, 1921L, 1922L, 1923L)),

    /** Alles uebrige Erz - zugleich die Rueckfallebene fuer unbekannte Gruppen. */
    ORE(Set.of());

    private final Set<Long> groupIds;

    OreCategory(Set<Long> groupIds) {
        this.groupIds = groupIds;
    }

    /**
     * Die Steuerklasse zu einer SDE-Gruppe.
     *
     * @return {@link #ORE}, wenn die Gruppe unbekannt oder nicht angegeben ist
     */
    public static OreCategory ofGroup(Long groupId) {
        if (groupId == null) {
            return ORE;
        }
        return Arrays.stream(values())
                .filter(category -> category.groupIds.contains(groupId))
                .findFirst()
                .orElse(ORE);
    }

    /** Der in {@code mining_tax_rates.category} abgelegte Wert. */
    public String dbValue() {
        return name();
    }
}

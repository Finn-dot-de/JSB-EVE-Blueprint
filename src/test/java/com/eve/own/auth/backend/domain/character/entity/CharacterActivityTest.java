package com.eve.own.auth.backend.domain.character.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Kennzahlen eines Charakters")
class CharacterActivityTest {

    private static final Instant MEASURED_AT = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    @DisplayName("legt einen Messwert mit dem Namen der Kennzahl an")
    void createsActivity() {
        CharacterActivity activity =
                CharacterActivity.of(42L, ActivityType.MINING_VOLUME, 1234.5, MEASURED_AT);

        assertThat(activity.getCharacterId()).isEqualTo(42L);
        assertThat(activity.getActivityType()).isEqualTo("MINING_VOLUME");
        assertThat(activity.getValue()).isEqualTo(1234.5);
        assertThat(activity.getTimestamp()).isEqualTo(MEASURED_AT);
    }

    @Test
    @DisplayName("erkennt die eigene Kennzahl wieder")
    void recognisesOwnType() {
        CharacterActivity activity =
                CharacterActivity.of(42L, ActivityType.TAX_PAYMENT, 1_000_000, MEASURED_AT);

        assertThat(activity.isOfType(ActivityType.TAX_PAYMENT)).isTrue();
        assertThat(activity.isOfType(ActivityType.PVE_ISK)).isFalse();
    }

    @Test
    @DisplayName("laesst sich auch mit einem unbekannten Typ aus der Datenbank lesen")
    void toleratesUnknownStoredType() {
        // Von Hand gepflegte Zeilen koennen Werte tragen, die das Enum nicht kennt.
        // Genau deshalb bleibt die Spalte eine Zeichenkette.
        CharacterActivity activity = new CharacterActivity();
        activity.setActivityType("HANDGEPFLEGT");

        assertThat(activity.isOfType(ActivityType.TAX_PAYMENT)).isFalse();
        assertThat(activity.getActivityType()).isEqualTo("HANDGEPFLEGT");
    }

    @Test
    @DisplayName("gibt jeden Kennzahlnamen als Datenbankwert heraus")
    void exposesDbValues() {
        assertThat(ActivityType.MINING_VOLUME.dbValue()).isEqualTo("MINING_VOLUME");
        assertThat(ActivityType.PVE_ISK.dbValue()).isEqualTo("PVE_ISK");
        assertThat(ActivityType.RAT_KILLS.dbValue()).isEqualTo("RAT_KILLS");
        assertThat(ActivityType.TAX_PAYMENT.dbValue()).isEqualTo("TAX_PAYMENT");
    }
}

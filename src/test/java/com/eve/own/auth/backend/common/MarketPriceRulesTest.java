package com.eve.own.auth.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die eine Regel: null ISK ist kein Preis.
 *
 * <p>Sie steht an einer Stelle, weil sie an vielen gilt - Einkaufsliste,
 * Kaufen/Bauen-Vergleich, Erzwahl, Auftragszeilen. Waere sie an jeder dieser
 * Stellen einzeln formuliert, waere sie an einer davon vergessen worden; genau
 * so ist der gemeldete Fehler entstanden.</p>
 */
class MarketPriceRulesTest {

    @Test
    @DisplayName("macht aus 0 ein ehrliches null")
    void nullIskIstKeinPreis() {
        // In EVE existiert keine Order zu 0 ISK. Wer die 0 als Preis
        // durchlaesst, bekommt einen Kauf geschenkt: er gewinnt jeden
        // Vergleich, den er gar nicht antreten duerfte, und macht aus einer
        // fehlenden Auskunft eine Summe.
        assertThat(MarketPriceRules.usable(0.0)).isNull();
        assertThat(MarketPriceRules.isUsable(0.0)).isFalse();
    }

    @Test
    @DisplayName("laesst einen echten Preis unveraendert durch")
    void echtePreiseBleiben() {
        // Die Regel darf nur die Null fangen. Wuerde sie mehr fangen, waere
        // jede Rechnung dahinter wertlos.
        assertThat(MarketPriceRules.usable(3.97)).isEqualTo(3.97);
        assertThat(MarketPriceRules.usable(0.01)).isEqualTo(0.01);
        assertThat(MarketPriceRules.isUsable(193_800_000.0)).isTrue();
    }

    @Test
    @DisplayName("faengt auch einen negativen Preis")
    void negativeWerdenMitgefangen() {
        // Ein Vorzeichenfehler in einer fremden Quelle waere sonst der einzige
        // Weg, auf dem ein Kauf Geld einbringt - und eine solche Zeile gewaenne
        // jeden Vergleich noch deutlicher als die 0.
        assertThat(MarketPriceRules.usable(-1.0)).isNull();
    }

    @Test
    @DisplayName("behandelt einen fehlenden Preis wie bisher")
    void nullBleibtNull() {
        assertThat(MarketPriceRules.usable(null)).isNull();
        assertThat(MarketPriceRules.isUsable(null)).isFalse();
    }
}

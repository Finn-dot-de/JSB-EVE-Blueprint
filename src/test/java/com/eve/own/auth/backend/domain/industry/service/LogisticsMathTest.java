package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.eve.own.auth.backend.domain.industry.service.LogisticsMath.Transport;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Transport, Verpackung und die Ausbeute der Wiederaufbereitung. */
class LogisticsMathTest {

    @Nested
    @DisplayName("Transportmittel")
    class Mittel {

        @Test
        void highsecFaehrtMitDemFrachter() {
            assertThat(LogisticsMath.transportFor(0.9, 5)).isEqualTo(Transport.FREIGHTER);
            assertThat(LogisticsMath.transportFor(0.5, 12)).isEqualTo(Transport.FREIGHTER);
        }

        @Test
        void lowsecUndNullsecBrauchenDenSprungfrachter() {
            // Nach Lowsec führt oft nur ein Sprung - und trotzdem fährt dort
            // niemand mit einem Frachter durch die Tore.
            assertThat(LogisticsMath.transportFor(0.4, 1)).isEqualTo(Transport.JUMP_FREIGHTER);
            assertThat(LogisticsMath.transportFor(-0.3, 30)).isEqualTo(Transport.JUMP_FREIGHTER);
        }

        @Test
        void inJitaSelbstFaelltKeinTransportAn() {
            assertThat(LogisticsMath.transportFor(0.95, 0)).isEqualTo(Transport.NONE);
            assertThat(LogisticsMath.freightCost(50_000, Transport.NONE))
                    .isEqualByComparingTo("0");
        }

        @Test
        void ohneKenntnisDesZielsWirdTeuerGerechnet() {
            // Eine zu niedrig geschätzte Rechnung fällt erst beim Bezahlen auf.
            assertThat(LogisticsMath.transportFor(null, 20)).isEqualTo(Transport.JUMP_FREIGHTER);
        }

        @Test
        void erkenntDieSicherheitsstufen() {
            assertThat(LogisticsMath.isHighsec(0.5)).isTrue();
            assertThat(LogisticsMath.isHighsec(0.4)).isFalse();
            assertThat(LogisticsMath.isLowsec(0.3)).isTrue();
            assertThat(LogisticsMath.isLowsec(-0.1)).isFalse();
            assertThat(LogisticsMath.isLowsec(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Kosten und Ladungen")
    class Kosten {

        @Test
        void rechnetDenSprungfrachterZuVierhundertsechzig() {
            // 5,2 Mio Tritanium sind 52.000 m3 - der Weg kostet dann mehr als die Ware.
            assertThat(LogisticsMath.freightCost(52_000, Transport.JUMP_FREIGHTER))
                    .isEqualByComparingTo("23920000.00");
        }

        @Test
        void komprimiertesErzKostetEinenBruchteil() {
            // Dieselbe Menge Tritanium als komprimiertes Veldspar: 1.625 m3.
            BigDecimal komprimiert = LogisticsMath.freightCost(1_625, Transport.JUMP_FREIGHTER);
            BigDecimal mineral = LogisticsMath.freightCost(52_000, Transport.JUMP_FREIGHTER);

            assertThat(komprimiert).isEqualByComparingTo("747500.00");
            assertThat(mineral.divide(komprimiert, 0, java.math.RoundingMode.HALF_UP))
                    .isEqualByComparingTo("32");
        }

        @Test
        void rundetAufGanzeLadungen() {
            // Eine halbe Fahrt gibt es nicht - genau daran scheitern Einkäufe,
            // die auf dem Papier passten.
            assertThat(LogisticsMath.loads(350_000, Transport.FREIGHTER)).isEqualTo(1);
            assertThat(LogisticsMath.loads(350_001, Transport.FREIGHTER)).isEqualTo(2);
            assertThat(LogisticsMath.loads(0, Transport.FREIGHTER)).isZero();
        }

        @Test
        void derSprungfrachterFasstWeniger() {
            // 350.000 m3 passen in einen Frachter, aber nicht in einen Sprungfrachter.
            assertThat(LogisticsMath.loads(350_000, Transport.FREIGHTER)).isEqualTo(1);
            assertThat(LogisticsMath.loads(350_000, Transport.JUMP_FREIGHTER)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Wiederaufbereitung")
    class Aufbereitung {

        @Test
        void ohneSkillsBleibtDieHalbeAusbeute() {
            assertThat(ReprocessingMath.yield(
                    ReprocessingMath.NPC_STATION_BASE, 0, 0, 0, BigDecimal.ONE))
                    .isEqualByComparingTo("0.50");
        }

        @Test
        void verkettetDieSkillsMultiplikativ() {
            // 0,50 * 1,15 * 1,10 * 1,10 = 0,69575
            assertThat(ReprocessingMath.yield(
                    ReprocessingMath.NPC_STATION_BASE, 5, 5, 5, BigDecimal.ONE))
                    .isEqualByComparingTo("0.69575");
        }

        @Test
        void deckeltBeiHundertProzent() {
            // Mehr als das Erz enthält, kommt nicht heraus.
            assertThat(ReprocessingMath.yield(
                    new BigDecimal("0.90"), 5, 5, 5, new BigDecimal("1.15")))
                    .isEqualByComparingTo("1");
        }

        @Test
        void rechnetVeldsparAufTritaniumHoch() {
            // Veldspar: 100 Einheiten je Portion, 400 Tritanium daraus.
            // Bei 100 % Ausbeute braucht es 1.300.000 Einheiten für 5,2 Mio Tritanium.
            assertThat(ReprocessingMath.oreUnitsFor(5_200_000, 400, 100, BigDecimal.ONE))
                    .isEqualTo(1_300_000);

            // Bei 80 % entsprechend mehr - wer mit 100 % rechnet, kauft zu wenig ein.
            assertThat(ReprocessingMath.oreUnitsFor(5_200_000, 400, 100, new BigDecimal("0.80")))
                    .isEqualTo(1_625_000);
        }

        @Test
        void rundetAufGanzePortionen() {
            // 150 Einheiten in den Ofen zu legen bringt die Ausbeute für 100.
            assertThat(ReprocessingMath.oreUnitsFor(1, 400, 100, BigDecimal.ONE)).isEqualTo(100);
            assertThat(ReprocessingMath.oreUnitsFor(401, 400, 100, BigDecimal.ONE)).isEqualTo(200);
        }

        @Test
        void rechnetDieGegenrichtung() {
            assertThat(ReprocessingMath.mineralFromOre(1_300_000, 400, 100, BigDecimal.ONE))
                    .isEqualTo(5_200_000);
            // Angebrochene Portionen zählen nicht mit.
            assertThat(ReprocessingMath.mineralFromOre(199, 400, 100, BigDecimal.ONE))
                    .isEqualTo(400);
            assertThat(ReprocessingMath.mineralFromOre(99, 400, 100, BigDecimal.ONE)).isZero();
        }

        @Test
        void kommtMitUnbrauchbarenEingabenKlar() {
            assertThat(ReprocessingMath.oreUnitsFor(0, 400, 100, BigDecimal.ONE)).isZero();
            assertThat(ReprocessingMath.oreUnitsFor(100, 0, 100, BigDecimal.ONE)).isZero();
            assertThat(ReprocessingMath.mineralFromOre(-5, 400, 100, BigDecimal.ONE)).isZero();
        }
    }
}

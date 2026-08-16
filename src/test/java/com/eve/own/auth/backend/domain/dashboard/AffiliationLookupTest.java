package com.eve.own.auth.backend.domain.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Nachschlagetabellen der Zugehoerigkeiten")
class AffiliationLookupTest {

    @Nested
    @DisplayName("Milizen")
    class Militias {

        @ParameterizedTest(name = "Fraktion {0} -> {1}")
        @CsvSource({
                "500007, Amarr",
                "500004, Gallente",
                "500002, Minmatar",
                "500001, Caldari",
                "500011, Angel",
                "500010, Guristas"
        })
        void mapsKnownFactions(long factionId, String label) {
            assertThat(Militia.ofFaction(factionId)).isPresent()
                    .get()
                    .extracting(Militia::label)
                    .isEqualTo(label);
        }

        @Test
        @DisplayName("kennt fremde Fraktionen nicht")
        void ignoresUnknownFaction() {
            assertThat(Militia.ofFaction(999999L)).isEmpty();
        }

        @Test
        @DisplayName("behandelt eine fehlende Fraktion als unbekannt")
        void handlesNullFaction() {
            assertThat(Militia.ofFaction(null)).isEmpty();
        }

        @Test
        @DisplayName("startet alle Zaehler bei null, in Anzeigereihenfolge")
        void buildsEmptyCounters() {
            assertThat(Militia.emptyCounters())
                    .containsExactlyEntriesOf(new java.util.LinkedHashMap<>() {{
                        put("Amarr", 0L);
                        put("Gallente", 0L);
                        put("Minmatar", 0L);
                        put("Caldari", 0L);
                        put("Angel", 0L);
                        put("Guristas", 0L);
                    }});
        }
    }

    @Nested
    @DisplayName("Loyalitaets-Corporations")
    class LoyaltyCorporations {

        @ParameterizedTest(name = "Corporation {0} -> {1}")
        @CsvSource({
                "1000125, CONCORD",
                "1000119, FederalAdmin",
                "1000134, BloodRaiders",
                "1000061, FreedomExtension"
        })
        void mapsKnownCorporations(long corporationId, String key) {
            assertThat(LoyaltyCorporation.ofCorporation(corporationId)).isPresent()
                    .get()
                    .extracting(LoyaltyCorporation::key)
                    .isEqualTo(key);
        }

        @Test
        @DisplayName("weist Paragon nicht als einzelne Corporation aus - das sind die Evermarks")
        void paragonIsNotAListedCorporation() {
            assertThat(LoyaltyCorporation.ofCorporation(LoyaltyCorporation.PARAGON_CORPORATION_ID))
                    .isEmpty();
        }

        @Test
        @DisplayName("kennt fremde Corporations nicht")
        void ignoresUnknownCorporation() {
            assertThat(LoyaltyCorporation.ofCorporation(1L)).isEmpty();
        }

        @Test
        @DisplayName("startet alle Zaehler bei null")
        void buildsEmptyCounters() {
            assertThat(LoyaltyCorporation.emptyCounters())
                    .containsOnlyKeys("CONCORD", "FederalAdmin", "BloodRaiders", "FreedomExtension")
                    .containsValue(0L);
        }
    }
}

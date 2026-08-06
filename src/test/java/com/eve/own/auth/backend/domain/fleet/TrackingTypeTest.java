package com.eve.own.auth.backend.domain.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Art der Anwesenheitserfassung")
class TrackingTypeTest {

    @Test
    @DisplayName("liest die bekannten Werte")
    void parsesKnownValues() {
        assertThat(TrackingType.of("LIVE")).isEqualTo(TrackingType.LIVE);
        assertThat(TrackingType.of("LINK")).isEqualTo(TrackingType.LINK);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"live", "Unbekannt", "", "LIVE_TRACKING"})
    @DisplayName("faellt bei unbekannten Werten auf die Vorgabe zurueck")
    void fallsBackToDefault(String value) {
        assertThat(TrackingType.of(value)).isEqualTo(TrackingType.DEFAULT);
    }

    @Test
    @DisplayName("behandelt einen fehlenden Wert wie einen unbekannten")
    void handlesNull() {
        assertThat(TrackingType.of(null)).isEqualTo(TrackingType.DEFAULT);
    }

    @Test
    @DisplayName("vergleicht sich mit dem gespeicherten Wert")
    void matchesStoredValue() {
        assertThat(TrackingType.LINK.matches("LINK")).isTrue();
        assertThat(TrackingType.LINK.matches("LIVE")).isFalse();
        assertThat(TrackingType.LINK.matches(null)).isFalse();
    }

    @Test
    @DisplayName("gibt den Namen als Datenbankwert heraus")
    void exposesDbValue() {
        assertThat(TrackingType.LIVE.dbValue()).isEqualTo("LIVE");
        assertThat(TrackingType.DEFAULT).isEqualTo(TrackingType.LIVE);
    }
}

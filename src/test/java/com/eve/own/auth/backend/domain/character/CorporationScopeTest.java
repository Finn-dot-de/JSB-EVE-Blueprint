package com.eve.own.auth.backend.domain.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Umfang der betreuten Corporations")
class CorporationScopeTest {

    private static final long MAIN = 98000001L;
    private static final long ALT_A = 98000002L;
    private static final long ALT_B = 98000003L;

    @Test
    @DisplayName("enthaelt ohne Alt-Corporations nur die Haupt-Corporation")
    void mainOnly() {
        CorporationScope scope = new CorporationScope(MAIN, "");

        assertThat(scope.allowedCorporationIds()).containsExactly(MAIN);
        assertThat(scope.mainCorporationId()).isEqualTo(MAIN);
    }

    @ParameterizedTest(name = "Konfiguration \"{0}\"")
    @ValueSource(strings = {
            "98000002,98000003",
            " 98000002 , 98000003 ",
            "98000002,,98000003",
            "98000002,98000003,"
    })
    @DisplayName("liest die Alt-Corporations unabhaengig von Leerzeichen und leeren Feldern")
    void parsesAltCorporations(String configured) {
        CorporationScope scope = new CorporationScope(MAIN, configured);

        assertThat(scope.allowedCorporationIds()).containsExactly(MAIN, ALT_A, ALT_B);
    }

    @Test
    @DisplayName("behandelt eine fehlende Konfiguration wie eine leere")
    void handlesNullConfiguration() {
        assertThat(new CorporationScope(MAIN, null).allowedCorporationIds()).containsExactly(MAIN);
    }

    @Test
    @DisplayName("nimmt die Haupt-Corporation nicht doppelt auf")
    void doesNotDuplicateMain() {
        CorporationScope scope = new CorporationScope(MAIN, MAIN + "," + ALT_A);

        assertThat(scope.allowedCorporationIds()).containsExactly(MAIN, ALT_A);
    }

    @Test
    @DisplayName("erkennt zugelassene und fremde Corporations")
    void answersMembershipQuestions() {
        CorporationScope scope = new CorporationScope(MAIN, String.valueOf(ALT_A));

        assertThat(scope.isAllowed(MAIN)).isTrue();
        assertThat(scope.isAllowed(ALT_A)).isTrue();
        assertThat(scope.isAllowed(99999999L)).isFalse();
        assertThat(scope.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("unterscheidet die Haupt-Corporation von den Alt-Corporations")
    void distinguishesMainFromAlts() {
        CorporationScope scope = new CorporationScope(MAIN, String.valueOf(ALT_A));

        assertThat(scope.isMain(MAIN)).isTrue();
        assertThat(scope.isMain(ALT_A)).isFalse();
        assertThat(scope.isMain(null)).isFalse();
    }

    @Test
    @DisplayName("gibt die Liste unveraenderlich heraus")
    void exposesImmutableList() {
        CorporationScope scope = new CorporationScope(MAIN, "");

        assertThatThrownBy(() -> scope.allowedCorporationIds().add(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

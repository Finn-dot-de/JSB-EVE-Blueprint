package com.eve.own.auth.backend.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Scopes aus der Nutzlast eines Access-Tokens")
class EveTokenScopesTest {

    private static final String TITLES = "esi-corporations.read_titles.v1";
    private static final String ROLES = "esi-characters.read_corporation_roles.v1";

    private static String token(String scopeClaim) {
        String payload = "{\"sub\":\"CHARACTER:EVE:1000\"" + scopeClaim + "}";
        return "kopf." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signatur";
    }

    @Test
    @DisplayName("liest eine Liste von Scopes")
    void readsScopeArray() {
        assertThat(EveTokenScopes.of(token(",\"scp\":[\"" + TITLES + "\",\"" + ROLES + "\"]")))
                .containsExactlyInAnyOrder(TITLES, ROLES);
    }

    @Test
    @DisplayName("liest auch einen einzelnen Scope als blanken Text")
    void readsSingleScopeAsPlainString() {
        // Bei genau einem gewaehrten Scope schreibt CCP keinen einelementigen
        // Array, sondern einen Text. Ohne diesen Zweig gilt so ein Token als
        // scope-frei - und die Meldung schickt den Nutzer zur Neuanmeldung,
        // obwohl der Scope da ist.
        assertThat(EveTokenScopes.of(token(",\"scp\":\"" + TITLES + "\"")))
                .containsExactly(TITLES);
    }

    @Test
    @DisplayName("meldet ein Token ganz ohne Scope-Claim als scope-frei")
    void treatsMissingClaimAsEmpty() {
        // Ein Token nur mit publicData traegt den Claim gar nicht. Leer ist hier
        // eine Aussage - im Unterschied zu "nicht lesbar" weiter unten.
        assertThat(EveTokenScopes.of(token(""))).isEmpty();
        assertThat(EveTokenScopes.carries(token(""), TITLES)).isFalse();
    }

    @Test
    @DisplayName("gibt bei unlesbarem Token \"unbekannt\" zurueck statt \"fehlt\"")
    void reportsUnknownForUnreadableToken() {
        // Der Unterschied entscheidet ueber die Fehlermeldung: "unbekannt" darf
        // niemanden zur Neuanmeldung schicken, "fehlt" schon.
        assertThat(EveTokenScopes.of("kein-jwt")).isNull();
        assertThat(EveTokenScopes.carries("kein-jwt", TITLES)).isNull();
        assertThat(EveTokenScopes.of(null)).isNull();
        assertThat(EveTokenScopes.of("kopf.####.signatur")).isNull();
    }

    @Test
    @DisplayName("beantwortet die Frage nach einem einzelnen Scope")
    void answersForSingleScope() {
        String accessToken = token(",\"scp\":[\"" + ROLES + "\"]");

        assertThat(EveTokenScopes.carries(accessToken, ROLES)).isTrue();
        assertThat(EveTokenScopes.carries(accessToken, TITLES)).isFalse();
    }
}

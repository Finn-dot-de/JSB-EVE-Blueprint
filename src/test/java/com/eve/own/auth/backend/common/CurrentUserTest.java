package com.eve.own.auth.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("Der angemeldete Charakter im Sicherheitskontext")
class CurrentUserTest {

    private static final Long CHARACTER_ID = 95465499L;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    @DisplayName("liefert die Charakter-ID des angemeldeten Nutzers")
    void returnsAuthenticatedCharacterId() {
        authenticateAs(CHARACTER_ID);

        assertThat(CurrentUser.characterId()).isEqualTo(CHARACTER_ID);
        assertThat(CurrentUser.find()).contains(CHARACTER_ID);
    }

    @Test
    @DisplayName("meldet einen leeren Kontext als 'niemand angemeldet'")
    void reportsEmptyContext() {
        assertThat(CurrentUser.find()).isEmpty();
    }

    @Test
    @DisplayName("erkennt den anonymen Nutzer nicht als angemeldet")
    void ignoresAnonymousUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(CurrentUser.find()).isEmpty();
    }

    @Test
    @DisplayName("ignoriert ein Principal, das keine Charakter-ID ist")
    void ignoresForeignPrincipalType() {
        authenticateAs("irgendein Nutzername");

        assertThat(CurrentUser.find()).isEmpty();
    }

    @Test
    @DisplayName("wirft, wenn ein Endpunkt hinter der Anmeldung doch ohne Charakter laeuft")
    void throwsWithoutAuthentication() {
        assertThatThrownBy(CurrentUser::characterId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kein angemeldeter Charakter");
    }
}

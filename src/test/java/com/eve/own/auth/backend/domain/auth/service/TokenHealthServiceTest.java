package com.eve.own.auth.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Der Vermerk "dieser Charakter muss sich neu anmelden".
 *
 * <p>Bis hierhin stand diese Information nur im Log und war danach weg. Aus dem
 * Produktionslog: drei Charaktere mit {@code invalid_grant - Refresh token
 * missing}, aber keine Moeglichkeit zu sagen, welche das sind oder seit wann.</p>
 */
class TokenHealthServiceTest {

    private static final long CHAR = 2_123_933_054L;

    private CharacterRepository characterRepo;
    private TokenHealthService service;

    @BeforeEach
    void setUp() {
        characterRepo = Mockito.mock(CharacterRepository.class);
        service = new TokenHealthService(characterRepo);
    }

    private Character charakter(Instant seit) {
        Character c = new Character();
        c.setId(CHAR);
        c.setName("Rat Izia");
        c.setTokenInvalidSince(seit);
        when(characterRepo.findById(CHAR)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("vermerkt den ersten Fehlschlag mit Zeitpunkt und Grund")
    void ersterFehlschlagWirdVermerkt() {
        Character c = charakter(null);

        service.markInvalid(CHAR, "400 BAD_REQUEST - invalid_grant. Refresh token missing.");

        assertThat(c.getTokenInvalidSince()).isNotNull();
        assertThat(c.getTokenInvalidReason()).contains("invalid_grant");
        verify(characterRepo).save(c);
    }

    @Test
    @DisplayName("lässt den Zeitpunkt des ersten Fehlschlags stehen")
    void zeitpunktRuecktNichtNach() {
        // Ohne das rückte der Zeitpunkt bei jedem Zehn-Minuten-Lauf nach, und
        // "seit wann ist der Charakter draußen" wäre immer "gerade eben" -
        // genau die Angabe, die einen Aussetzer von einem Dauerzustand trennt.
        Instant frueher = Instant.parse("2026-08-01T12:00:00Z");
        Character c = charakter(frueher);

        service.markInvalid(CHAR, "erneut gescheitert");

        assertThat(c.getTokenInvalidSince()).isEqualTo(frueher);
        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("nimmt den Vermerk zurück, sobald der Token wieder geht")
    void erfolgLoeschtDenVermerk() {
        Character c = charakter(Instant.parse("2026-08-01T12:00:00Z"));
        c.setTokenInvalidReason("invalid_grant");

        service.markValid(CHAR);

        assertThat(c.getTokenInvalidSince()).isNull();
        assertThat(c.getTokenInvalidReason()).isNull();
        verify(characterRepo).save(c);
    }

    @Test
    @DisplayName("schreibt nicht, wenn ohnehin alles in Ordnung ist")
    void gesunderCharakterWirdNichtGeschrieben() {
        // Der Normalfall, und er läuft bei jedem Abgleich für jeden Charakter.
        // Ein Schreibvorgang je Charakter und Lauf wäre reine Last ohne Ertrag.
        charakter(null);

        service.markValid(CHAR);

        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("kürzt einen überlangen Grund auf die Spaltenbreite")
    void langerGrundWirdGekuerzt() {
        // Ein ESI-Fehlertext kann eine ganze HTML-Seite sein. Ungekürzt bricht
        // das Speichern - und der Vermerk ginge genau dann verloren, wenn er
        // gebraucht wird.
        Character c = charakter(null);

        service.markInvalid(CHAR, "x".repeat(5_000));

        assertThat(c.getTokenInvalidReason()).hasSizeLessThanOrEqualTo(255);
    }
}

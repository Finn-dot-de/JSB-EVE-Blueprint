package com.eve.own.auth.backend.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die EVE-Token liegen dauerhaft in der Datenbank - wer sie dort liest, darf mit
 * ihnen nichts anfangen koennen.
 */
@DisplayName("Verschluesselung der EVE-Token")
class AesEncryptionServiceTest {

    private AesEncryptionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AesEncryptionService();
        // Der Schluessel kommt sonst aus der Konfiguration.
        Field key = AesEncryptionService.class.getDeclaredField("base64Key");
        key.setAccessible(true);
        key.set(service, Base64.getEncoder().encodeToString(new byte[32]));
        service.init();
    }

    @Test
    @DisplayName("gibt einen verschluesselten Text unveraendert zurueck")
    void roundTrips() {
        String token = "ein-geheimes-access-token";

        assertThat(service.decrypt(service.encrypt(token))).isEqualTo(token);
    }

    @Test
    @DisplayName("verschluesselt denselben Text jedes Mal anders")
    void usesFreshInitialisationVector() {
        // Gleiches Ergebnis bei gleichem Klartext waere ein verraeterisches Muster.
        String token = "ein-geheimes-access-token";

        assertThat(service.encrypt(token)).isNotEqualTo(service.encrypt(token));
    }

    @Test
    @DisplayName("laesst den Klartext nicht im Ergebnis stehen")
    void hidesPlainText() {
        assertThat(service.encrypt("geheim")).doesNotContain("geheim");
    }

    @Test
    @DisplayName("kommt mit Umlauten und Sonderzeichen zurecht")
    void handlesSpecialCharacters() {
        String token = "äöü-ß-€-🚀";

        assertThat(service.decrypt(service.encrypt(token))).isEqualTo(token);
    }

    @Test
    @DisplayName("kommt mit einem leeren Text zurecht")
    void handlesEmptyText() {
        assertThat(service.decrypt(service.encrypt(""))).isEmpty();
    }

    @Test
    @DisplayName("weist unbrauchbare Eingaben ab, statt Muell zurueckzugeben")
    void rejectsGarbage() {
        assertThatThrownBy(() -> service.decrypt("kein gueltiger Geheimtext"))
                .isInstanceOf(RuntimeException.class);
    }
}

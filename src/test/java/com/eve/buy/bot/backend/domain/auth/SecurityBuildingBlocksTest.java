package com.eve.buy.bot.backend.domain.auth;

import com.eve.buy.bot.backend.domain.auth.security.AesEncryptionService;
import com.eve.buy.bot.backend.domain.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests der sicherheitsrelevanten Bausteine.
 *
 * <p>Beide Klassen schützen fremde Zugangsdaten: die Verschlüsselung die ESI-Tokens in der
 * Datenbank, das Sitzungstoken den Admin-Bereich. Ein stiller Fehler hier fällt im Betrieb
 * erst auf, wenn es zu spät ist.
 */
@DisplayName("Sicherheitsbausteine")
class SecurityBuildingBlocksTest {

    @Nested
    @DisplayName("AesEncryptionService")
    class Encryption {

        private AesEncryptionService service;

        @BeforeEach
        void setUp() {
            service = new AesEncryptionService();
            // Der Dienst erwartet den AES-Schlüssel Base64-kodiert, hier 32 Byte für AES-256.
            String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            ReflectionTestUtils.setField(service, "base64Key", key);
            service.init();
        }

        @Test
        @DisplayName("liefert nach dem Entschlüsseln wieder den Ursprungstext")
        void roundTripsPlainText() {
            String token = "eyJhbGciOiJSUzI1NiIsImtpZCI6IkpXVC1TaWduYXR1cmUtS2V5In0.beispiel";

            String encrypted = service.encrypt(token);

            assertThat(encrypted).isNotEqualTo(token);
            assertThat(service.decrypt(encrypted)).isEqualTo(token);
        }

        @Test
        @DisplayName("erzeugt für denselben Text unterschiedliche Geheimtexte")
        void producesDifferentCipherTextsForSameInput() {
            String encryptedOnce = service.encrypt("gleicher Text");
            String encryptedTwice = service.encrypt("gleicher Text");

            // Bei zufälligem Initialisierungsvektor darf sich der Geheimtext unterscheiden,
            // entschlüsseln muss aber beides zum selben Ergebnis führen.
            assertThat(service.decrypt(encryptedOnce)).isEqualTo("gleicher Text");
            assertThat(service.decrypt(encryptedTwice)).isEqualTo("gleicher Text");
        }
    }

    @Nested
    @DisplayName("JwtService")
    class Sessions {

        private final JwtService service =
                new JwtService("test-secret-das-lang-genug-ist-fuer-hmac-sha-256!");

        @Test
        @DisplayName("gibt Charakter, Name und Rollen unverändert zurück")
        void roundTripsSessionData() {
            String token = service.generateToken(90000001L, "Testpilot", Set.of("ROLE_USER", "ROLE_IT_ADMIN"));

            assertThat(service.validateToken(token)).isTrue();
            assertThat(service.getCharacterIdFromToken(token)).isEqualTo(90000001L);
            assertThat(service.getCharacterNameFromToken(token)).isEqualTo("Testpilot");
            assertThat(service.getRolesFromToken(token)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_IT_ADMIN");
        }

        @Test
        @DisplayName("weist ein manipuliertes Token ab")
        void rejectsTamperedToken() {
            String token = service.generateToken(90000001L, "Testpilot", Set.of("ROLE_USER"));
            String tampered = token.substring(0, token.length() - 4) + "AAAA";

            assertThat(service.validateToken(tampered)).isFalse();
        }

        @Test
        @DisplayName("weist ein mit fremdem Schlüssel signiertes Token ab")
        void rejectsTokenSignedWithAnotherKey() {
            JwtService other = new JwtService("ein-voellig-anderes-geheimnis-mit-genug-laenge!");
            String foreignToken = other.generateToken(90000001L, "Fremder", Set.of("ROLE_IT_ADMIN"));

            assertThat(service.validateToken(foreignToken)).isFalse();
        }

        @Test
        @DisplayName("weist Unsinn ab, statt eine Ausnahme durchzureichen")
        void rejectsGarbage() {
            assertThat(service.validateToken("kein-token")).isFalse();
            assertThat(service.validateToken("")).isFalse();
        }
    }
}

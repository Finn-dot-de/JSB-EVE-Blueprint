package com.eve.own.auth.backend.esi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@DisplayName("Der Klartext aus einer Fehlerantwort von ESI")
class EsiErrorTextTest {

    private static HttpClientErrorException forbidden(String body) {
        return HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("holt den Satz aus dem error-Feld")
    void readsErrorField() {
        assertThat(EsiErrorText.of(forbidden(
                "{\"error\":\"The given character doesn't have the required role(s)\"}")))
                .isEqualTo("The given character doesn't have the required role(s)");
    }

    @Test
    @DisplayName("nimmt den Rohtext, wenn die Antwort kein JSON ist")
    void fallsBackToRawBody() {
        // Ein Fehler beim Deuten eines Fehlers darf den Fehler nicht ersetzen -
        // sonst waere die Auskunft wieder weg, genau wie vorher.
        assertThat(EsiErrorText.of(forbidden("  Service Unavailable  ")))
                .isEqualTo("Service Unavailable");
    }

    @Test
    @DisplayName("meldet null, wenn CCP gar nichts mitgeschickt hat")
    void reportsNullForEmptyBody() {
        assertThat(EsiErrorText.of(forbidden(null))).isNull();
        assertThat(EsiErrorText.of(forbidden("   "))).isNull();
        assertThat(EsiErrorText.of(null)).isNull();
    }
}

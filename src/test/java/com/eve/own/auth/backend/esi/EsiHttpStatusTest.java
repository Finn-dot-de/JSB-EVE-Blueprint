package com.eve.own.auth.backend.esi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

@DisplayName("Auswertung der ESI-Statuscodes")
class EsiHttpStatusTest {

    private static RestClientResponseException responseWith(int status) {
        return new RestClientResponseException(
                "ESI meldet " + status, status, "", HttpHeaders.EMPTY, null, null);
    }

    @Test
    @DisplayName("erkennt das aufgebrauchte Fehler-Budget (420)")
    void detectsErrorLimit() {
        assertThat(EsiHttpStatus.isErrorLimited(responseWith(EsiHttpStatus.ERROR_LIMITED))).isTrue();
        assertThat(EsiHttpStatus.isErrorLimited(responseWith(403))).isFalse();
    }

    @Test
    @DisplayName("fasst abgelaufene Token und fehlende Rechte als Auth-Fehler zusammen")
    void detectsAuthFailures() {
        assertThat(EsiHttpStatus.isAuthFailure(responseWith(401))).isTrue();
        assertThat(EsiHttpStatus.isAuthFailure(responseWith(403))).isTrue();
        assertThat(EsiHttpStatus.isAuthFailure(responseWith(404))).isFalse();
        assertThat(EsiHttpStatus.isAuthFailure(responseWith(420))).isFalse();
    }

    @Test
    @DisplayName("unterscheidet 403 und 404 einzeln")
    void detectsForbiddenAndNotFound() {
        assertThat(EsiHttpStatus.isForbidden(responseWith(403))).isTrue();
        assertThat(EsiHttpStatus.isForbidden(responseWith(401))).isFalse();

        assertThat(EsiHttpStatus.isNotFound(responseWith(404))).isTrue();
        assertThat(EsiHttpStatus.isNotFound(responseWith(403))).isFalse();
    }
}

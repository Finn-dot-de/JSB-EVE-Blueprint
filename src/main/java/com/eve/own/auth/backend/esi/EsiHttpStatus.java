package com.eve.own.auth.backend.esi;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

/**
 * Die ESI-Statuscodes, auf die der Code tatsaechlich reagiert.
 *
 * <p>Vor allem 420 verdient eine eigene Erwaehnung: der Code steht in keinem
 * HTTP-Standard und ist deshalb auch nicht in {@link HttpStatus} enthalten. CCP
 * signalisiert damit ein volles Fehler-Budget - jede weitere Anfrage wuerde das
 * Zeitfenster nur verlaengern. Ein 420 muss deshalb ueberall bis zur zentralen
 * Drosselung durchgereicht und darf nie stillschweigend geschluckt werden.</p>
 */
public final class EsiHttpStatus {

    /** ESI: Fehler-Budget aufgebraucht, Aufrufer muss pausieren. */
    public static final int ERROR_LIMITED = 420;

    private EsiHttpStatus() {
        throw new AssertionError("Konstantenhalter, nicht instanziierbar.");
    }

    public static boolean isErrorLimited(RestClientResponseException exception) {
        return exception.getStatusCode().value() == ERROR_LIMITED;
    }

    /** 401/403: Token abgelaufen oder die noetige Ingame-Rolle fehlt. */
    public static boolean isAuthFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value();
    }

    public static boolean isForbidden(RestClientResponseException exception) {
        return exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value();
    }

    public static boolean isNotFound(RestClientResponseException exception) {
        return exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value();
    }
}

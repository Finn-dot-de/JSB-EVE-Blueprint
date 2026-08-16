package com.eve.buy.bot.backend.config;

import com.eve.buy.bot.backend.audit.AuditCategory;
import com.eve.buy.bot.backend.audit.AuditContext;
import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.audit.AuditSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Tests der zentralen Fehlerbehandlung.
 *
 * <p>Zwei Dinge müssen stimmen: der Client darf keine internen Einzelheiten sehen, und die
 * Antwort muss die Aufruf-ID enthalten - sonst ist ein von einem nicht angemeldeten Spieler
 * gemeldeter Fehler nicht wiederzufinden.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    @Mock private AuditService auditService;

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(auditService);
        request = new MockHttpServletRequest("POST", "/api/buybot/calculate");
        AuditContext.start("abc12345", "203.0.113.42", "JUnit", "POST", "/api/buybot/calculate");
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    @DisplayName("gibt bei unerwarteten Fehlern die Aufruf-ID mit, aber keine Einzelheiten")
    void hidesDetailsButExposesRequestId() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleUnexpected(new NullPointerException("intern: session null"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().requestId()).isEqualTo("abc12345");
        assertThat(response.getBody().error()).doesNotContain("session null");
        assertThat(response.getBody().error()).contains("Fehler-ID");
    }

    @Test
    @DisplayName("schreibt unerwartete Fehler mit Ausnahmetyp und Codestelle ins Protokoll")
    void recordsExceptionDetailsInAuditLog() {
        handler.handleUnexpected(new IllegalStateException("Konfiguration fehlt"), request);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditCategory.ERROR), eq(AuditSeverity.ERROR), anyString(), details.capture());
        assertThat(details.getValue()).contains("IllegalStateException").contains("Konfiguration fehlt");
    }

    @Test
    @DisplayName("meldet fehlerhafte Eingaben als 400 statt als Serverfehler")
    void mapsBadInputToBadRequest() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleBadRequest(new IllegalArgumentException("Unbekannter Abgabeort: 99"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().requestId()).isEqualTo("abc12345");
        verify(auditService).record(eq(AuditCategory.REQUEST), eq(AuditSeverity.WARN), anyString(), any());
    }

    @Test
    @DisplayName("bucht verweigerte Zugriffe unter Sicherheit")
    void mapsAccessDeniedToSecurity() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAccessDenied(new AccessDeniedException("kein Zugriff"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditService).record(eq(AuditCategory.SECURITY), eq(AuditSeverity.WARN), anyString(), any());
    }

    @Test
    @DisplayName("behält den Status bewusst gesetzter Fehler bei")
    void keepsStatusOfDeliberateErrors() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Item nicht gefunden"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("Item nicht gefunden");
    }

    @Test
    @DisplayName("liefert auch ohne Aufrufkontext eine gültige Antwort")
    void worksWithoutRequestContext() {
        AuditContext.clear();

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleUnexpected(new RuntimeException("egal"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().requestId()).isNull();
    }
}

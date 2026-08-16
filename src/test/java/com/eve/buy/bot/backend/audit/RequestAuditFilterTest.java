package com.eve.buy.bot.backend.audit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests des Protokoll-Filters.
 *
 * <p>Die meisten Nutzer sind nicht angemeldet. Ob ein gemeldeter Fehler später auffindbar
 * ist, hängt allein daran, dass hier IP-Adresse und Aufruf-ID korrekt erfasst werden.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequestAuditFilter")
class RequestAuditFilterTest {

    @Mock private AuditService auditService;
    @Mock private FilterChain filterChain;

    private RequestAuditFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new RequestAuditFilter(auditService);
        ReflectionTestUtils.setField(filter, "logSuccessfulReads", false);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setRemoteAddr("172.18.0.1");
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    @DisplayName("nimmt die Adresse aus X-Forwarded-For, weil ein Reverse-Proxy davorsteht")
    void prefersForwardedHeaderOverProxyAddress() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/buybot/calculate");
        request.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1");

        filter.doFilter(request, response, (req, res) ->
                assertThat(AuditContext.current().getClientIp()).isEqualTo("203.0.113.42"));

        verify(auditService).record(eq(AuditCategory.REQUEST), eq(AuditSeverity.INFO), anyString(), any(), eq(200), any());
    }

    @Test
    @DisplayName("nutzt die direkte Adresse, wenn kein Proxy-Header gesetzt ist")
    void fallsBackToRemoteAddress() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/buybot/calculate");

        filter.doFilter(request, response, (req, res) ->
                assertThat(AuditContext.current().getClientIp()).isEqualTo("172.18.0.1"));
    }

    @Test
    @DisplayName("gibt die Aufruf-ID im Antwort-Header zurück, damit der Nutzer sie nennen kann")
    void exposesRequestIdInResponseHeader() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/buybot/calculate");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader(RequestAuditFilter.REQUEST_ID_HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("räumt den Kontext am Ende wieder ab")
    void clearsContextAfterRequest() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/buybot/config");

        filter.doFilter(request, response, filterChain);

        assertThat(AuditContext.current()).isNull();
    }

    @Test
    @DisplayName("protokolliert erfolgreiche Lesezugriffe nicht, solange das abgeschaltet ist")
    void skipsSuccessfulReadsByDefault() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/buybot/config");

        filter.doFilter(request, response, filterChain);

        verify(auditService, never()).record(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("protokolliert fehlgeschlagene Lesezugriffe immer")
    void alwaysLogsFailedReads() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/buybot/config");

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(500));

        verify(auditService).record(eq(AuditCategory.REQUEST), eq(AuditSeverity.ERROR), anyString(), any(), eq(500), any());
    }

    @Test
    @DisplayName("bucht abgelehnte Zugriffe unter Sicherheit statt unter Aufrufen")
    void classifiesDeniedAccessAsSecurity() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/admin/buybot/config");

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(403));

        verify(auditService).record(eq(AuditCategory.SECURITY), eq(AuditSeverity.WARN), anyString(), any(), eq(403), any());
    }

    @Test
    @DisplayName("lässt Aufrufe außerhalb der API unprotokolliert")
    void ignoresNonApiPaths() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/index.html");

        filter.doFilter(request, response, filterChain);

        verify(auditService, never()).record(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("misst die Dauer des Aufrufs")
    void measuresDuration() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/buybot/calculate");

        filter.doFilter(request, response, (req, res) -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ArgumentCaptor<Long> duration = ArgumentCaptor.forClass(Long.class);
        verify(auditService).record(any(), any(), anyString(), any(), any(), duration.capture());
        assertThat(duration.getValue()).isGreaterThanOrEqualTo(5L);
    }
}

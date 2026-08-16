package com.eve.buy.bot.backend.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Erfasst jeden API-Aufruf: Aufruf-ID, IP-Adresse, Client, Dauer und Ergebnis.
 *
 * <p>Läuft ganz vorne in der Filterkette, damit auch abgelehnte Anfragen erfasst werden.
 * Die Aufruf-ID landet zusätzlich im {@link MDC} und erscheint dadurch in jeder Logzeile
 * dieses Requests - ein Fehler lässt sich so vom Log bis zum Protokolleintrag verfolgen.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestAuditFilter extends OncePerRequestFilter {

    /** Antwort-Header, über den das Frontend die Aufruf-ID erfährt. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_CLIENT_IP = "clientIp";
    private static final String MDC_ACTOR = "actor";

    private static final String API_PREFIX = "/api/";
    private static final int MAX_USER_AGENT_LENGTH = 300;

    private final AuditService auditService;

    /** Ob auch erfolgreiche Lesezugriffe protokolliert werden. */
    @Value("${buybot.audit.log-successful-reads:false}")
    private boolean logSuccessfulReads;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String clientIp = resolveClientIp(request);

        AuditContext.start(requestId, clientIp, userAgent(request), request.getMethod(), request.getRequestURI());
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CLIENT_IP, clientIp);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startedAt;
            try {
                recordRequest(request, response.getStatus(), duration);
            } finally {
                AuditContext.clear();
                MDC.remove(MDC_REQUEST_ID);
                MDC.remove(MDC_CLIENT_IP);
                MDC.remove(MDC_ACTOR);
            }
        }
    }

    /**
     * Schreibt den Protokolleintrag für den Aufruf, sofern er protokolliert werden soll.
     *
     * <p>Fehler werden immer erfasst, ebenso alle schreibenden Zugriffe. Erfolgreiche
     * Lesezugriffe nur, wenn das ausdrücklich eingeschaltet ist - sonst besteht das
     * Protokoll fast ausschließlich aus Abrufen der Konfiguration.
     *
     * @param request    der bearbeitete Aufruf
     * @param status     der HTTP-Status der Antwort
     * @param durationMs die Bearbeitungsdauer
     */
    private void recordRequest(HttpServletRequest request, int status, long durationMs) {
        String path = request.getRequestURI();
        if (!path.startsWith(API_PREFIX)) {
            return;
        }

        boolean isRead = "GET".equalsIgnoreCase(request.getMethod());
        boolean isFailure = status >= 400;
        if (isRead && !isFailure && !logSuccessfulReads) {
            return;
        }

        AuditSeverity severity;
        if (status >= 500) {
            severity = AuditSeverity.ERROR;
        } else if (status >= 400) {
            severity = AuditSeverity.WARN;
        } else {
            severity = AuditSeverity.INFO;
        }

        AuditCategory category = status == 401 || status == 403 ? AuditCategory.SECURITY : AuditCategory.REQUEST;
        String message = "%s %s -> %d".formatted(request.getMethod(), path, status);
        auditService.record(category, severity, message, null, status, durationMs);
    }

    /**
     * Ermittelt die IP-Adresse des Aufrufers.
     *
     * <p>Im Betrieb steht ein Reverse-Proxy davor, deshalb hat {@code X-Forwarded-For}
     * Vorrang; der erste Eintrag darin ist der ursprüngliche Client.
     *
     * @param request der Aufruf
     * @return die IP-Adresse des Aufrufers
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Liest den User-Agent und kürzt ihn auf die Spaltenbreite.
     *
     * @param request der Aufruf
     * @return der gekürzte User-Agent oder {@code null}
     */
    private String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        return agent.length() <= MAX_USER_AGENT_LENGTH ? agent : agent.substring(0, MAX_USER_AGENT_LENGTH);
    }
}

package com.eve.buy.bot.backend.config;

import com.eve.buy.bot.backend.audit.AuditContext;
import com.eve.buy.bot.backend.domain.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Meldet Aufrufe anhand des Sitzungscookies an.
 *
 * <p>Der Buybot ist bewusst auch ohne Anmeldung nutzbar: fehlt das Cookie, läuft der Aufruf
 * einfach ohne Authentifizierung weiter und wird als anonym protokolliert.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Name des Cookies, in dem das Sitzungstoken liegt. */
    private static final String SESSION_COOKIE = "toky";

    private static final String MDC_ACTOR = "actor";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = readSessionCookie(request);

        if (token != null && jwtService.validateToken(token)) {
            Long characterId = jwtService.getCharacterIdFromToken(token);
            String characterName = jwtService.getCharacterNameFromToken(token);
            Set<String> roles = jwtService.getRolesFromToken(token);

            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(characterId, null, authorities));

            // Für das Protokoll und alle Logzeilen dieses Aufrufs festhalten, wer handelt.
            AuditContext.setActor(characterId, characterName);
            MDC.put(MDC_ACTOR, characterName != null ? characterName : String.valueOf(characterId));
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Sucht das Sitzungstoken in den Cookies des Aufrufs.
     *
     * @param request der Aufruf
     * @return das Token oder {@code null}, wenn kein Sitzungscookie gesetzt ist
     */
    private String readSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

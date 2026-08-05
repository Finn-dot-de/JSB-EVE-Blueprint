package com.eve.own.auth.backend.config;

import com.eve.own.auth.backend.domain.auth.security.SessionCookie;
import com.eve.own.auth.backend.domain.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Uebersetzt das Sitzungs-Cookie in einen Sicherheitskontext.
 *
 * <p>Das Principal ist die Charakter-ID des Main-Charakters; die Rollen stehen
 * als Authorities daran. Ohne oder mit ungueltigem Cookie laeuft die Anfrage
 * unauthentifiziert weiter - ueber den Zugriff entscheidet dann die
 * Sicherheitskonfiguration.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        readSessionToken(request)
                .filter(jwtService::validateToken)
                .ifPresent(this::authenticate);

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        List<SimpleGrantedAuthority> authorities = jwtService.getRolesFromToken(token).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        jwtService.getCharacterIdFromToken(token), null, authorities));
    }

    private static Optional<String> readSessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> SessionCookie.NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}

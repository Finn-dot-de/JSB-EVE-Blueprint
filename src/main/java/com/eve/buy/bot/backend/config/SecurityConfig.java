package com.eve.buy.bot.backend.config;

import org.springframework.beans.factory.annotation.Value; // NEU
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Sicherheitsregeln der Anwendung.
 *
 * <p>Der Preisrechner ist bewusst offen: Spieler sollen ihn ohne Anmeldung nutzen koennen.
 * Alles unter {@code /api/admin} verlangt dagegen eine Admin-Rolle. Angemeldet wird ueber
 * ein signiertes Sitzungscookie, das nach dem EVE-SSO-Login ausgestellt wird.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final String frontendUrl;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter,
                          @Value("${app.frontend.url}") String frontendUrl) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Legt fest, welche Rolle welche andere einschliesst.
     *
     * @return die Rangfolge der Rollen
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
            ROLE_IT_ADMIN > ROLE_CEO
            ROLE_CEO > ROLE_DIRECTOR
            ROLE_DIRECTOR > ROLE_MANAGER
            ROLE_MANAGER > ROLE_SENIOR_MEMBER
            ROLE_SENIOR_MEMBER > ROLE_MEMBER
            ROLE_MEMBER > ROLE_JUNIOR_MEMBER
            ROLE_JUNIOR_MEMBER > ROLE_USER
            """);
    }

    /**
     * Definiert, welche Pfade offen sind und welche eine Anmeldung verlangen.
     *
     * @param http die zu konfigurierende Kette
     * @return die fertige Filterkette
     * @throws Exception wenn die Konfiguration fehlschlaegt
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/callback", "/api/auth/me",
                                "/api/buybot/locations", "/api/buybot/calculate", "/api/buybot/config",
                                "/api/buybot/injector-price").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Erlaubt dem Frontend den Zugriff auf die API.
     *
     * <p>Zugelassen ist die konfigurierte Adresse der Anwendung, dazu localhost
     * fuer die Entwicklung.
     *
     * @return die CORS-Einstellungen
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(frontendUrl, "http://localhost:4200", "http://localhost"));
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }


}
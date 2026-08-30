package com.eve.own.auth.backend.domain.character.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Meldet die Stellschrauben der Alt-Erkennung bei Spring an.
 *
 * <p>Dieselbe Bauart wie {@code MarketConfig}: eine eigene kleine
 * Konfigurationsklasse statt {@code @ConfigurationPropertiesScan} an der
 * Anwendung, damit sichtbar bleibt, welcher Fachbereich welche Eigenschaften
 * mitbringt.</p>
 */
@Configuration
@EnableConfigurationProperties(AltDetectionProperties.class)
public class AltDetectionConfig {
}

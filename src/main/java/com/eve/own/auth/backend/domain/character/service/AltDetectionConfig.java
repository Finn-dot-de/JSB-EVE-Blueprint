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
 *
 * <p>Zwei Klassen, weil zwei verschiedene Fragen: {@link AltDetectionProperties}
 * regelt, wie <em>bewertet</em> wird, {@link AltSourceProperties} regelt, was
 * ueberhaupt <em>erhoben</em> wird. Der zweite Satz Schalter muss auch dann noch
 * greifen, wenn die Bewertung gar nicht laeuft.</p>
 */
@Configuration
@EnableConfigurationProperties({AltDetectionProperties.class, AltSourceProperties.class})
public class AltDetectionConfig {
}

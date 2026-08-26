package com.eve.own.auth.backend.domain.market;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Meldet die Stellschrauben des Marktabzugs bei Spring an. */
@Configuration
@EnableConfigurationProperties(MarketOrderProperties.class)
public class MarketConfig {
}

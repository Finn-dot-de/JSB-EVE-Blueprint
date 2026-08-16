package com.eve.own.auth.backend.esi;

import com.eve.own.auth.backend.esi.etag.EsiEtagProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(EsiEtagProperties.class)
public class EsiConfig {

    @Value("${eve.esi.base-url}")
    private String baseUrl;

    @Value("${eve.esi.user-agent:EveOwnAuth-Tool (deine@email.com)}")
    private String userAgent;

    /**
     * Bewusst ohne {@code Cache-Control: no-cache}: dieser Header wuerde
     * vorgelagerte Caches zwingen, jede Anfrage bis zu ESI durchzureichen und
     * damit den Sinn der konditionalen Requests untergraben. Die Aktualitaet
     * regeln ETag und Expires.
     */
    @Bean
    public RestClient esiClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .build();
    }
}

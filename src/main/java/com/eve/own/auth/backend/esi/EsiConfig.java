package com.eve.own.auth.backend.esi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EsiConfig {

    @Value("${eve.esi.base-url}")
    private String baseUrl;

    @Bean
    public RestClient esiClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "EveOwnAuth-Tool (deine@email.com)")
                .build();
    }
}
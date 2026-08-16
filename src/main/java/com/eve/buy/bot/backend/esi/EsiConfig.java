package com.eve.buy.bot.backend.esi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Baut den HTTP-Client fuer ESI mit Basis-URL und User-Agent. */
@Configuration
public class EsiConfig {

    @Value("${eve.esi.base-url}")
    private String baseUrl;

    /**
     * Baut den ESI-Client.
     *
     * <p>CCP verlangt einen aussagekraeftigen User-Agent, um bei Problemen den
     * Betreiber ansprechen zu koennen.
     *
     * @return der vorkonfigurierte HTTP-Client
     */
    @Bean
    public RestClient esiClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "EveOwnAuth-Tool (deine@email.com)")
                .build();
    }
}
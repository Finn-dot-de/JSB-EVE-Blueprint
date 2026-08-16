package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.buybot.dto.MarketPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holt die Jita-Marktpreise.
 *
 * <p>Die Kurse kommen gebuendelt von Fuzzwork statt einzeln von ESI - das ist eine Abfrage
 * statt hunderter. Der Preis des Skill Injectors wird zusaetzlich kurz zwischengespeichert,
 * weil ihn jeder Seitenaufruf braucht.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {

    private final RestClient restClient;
    private static final int JITA_STATION_ID = 60003760;

    /** Large Skill Injector - die Referenzwährung für "was ist das eigentlich wert?". */
    public static final long LARGE_SKILL_INJECTOR_TYPE_ID = 40520L;
    private static final Duration INJECTOR_CACHE_TTL = Duration.ofMinutes(5);

    private volatile double cachedInjectorPrice = 0.0;
    private volatile Instant injectorPriceFetchedAt = Instant.EPOCH;

    /**
     * Holt die Jita-Preise fuer die angegebenen Item-Typen.
     *
     * <p>Die Anfrage wird in Bloecke geteilt, weil die URL sonst zu lang wird.
     * Faellt ein Block aus, fehlen nur dessen Preise statt der ganzen Berechnung.
     *
     * @param typeIds die gesuchten Type-IDs
     * @return die Preise je Type-ID
     */
    public Map<Long, MarketPriceDto> getJitaPrices(Set<Long> typeIds) {
        Map<Long, MarketPriceDto> result = new HashMap<>();
        List<Long> idList = new ArrayList<>(typeIds);
        int chunkSize = 200;

        for (int i = 0; i < idList.size(); i += chunkSize) {
            List<Long> chunk = idList.subList(i, Math.min(i + chunkSize, idList.size()));
            String joinedIds = String.join(",", chunk.stream().map(String::valueOf).toList());

            String url = String.format("https://market.fuzzwork.co.uk/aggregates/?station=%d&types=%s", JITA_STATION_ID, joinedIds);

            try {
                Map<String, Map<String, Map<String, String>>> response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});

                if (response != null) {
                    response.forEach((idStr, node) -> {
                        result.put(Long.parseLong(idStr), MarketPriceDto.fromFuzzworkNode(node));
                    });
                }
            } catch (Exception e) {
                log.error("Fehler bei der Fuzzwork-Abfrage für IDs {}: {}", joinedIds, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Jita-Sell des Large Skill Injectors - also das, was ein Injector aktuell kostet.
     * Wird kurz gecached, damit nicht jeder Seitenaufruf eine Marktabfrage auslöst.
     * Gibt 0 zurück, wenn noch nie ein Preis geholt werden konnte.
     */
    public double getSkillInjectorPrice() {
        if (Duration.between(injectorPriceFetchedAt, Instant.now()).compareTo(INJECTOR_CACHE_TTL) < 0) {
            return cachedInjectorPrice;
        }

        MarketPriceDto price = getJitaPrices(Set.of(LARGE_SKILL_INJECTOR_TYPE_ID))
                .get(LARGE_SKILL_INJECTOR_TYPE_ID);

        if (price != null && price.getSellMin() > 0) {
            cachedInjectorPrice = price.getSellMin();
            injectorPriceFetchedAt = Instant.now();
        } else {
            // Markt nicht erreichbar: den letzten bekannten Preis behalten, aber bald neu versuchen
            log.warn("Kein Jita-Preis für den Large Skill Injector erhalten.");
            injectorPriceFetchedAt = Instant.now().minus(INJECTOR_CACHE_TTL).plus(Duration.ofSeconds(30));
        }
        return cachedInjectorPrice;
    }
}
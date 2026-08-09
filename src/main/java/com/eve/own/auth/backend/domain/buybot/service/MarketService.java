package com.eve.own.auth.backend.domain.buybot.service;

import com.eve.own.auth.backend.domain.buybot.dto.MarketPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {

    private final RestClient restClient;
    private static final int JITA_STATION_ID = 60003760;

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
}
package com.eve.own.auth.backend.domain.buybot.dto;

import lombok.Data;
import java.util.Map;

@Data
public class MarketPriceDto {
    private double buyMax;
    private double sellMin;

    // Fuzzwork liefert ein verschachteltes JSON: {"buy": {"max": "100.0"}, "sell": {"min": "105.0"}}
    public static MarketPriceDto fromFuzzworkNode(Map<String, Map<String, String>> fuzzworkNode) {
        MarketPriceDto dto = new MarketPriceDto();
        try {
            dto.setBuyMax(Double.parseDouble(fuzzworkNode.get("buy").get("max")));
            dto.setSellMin(Double.parseDouble(fuzzworkNode.get("sell").get("min")));
        } catch (Exception e) {
            dto.setBuyMax(0.0);
            dto.setSellMin(0.0);
        }
        return dto;
        
    }
}
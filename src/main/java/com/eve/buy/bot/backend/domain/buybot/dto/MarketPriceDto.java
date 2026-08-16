package com.eve.buy.bot.backend.domain.buybot.dto;

import lombok.Data;
import java.util.Map;

/** Hoechstes Kaufgebot und niedrigster Verkaufspreis eines Items in Jita. */
@Data
public class MarketPriceDto {
    private double buyMax;
    private double sellMin;

    // Fuzzwork liefert ein verschachteltes JSON: {"buy": {"max": "100.0"}, "sell": {"min": "105.0"}}

    /**
     * Liest einen Preisknoten der Fuzzwork-Antwort.
     *
     * <p>Fehlt ein Wert oder ist er unlesbar, wird 0 angenommen; das Item faellt
     * dann im Ergebnis als wertlos auf statt die ganze Berechnung abzubrechen.
     *
     * @param fuzzworkNode der verschachtelte Knoten aus der Antwort
     * @return die gelesenen Preise
     */
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
package com.eve.own.auth.backend.domain.buybot.service;

import com.eve.own.auth.backend.domain.buybot.dto.MarketPriceDto;
import com.eve.own.auth.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackCategoryRule;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackConfig;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackLocation;
import com.eve.own.auth.backend.domain.buybot.entity.BuybackTypeRule;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackCategoryRuleRepository;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.own.auth.backend.domain.buybot.repository.BuybackTypeRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BuybackCalculationService {

    private final MarketService marketService;
    private final BuybackConfigRepository configRepo;
    private final BuybackLocationRepository locationRepo;
    private final BuybackTypeRuleRepository typeRuleRepo;
    private final BuybackCategoryRuleRepository categoryRuleRepo;

    public void calculatePrices(List<ParsedItemDto> items, Long locationId) {
        BuybackConfig config = configRepo.findById(1L).orElseThrow(() -> new RuntimeException("Config fehlt!"));
        BuybackLocation location = locationRepo.findById(locationId).orElseThrow(() -> new RuntimeException("Location nicht gefunden!"));

        // 1. Alle nötigen Jita-Preise holen
        Set<Long> validTypeIds = items.stream()
                .filter(ParsedItemDto::isResolved)
                .map(ParsedItemDto::getTypeId)
                .collect(Collectors.toSet());

        Map<Long, MarketPriceDto> prices = marketService.getJitaPrices(validTypeIds);

        // 2. Berechnung für jedes Item
        for (ParsedItemDto item : items) {
            if (!item.isResolved()) {
                item.setStatus("NICHT GEFUNDEN");
                item.setTotalPrice(0.0);
                continue;
            }

            Optional<BuybackTypeRule> typeRuleOpt = typeRuleRepo.findById(item.getTypeId());

            // Blacklist-Check
            if (typeRuleOpt.isPresent() && Boolean.TRUE.equals(typeRuleOpt.get().getIsBlacklisted())) {
                item.setStatus("GESPERRT (ITEM)");
                item.setTotalPrice(0.0);
                continue;
            }

            Optional<BuybackCategoryRule> categoryRuleOpt = categoryRuleRepo.findById(item.getCategoryId());

            // Whitelist-Check (Muss auf Item- oder Kategorie-Ebene erlaubt sein)
            if (typeRuleOpt.isEmpty() && categoryRuleOpt.isEmpty()) {
                item.setStatus("NICHT GELISTET");
                item.setTotalPrice(0.0);
                continue;
            }

            // Modifikator bestimmen (Strenge Hierarchie: Item > Kategorie > Global)
            double modPercent = config.getGlobalModifier();
            if (categoryRuleOpt.isPresent() && categoryRuleOpt.get().getModifier() != null) {
                modPercent = categoryRuleOpt.get().getModifier();
            }
            if (typeRuleOpt.isPresent() && typeRuleOpt.get().getModifier() != null) {
                modPercent = typeRuleOpt.get().getModifier();
            }

            MarketPriceDto priceData = prices.get(item.getTypeId());
            double jitaSell = (priceData != null) ? priceData.getSellMin() : 0.0;
            double jitaBuy = (priceData != null) ? priceData.getBuyMax() : 0.0;

            double basisPrice = "sell".equalsIgnoreCase(config.getPriceBasis()) ? jitaSell : jitaBuy;

            double unitBase = basisPrice * (modPercent / 100.0);
            double unitTransport = item.getVolumeEach() * location.getTransportFee();
            double unitSecurity = jitaSell * (location.getSecurityFee() / 100.0);

            double unitTotal = unitBase - unitTransport - unitSecurity;
            if (unitTotal < 0) {
                unitTotal = 0.0;
            }

            item.setTotalPrice(unitTotal * item.getQuantity());
            item.setAppliedModifier(modPercent);
            item.setStatus("OK");
        }
    }
}
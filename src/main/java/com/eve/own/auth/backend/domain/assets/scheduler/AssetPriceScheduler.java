package com.eve.own.auth.backend.domain.assets.scheduler;

import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import com.eve.own.auth.backend.domain.assets.repository.MarketPriceRepository;
import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Haelt Preise und Standortnamen fuer die Asset-Auswertung aktuell.
 *
 * Preise kommen von Fuzzwork (Jita 4-4, Station 60003760) - dieselbe Quelle,
 * die schon fuer die Mining-Steuern genutzt wird.
 */
@Slf4j
@Component
public class AssetPriceScheduler {

    /** Fuzzwork vertraegt grosse Listen, aber URLs nicht beliebig lang. */
    private static final int BATCH_SIZE = 200;

    private final CharacterAssetRepository assetRepo;
    private final MarketPriceRepository priceRepo;
    private final AssetLocationService locationService;
    private final EsiService esiService;

    public AssetPriceScheduler(CharacterAssetRepository assetRepo,
                               MarketPriceRepository priceRepo,
                               AssetLocationService locationService,
                               EsiService esiService) {
        this.assetRepo = assetRepo;
        this.priceRepo = priceRepo;
        this.locationService = locationService;
        this.esiService = esiService;
    }

    /** Stuendlich: Jita-Preise fuer alle Typen, die irgendwo im Hangar liegen. */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 120_000)
    public void updateAssetPrices() {
        List<Long> typeIds = assetRepo.findDistinctAssetTypeIds();
        if (typeIds.isEmpty()) {
            log.info("Keine Assets vorhanden - Preis-Update uebersprungen.");
            return;
        }

        log.info("Aktualisiere Jita-Preise fuer {} Asset-Typen...", typeIds.size());
        List<MarketPrice> toSave = new ArrayList<>();
        int failedBatches = 0;

        for (int i = 0; i < typeIds.size(); i += BATCH_SIZE) {
            List<Long> batch = typeIds.subList(i, Math.min(i + BATCH_SIZE, typeIds.size()));
            try {
                Map<String, EsiService.FuzzworkPrice> prices = esiService.getFuzzworkPrices(batch);
                if (prices == null || prices.isEmpty()) {
                    failedBatches++;
                    continue;
                }
                for (Long typeId : batch) {
                    var data = prices.get(String.valueOf(typeId));
                    if (data == null) continue;

                    MarketPrice price = priceRepo.findById(typeId).orElseGet(MarketPrice::new);
                    price.setTypeId(typeId);
                    price.setJitaBuy(data.buy() != null ? data.buy().max() : null);
                    price.setJitaSell(data.sell() != null ? data.sell().min() : null);
                    price.setUpdatedAt(Instant.now());
                    toSave.add(price);
                }
            } catch (Exception e) {
                failedBatches++;
                log.warn("Preis-Batch ab Index {} fehlgeschlagen: {}", i, e.getMessage());
            }
        }

        priceRepo.saveAll(toSave);
        log.info("Jita-Preise aktualisiert: {} Typen gespeichert, {} Batches fehlgeschlagen.",
                toSave.size(), failedBatches);
    }

    /** Alle 30 Minuten: Namen fuer neu aufgetauchte Stationen / Strukturen nachziehen. */
    @Scheduled(fixedRate = 1_800_000, initialDelay = 180_000)
    public void updateAssetLocations() {
        try {
            locationService.resolvePendingLocations();
        } catch (Exception e) {
            log.error("Standort-Aufloesung fehlgeschlagen: {}", e.getMessage());
        }
    }
}

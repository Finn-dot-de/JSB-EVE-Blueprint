package com.eve.own.auth.backend.domain.assets.scheduler;

import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import com.eve.own.auth.backend.domain.assets.repository.MarketPriceRepository;
import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.market.MarketSnapshot;
import com.eve.own.auth.backend.domain.market.StationPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Haelt Preise und Standortnamen fuer die Asset-Auswertung aktuell.
 *
 * <p>Die Preise holt diese Klasse nicht mehr selbst. Sie bekommt den fertigen
 * Marktabzug von
 * {@link com.eve.own.auth.backend.domain.market.MarketPriceScheduler}
 * gereicht - denn derselbe Abzug bedient auch den Industrie-Preislauf und die
 * Mining-Steuersaetze. Frueher hatte jeder seinen eigenen Zeitgeber und seinen
 * eigenen Weg ans Netz; ueber den Regionsabzug waeren daraus dreimal 411
 * Seiten geworden.</p>
 */
@Slf4j
@Component
public class AssetPriceScheduler {

    private final CharacterAssetRepository assetRepo;
    private final MarketPriceRepository priceRepo;
    private final AssetLocationService locationService;

    public AssetPriceScheduler(CharacterAssetRepository assetRepo,
                               MarketPriceRepository priceRepo,
                               AssetLocationService locationService) {
        this.assetRepo = assetRepo;
        this.priceRepo = priceRepo;
        this.locationService = locationService;
    }

    /**
     * Schreibt die Jita-Preise fuer alle Typen, die irgendwo im Hangar liegen.
     *
     * <p>Der Abzug ist zu diesem Zeitpunkt bereits vollstaendig - ein
     * abgebrochener Durchlauf kommt hier gar nicht erst an. Was noch fehlen
     * kann, ist der einzelne Typ: gemessen haben 488 von 17.373 gehandelten
     * Typen in Jita 4-4 keine Verkaufsorder. Fuer die wird nichts geschrieben,
     * schon gar keine 0.</p>
     *
     * @return wie viele Typen einen brauchbaren Preis bekommen haben
     */
    public int updateAssetPrices(MarketSnapshot abzug) {
        List<Long> typeIds = assetRepo.findDistinctAssetTypeIds();
        if (typeIds.isEmpty()) {
            log.info("Keine Assets vorhanden - Preis-Update uebersprungen.");
            return 0;
        }

        List<MarketPrice> toSave = new ArrayList<>();
        int ohneOrder = 0;
        Instant jetzt = Instant.now();

        for (Long typeId : typeIds) {
            StationPrice preis = abzug.price(typeId);
            if (preis == null) {
                // Kein Angebot an der Station. Der alte Wert bleibt stehen -
                // eine Zeile mit 0 waere schlimmer als gar keine, weil sie den
                // Bestand wertlos und den Kauf kostenlos rechnet.
                ohneOrder++;
                continue;
            }
            MarketPrice zeile = priceRepo.findById(typeId).orElseGet(MarketPrice::new);
            zeile.setTypeId(typeId);
            // Je Seite einzeln: dass niemand kauft, heisst nicht, dass auch
            // niemand verkauft. Die fehlende Seite behaelt ihren alten Wert.
            zeile.setJitaBuy(preis.buy() != null ? preis.buy() : zeile.getJitaBuy());
            zeile.setJitaSell(preis.sell() != null ? preis.sell() : zeile.getJitaSell());
            zeile.setUpdatedAt(jetzt);
            toSave.add(zeile);
        }

        priceRepo.saveAll(toSave);
        // Gezaehlt werden Typen mit brauchbarem Preis, nicht Zeilen im Ergebnis.
        log.info("Asset-Preise: {} von {} Typen mit brauchbarem Jita-Preis, {} ohne Order an der Station.",
                toSave.size(), typeIds.size(), ohneOrder);
        return toSave.size();
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

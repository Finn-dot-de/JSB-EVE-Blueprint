package com.eve.own.auth.backend.domain.character.scheduler;

import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.service.CorporationAssetSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stoesst das Spiegeln der Corp-Hangars an. */
@Slf4j
@Component
public class CorporationAssetScheduler {

    /**
     * Stuendlich, und damit bewusst seltener als der Charakter-Sync: Corp-Bestaende
     * aendern sich traeger, und der Endpunkt ist bei grossen Corporations teuer -
     * er liefert viele Seiten.
     *
     * <p>Als Literal, weil {@code @Scheduled} einen Konstantenausdruck verlangt.</p>
     */
    private static final long INTERVAL_MS = 60 * 60 * 1000L;

    private final CorporationScope corporationScope;
    private final CorporationAssetSyncService corporationAssetSyncService;

    public CorporationAssetScheduler(CorporationScope corporationScope,
                                     CorporationAssetSyncService corporationAssetSyncService) {
        this.corporationScope = corporationScope;
        this.corporationAssetSyncService = corporationAssetSyncService;
    }

    @Scheduled(fixedRate = INTERVAL_MS)
    public void syncCorporationAssets() {
        log.info("Starte Corp-Asset-Sync...");
        for (Long corporationId : corporationScope.allowedCorporationIds()) {
            try {
                corporationAssetSyncService.sync(corporationId);
            } catch (Exception e) {
                log.error("Corp-Asset-Sync fuer Corporation {} fehlgeschlagen: {}",
                        corporationId, e.getMessage());
            }
        }
        log.info("Corp-Asset-Sync abgeschlossen.");
    }
}

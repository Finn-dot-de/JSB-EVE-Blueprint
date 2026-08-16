package com.eve.own.auth.backend.domain.mining.scheduler;

import com.eve.own.auth.backend.domain.mining.service.MiningPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stoesst den Abgleich der Jita-Referenzpreise an. */
@Slf4j
@Component
public class MiningPriceScheduler {

    /**
     * Stuendlich. Die Marktdaten stammen von einem Fremdanbieter, der seine
     * Aggregate ohnehin nicht haeufiger fortschreibt.
     *
     * <p>Als Literal, weil {@code @Scheduled} einen Konstantenausdruck verlangt.</p>
     */
    private static final long INTERVAL_MS = 60 * 60 * 1000L;

    private final MiningPriceService miningPriceService;

    public MiningPriceScheduler(MiningPriceService miningPriceService) {
        this.miningPriceService = miningPriceService;
    }

    @Scheduled(fixedRate = INTERVAL_MS)
    public void refreshJitaPrices() {
        try {
            miningPriceService.refreshJitaPrices();
        } catch (Exception e) {
            log.error("Abgleich der Jita-Preise fehlgeschlagen: {}", e.getMessage(), e);
        }
    }
}

package com.eve.own.auth.backend.esi.etag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Haelt die Tabelle {@code esi_etags} klein.
 *
 * <p>Ohne das Aufraeumen wuerden Eintraege zu Charakteren, deren Token laengst
 * abgelaufen ist, unbegrenzt liegenbleiben - inklusive ihrer zwischengespeicherten
 * Response-Bodies.</p>
 */
@Slf4j
@Component
public class EsiEtagCleanupScheduler {

    private static final long ONE_DAY_IN_MILLIS = 86_400_000L;
    private static final long STARTUP_DELAY_IN_MILLIS = 600_000L;

    private final EsiEtagStore etagStore;

    public EsiEtagCleanupScheduler(EsiEtagStore etagStore) {
        this.etagStore = etagStore;
    }

    @Scheduled(fixedRate = ONE_DAY_IN_MILLIS, initialDelay = STARTUP_DELAY_IN_MILLIS)
    public void purgeStaleEntries() {
        try {
            etagStore.purgeStaleEntries();
        } catch (Exception e) {
            log.error("Aufraeumen des ETag-Caches fehlgeschlagen: {}", e.getMessage());
        }
    }
}

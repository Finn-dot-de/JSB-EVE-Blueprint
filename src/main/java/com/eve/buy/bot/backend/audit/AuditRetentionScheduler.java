package com.eve.buy.bot.backend.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Räumt alte Protokolleinträge weg.
 *
 * <p>Das Protokoll enthält IP-Adressen und damit personenbezogene Daten. Eine unbegrenzte
 * Aufbewahrung wäre weder zulässig noch nötig - die Frist steht in
 * {@code buybot.audit.retention-days}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionScheduler {

    private final AuditEntryRepository repository;

    /** Aufbewahrungsdauer in Tagen. */
    @Value("${buybot.audit.retention-days:30}")
    private int retentionDays;

    /** Läuft einmal täglich; der genaue Zeitpunkt ist unerheblich. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    @Transactional
    public void purgeOldEntries() {
        if (retentionDays <= 0) {
            return;
        }
        Instant threshold = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = repository.deleteOlderThan(threshold);
        if (deleted > 0) {
            log.info("Protokoll aufgeräumt: {} Einträge älter als {} Tage gelöscht.", deleted, retentionDays);
        }
    }
}

package com.eve.own.auth.backend.domain.character.scheduler;

import com.eve.own.auth.backend.domain.character.service.AltSourceRetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Der Loeschlauf der Aufbewahrungsfristen.
 *
 * <p>Er ist der Teil, der eine Frist von einer Absicht unterscheidet. Deshalb
 * gibt es ihn als eigene Klasse und nicht als Nebentaetigkeit eines anderen
 * Laufs: wer den Erfassungslauf abschaltet oder umbaut, darf dabei nicht
 * versehentlich das Loeschen mit abschalten. Die Erfassung kann ruhen, die
 * Aufbewahrungsfrist laeuft weiter.</p>
 *
 * <p>Taeglich, weil die Frist in Tagen gilt - haeufiger zu loeschen aendert am
 * Ergebnis nichts und liefe nur oefter ueber die Tabelle. Die
 * Anlaufverzoegerung haelt den Start der Anwendung frei; das Muster stammt vom
 * {@code EsiEtagCleanupScheduler}.</p>
 *
 * <p>Ein Fehlschlag wird protokolliert und beendet den Lauf nicht: die zweite
 * Tabelle soll auch dann aufgeraeumt werden, wenn es bei der ersten hakte.</p>
 */
@Slf4j
@Component
public class AltSourceRetentionScheduler {

    private static final long ONE_DAY_IN_MILLIS = 86_400_000L;
    private static final long STARTUP_DELAY_IN_MILLIS = 900_000L;

    private final AltSourceRetentionService retentionService;

    public AltSourceRetentionScheduler(AltSourceRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(fixedRate = ONE_DAY_IN_MILLIS, initialDelay = STARTUP_DELAY_IN_MILLIS)
    public void purgeExpiredRecords() {
        try {
            retentionService.purgePresence();
        } catch (Exception e) {
            log.error("Loeschlauf der Anwesenheitsaufzeichnung fehlgeschlagen: {}", e.getMessage());
        }
        try {
            retentionService.purgeIskTransfers();
        } catch (RuntimeException e) {
            log.warn("Loeschlauf der ISK-Ueberweisungen fehlgeschlagen: {}", e.getMessage());
        }
        try {
            // Ohne diesen Lauf blieben Zeilen von Charakteren, die aus dem
            // Erfassungslauf herausfallen, fuer immer liegen - und die Zusage
            // auf der Oberflaeche waere unwahr.
            retentionService.purgeSnapshots();
        } catch (Exception e) {
            log.error("Loeschlauf der ISK-Ueberweisungen fehlgeschlagen: {}", e.getMessage());
        }
    }
}

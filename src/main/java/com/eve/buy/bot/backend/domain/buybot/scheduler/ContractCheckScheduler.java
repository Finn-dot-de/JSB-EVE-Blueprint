package com.eve.buy.bot.backend.domain.buybot.scheduler;

import com.eve.buy.bot.backend.domain.buybot.service.ContractCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tickt alle 30 Sekunden. Ob wirklich geprüft wird, entscheidet der Service
 * anhand des im Admin-Panel eingestellten Intervalls - so wirken Änderungen
 * sofort und ohne Neustart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractCheckScheduler {

    private final ContractCheckService contractCheckService;

    /** Prueft, ob ein Durchlauf faellig ist, und stoesst ihn an. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void tick() {
        try {
            ContractCheckService.RunResult result = contractCheckService.runIfDue();
            if (result == null) {
                return; // ausgeschaltet oder Intervall noch nicht abgelaufen
            }
            // Ruhige Läufe nur auf DEBUG, damit das Log bei kurzem Intervall nicht zuläuft.
            // Im Admin-Panel ist jeder Lauf am Zeitstempel sichtbar.
            if (result.checked() > 0 || !result.success()) {
                log.info("Vertragsprüfung: {}", result.message());
            } else {
                log.debug("Vertragsprüfung: {}", result.message());
            }
        } catch (Exception e) {
            log.error("Vertragsprüfung abgebrochen: {}", e.getMessage());
        }
    }
}

package com.eve.own.auth.backend.domain.mining;

import com.eve.own.auth.backend.domain.mining.service.MiningTaxRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Gleicht die Steuersaetze beim Start mit der SDE ab.
 *
 * <p>Bewusst ein {@link ApplicationRunner} und kein {@code @PostConstruct} in
 * einem Controller: der Abgleich braucht eine bereits einsatzbereite
 * Datenbankschicht. Beim Aufbau der Beans ist die noch nicht garantiert
 * vorhanden, was Fehler frueher hinter einem stillen {@code catch} verschwinden
 * liess.</p>
 *
 * <p>Ein Fehlschlag beendet den Start nicht: die Anwendung bleibt ohne frische
 * Stammdaten nutzbar, nur eventuelle neue Erze fehlen bis zum naechsten Start.</p>
 */
@Slf4j
@Component
public class MiningTaxRateInitializer implements ApplicationRunner {

    private final MiningTaxRateService taxRateService;

    public MiningTaxRateInitializer(MiningTaxRateService taxRateService) {
        this.taxRateService = taxRateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            taxRateService.synchronizeWithSde();
        } catch (Exception e) {
            log.error("Steuersaetze liessen sich beim Start nicht abgleichen: {}", e.getMessage(), e);
        }
    }
}

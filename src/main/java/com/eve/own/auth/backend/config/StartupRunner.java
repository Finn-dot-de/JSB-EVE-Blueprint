package com.eve.own.auth.backend.config;

import com.eve.own.auth.backend.domain.eve.service.SdeImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StartupRunner implements CommandLineRunner {

    @Value("${app.run-migration:false}")
    private boolean runMigration;

    private final SdeImportService sdeService; // Dein Service, der CSVs in die DB schaufelt

    public StartupRunner(SdeImportService sdeService) {
        this.sdeService = sdeService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (runMigration) {
            log.info("Migration-Flag gefunden: Starte SDE Import...");
            sdeService.importStaticData();
            log.info("SDE Import abgeschlossen.");
        }
    }
}
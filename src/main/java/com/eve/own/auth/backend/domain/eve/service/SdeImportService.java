package com.eve.own.auth.backend.domain.eve.service;

import com.eve.own.auth.backend.domain.eve.entity.EveType;
import com.eve.own.auth.backend.domain.eve.repository.EveTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class SdeImportService {

    private final EveTypeRepository typeRepository;

    public SdeImportService(EveTypeRepository typeRepository) {
        this.typeRepository = typeRepository;
    }

    @Transactional
    public void importStaticData() {
        log.info("Lade invTypes.csv aus dem SDE...");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("sde/invTypes.csv").getInputStream()))) {

            String line;
            List<EveType> batch = new ArrayList<>();
            int count = 0;

            // Header überspringen
            br.readLine();

            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");

                EveType type = new EveType();
                type.setTypeId(Long.parseLong(values[0]));
                type.setGroupId(Long.parseLong(values[1]));
                type.setName(values[2]);
                // volume/mass falls vorhanden...

                batch.add(type);
                count++;

                // Batch-Save für Performance
                if (batch.size() >= 500) {
                    typeRepository.saveAll(batch);
                    batch.clear();
                    log.info("Importiert: {} Zeilen", count);
                }
            }
            typeRepository.saveAll(batch); // Rest speichern
            log.info("SDE Import fertig. Insgesamt {} Typen geladen.", count);

        } catch (Exception e) {
            log.error("Fehler beim SDE Import: ", e);
        }
    }
}
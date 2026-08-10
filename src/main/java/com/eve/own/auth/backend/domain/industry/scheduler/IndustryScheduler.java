package com.eve.own.auth.backend.domain.industry.scheduler;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.industry.service.IndustryAttributionService;
import com.eve.own.auth.backend.domain.industry.service.IndustrySyncService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holt die Industriedaten regelmaessig ab.
 *
 * <p>Die Takte richten sich nach dem, was sich tatsaechlich aendert, nicht nach
 * einem runden Wert:</p>
 * <ul>
 *   <li><b>Jobs alle zehn Minuten.</b> ESI puffert diesen Endpunkt fuenf Minuten;
 *       haeufiger abzufragen bringt nur dieselbe Antwort und verbraucht das
 *       Fehlerkontingent.</li>
 *   <li><b>Blaupausen alle sechs Stunden.</b> Forschung dauert Tage - haeufiger
 *       nachzusehen liefert nie etwas Neues.</li>
 *   <li><b>Referenzpreise stuendlich.</b> CCP rechnet sie einmal am Tag neu,
 *       aber die Uhrzeit ist nicht zugesagt.</li>
 * </ul>
 *
 * <p>Die Anfangsverzoegerungen sind gestaffelt, damit nicht alle drei Laeufe
 * gleichzeitig mit dem uebrigen Start um Verbindungen konkurrieren.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndustryScheduler {

    private static final long TEN_MINUTES = 600_000L;
    private static final long SIX_HOURS = 21_600_000L;
    private static final long ONE_HOUR = 3_600_000L;

    private final CharacterRepository characterRepo;
    private final IndustrySyncService syncService;
    private final IndustryAttributionService attribution;

    /** Jobs abholen und den Auftraegen zuordnen. */
    @Scheduled(fixedRate = TEN_MINUTES, initialDelay = 240_000)
    public void syncJobs() {
        var alle = characterRepo.findAll();
        if (alle.isEmpty()) {
            return;
        }

        int abgeholt = 0;
        int ohneZugriff = 0;
        for (Character c : alle) {
            int n = syncService.syncJobs(c);
            if (n < 0) {
                ohneZugriff++;
            } else {
                abgeholt += n;
            }
        }

        int zugeordnet = attributeAll(alle);
        log.info("Industriejobs: {} abgeholt, {} zugeordnet, {} von {} Charakteren ohne Zugriff.",
                abgeholt, zugeordnet, ohneZugriff, alle.size());
    }

    /** Blaupausen abholen - ohne sie rechnet jeder Auftrag mit ME 0. */
    @Scheduled(fixedRate = SIX_HOURS, initialDelay = 300_000)
    public void syncBlueprints() {
        var alle = characterRepo.findAll();
        int abgeholt = 0;
        int ohneZugriff = 0;
        for (Character c : alle) {
            int n = syncService.syncBlueprints(c);
            if (n < 0) {
                ohneZugriff++;
            } else {
                abgeholt += n;
            }
        }
        log.info("Blaupausen: {} abgeholt, {} von {} Charakteren ohne Zugriff.",
                abgeholt, ohneZugriff, alle.size());
    }

    /** Die Referenzpreise, auf denen die Jobgebuehr beruht. */
    @Scheduled(fixedRate = ONE_HOUR, initialDelay = 360_000)
    public void syncAdjustedPrices() {
        syncService.syncAdjustedPrices();
    }

    /**
     * Die Jita-Preise fuer Erze, Komponenten und Reaktionsprodukte.
     *
     * <p>Getrennt vom Referenzpreis-Lauf, weil es eine andere Quelle ist und
     * deutlich mehr Abfragen kostet - rund 27 Bloecke zu je 200 Typen. Der
     * Abstand von einer Stunde reicht: Marktpreise schwanken, aber nicht im
     * Minutentakt, und eine Beschaffungsrechnung ist ohnehin eine Schaetzung.</p>
     */
    @Scheduled(fixedRate = ONE_HOUR, initialDelay = 480_000)
    public void syncIndustryPrices() {
        syncService.syncIndustryPrices();
    }

    /**
     * Die Strukturen der Corporation samt ihrer Dienste.
     *
     * <p>Nur alle sechs Stunden: Strukturen kommen und gehen selten, und der
     * Endpunkt braucht die Ingame-Rolle Station_Manager, die die meisten
     * Mitglieder nicht haben. Es genuegt, wenn ein einziger Charakter mit der
     * Rolle den Abgleich traegt - deshalb wird nach dem ersten Erfolg
     * abgebrochen, statt bei allen uebrigen ins Leere zu laufen und dabei das
     * Fehlerkontingent zu verbrauchen.</p>
     */
    @Scheduled(fixedRate = SIX_HOURS, initialDelay = 420_000)
    public void syncStructures() {
        for (Character c : characterRepo.findAll()) {
            int n = syncService.syncCorpStructures(c);
            if (n >= 0) {
                log.info("Bauorte: {} Corp-Strukturen über Charakter {} abgeglichen.",
                        n, c.getId());
                return;
            }
        }
        log.debug("Bauorte: kein Charakter mit der Rolle Station_Manager gefunden.");
    }

    /**
     * Ordnet die Jobs kontoweise zu.
     *
     * <p>Kontoweise und nicht charakterweise, weil ein Auftrag dem Konto gehoert:
     * ein Alt kann sehr wohl den Job zum Auftrag des Hauptcharakters starten.</p>
     */
    private int attributeAll(Iterable<Character> alle) {
        Map<Long, Set<Long>> konten = new HashMap<>();
        for (Character c : alle) {
            Long accountId = c.getMainCharacterId() != null ? c.getMainCharacterId() : c.getId();
            konten.computeIfAbsent(accountId, k -> new HashSet<>()).add(c.getId());
        }

        int summe = 0;
        for (Map.Entry<Long, Set<Long>> konto : konten.entrySet()) {
            try {
                summe += attribution.attribute(konto.getKey(), konto.getValue());
            } catch (RuntimeException e) {
                log.warn("Zuordnung für Konto {} fehlgeschlagen: {}",
                        konto.getKey(), e.getMessage());
            }
        }
        return summe;
    }
}

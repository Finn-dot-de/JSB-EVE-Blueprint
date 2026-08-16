package com.eve.own.auth.backend.domain.character.scheduler;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.service.CharacterSyncService;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Laeuft regelmaessig alle Charaktere durch und stoesst ihren Sync an.
 *
 * <p>Die Klasse entscheidet ausschliesslich ueber <em>wann</em> und <em>wie oft</em>
 * synchronisiert wird und wie mit den Grenzen von ESI umzugehen ist. Was genau
 * geholt wird, steht im {@link CharacterSyncService}.</p>
 */
@Slf4j
@Component
public class CharacterSyncScheduler {

    /** Alle zehn Minuten. Als Literal, weil {@code @Scheduled} einen Konstantenausdruck verlangt. */
    private static final long INTERVAL_MS = 10 * 60 * 1000L;

    /**
     * Kurze Pause zwischen zwei Charakteren.
     *
     * <p>ESI erlaubt zwar deutlich hoehere Raten, doch ein gleichmaessiger Strom
     * ist billiger als ein Schwall: die Antwortzeiten bleiben stabil und das
     * Fehler-Budget wird gar nicht erst angetastet.</p>
     */
    private static final long PAUSE_BETWEEN_CHARACTERS_MS = 150;

    /** Zwangspause, wenn CCP das Fehler-Budget fuer erschoepft erklaert (Status 420). */
    private static final Duration ERROR_LIMIT_COOLDOWN = Duration.ofSeconds(60);

    private final CharacterRepository characterRepo;
    private final CharacterSyncService characterSyncService;

    public CharacterSyncScheduler(CharacterRepository characterRepo,
                                  CharacterSyncService characterSyncService) {
        this.characterRepo = characterRepo;
        this.characterSyncService = characterSyncService;
    }

    @Scheduled(fixedRate = INTERVAL_MS)
    public void syncAllCharacters() {
        log.info("Starte Account-Sync...");
        List<Character> characters = characterRepo.findAllWithCorporation();

        for (Character character : characters) {
            if (!syncSafely(character)) {
                // Unterbrochen: der Thread soll enden, nicht weiterarbeiten.
                return;
            }
        }
        log.info("Account-Sync abgeschlossen: {} Charaktere.", characters.size());
    }

    /**
     * Synchronisiert einen Charakter und faengt dabei alles ab, was den Durchlauf
     * der uebrigen nicht gefaehrden darf.
     *
     * @return {@code false}, wenn der Thread unterbrochen wurde
     */
    private boolean syncSafely(Character character) {
        try {
            characterSyncService.sync(character);
            return pause(PAUSE_BETWEEN_CHARACTERS_MS);
        } catch (RestClientResponseException e) {
            return handleEsiError(character, e);
        } catch (Exception e) {
            log.error("Sync fuer Charakter {} fehlgeschlagen: {}", character.getName(), e.getMessage());
            return true;
        }
    }

    private boolean handleEsiError(Character character, RestClientResponseException e) {
        if (EsiHttpStatus.isErrorLimited(e)) {
            log.warn("ESI-Fehler-Budget bei Charakter {} erschoepft, pausiere {} s.",
                    character.getName(), ERROR_LIMIT_COOLDOWN.toSeconds());
            return pause(ERROR_LIMIT_COOLDOWN.toMillis());
        }
        if (EsiHttpStatus.isAuthFailure(e)) {
            log.warn("Auth-Fehler ({}) bei Charakter {}: Token abgelaufen oder Rechte fehlen.",
                    e.getStatusCode(), character.getName());
            return true;
        }
        log.error("ESI-Fehler bei Charakter {}: {} - {}",
                character.getName(), e.getStatusCode(), e.getResponseBodyAsString());
        return true;
    }

    /** @return {@code false}, wenn die Pause unterbrochen wurde */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Account-Sync unterbrochen, Durchlauf wird abgebrochen.");
            return false;
        }
    }
}

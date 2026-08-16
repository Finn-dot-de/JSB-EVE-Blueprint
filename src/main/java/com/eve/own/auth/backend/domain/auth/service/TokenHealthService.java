package com.eve.own.auth.backend.domain.auth.service;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Haelt fest, welche Charaktere sich neu anmelden muessen.
 *
 * <p>Bis hierhin existierte diese Information nur als Logzeile - "invalid_grant
 * - Refresh token missing" - und war nach dem naechsten Durchlauf weg. Niemand
 * konnte sagen, <em>welche</em> Charaktere betroffen sind; entsprechend liess
 * sich weder etwas im Auth anzeigen noch jemand benachrichtigen.</p>
 *
 * <h2>Warum eine eigene Klasse</h2>
 * <p>Der Vermerk muss <b>ueberleben, was ihn ausloest</b>. Er entsteht genau
 * dann, wenn die Token-Erneuerung scheitert - und deren Transaktion wird
 * anschliessend zurueckgerollt. Schriebe man den Vermerk dort hinein, ginge er
 * mit unter. Deshalb {@link Propagation#REQUIRES_NEW} und deshalb eine eigene
 * Bohne: ein Aufruf ueber {@code this} wuerde am Spring-Proxy vorbeigehen und
 * die Anmerkung wirkungslos machen.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenHealthService {

    /** Laenge, auf die der Grund gekuerzt wird - die Spalte fasst 255. */
    private static final int MAX_REASON = 240;

    private final CharacterRepository characterRepo;

    /**
     * Vermerkt, dass sich der Token nicht erneuern liess.
     *
     * <p>Der Zeitpunkt bleibt beim <em>ersten</em> Fehlschlag stehen. Sonst
     * rueckte er bei jedem Zehn-Minuten-Lauf nach, und "seit wann ist der
     * Charakter draussen" waere immer "gerade eben" - genau die Angabe, die man
     * braucht, um zwischen einem Aussetzer und einem Dauerzustand zu
     * unterscheiden.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInvalid(Long characterId, String reason) {
        characterRepo.findById(characterId).ifPresent(c -> {
            if (c.getTokenInvalidSince() != null) {
                return;
            }
            c.setTokenInvalidSince(Instant.now());
            c.setTokenInvalidReason(kuerzen(reason));
            characterRepo.save(c);
            log.warn("Charakter {} braucht eine neue Anmeldung: {}", c.getName(), kuerzen(reason));
        });
    }

    /**
     * Nimmt den Vermerk zurueck, sobald der Token wieder funktioniert.
     *
     * <p>Bewusst ohne Schreibvorgang, wenn nichts vermerkt war - das ist der
     * Normalfall und laeuft bei jedem Abgleich fuer jeden Charakter.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markValid(Long characterId) {
        characterRepo.findById(characterId).ifPresent(c -> {
            if (c.getTokenInvalidSince() == null) {
                return;
            }
            log.info("Charakter {} ist wieder angemeldet.", c.getName());
            c.setTokenInvalidSince(null);
            c.setTokenInvalidReason(null);
            // Auch die Meldesperre faellt: der naechste Vorfall ist ein neuer.
            c.setTokenInvalidNotifiedAt(null);
            characterRepo.save(c);
        });
    }

    /** Alle Charaktere, die sich neu anmelden muessen. */
    @Transactional(readOnly = true)
    public List<Character> invalidTokens() {
        return characterRepo.findByTokenInvalidSinceIsNotNull();
    }

    /** Haelt fest, dass wegen dieses Vorfalls benachrichtigt wurde. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNotified(Long characterId) {
        characterRepo.findById(characterId).ifPresent(c -> {
            c.setTokenInvalidNotifiedAt(Instant.now());
            characterRepo.save(c);
        });
    }

    private static String kuerzen(String reason) {
        if (reason == null) {
            return "unbekannt";
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }
}

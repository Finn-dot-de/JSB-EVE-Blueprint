package com.eve.own.auth.backend.domain.character.scheduler;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.service.ContactSyncService;
import com.eve.own.auth.backend.domain.character.service.MailCountSyncService;
import com.eve.own.auth.backend.domain.character.service.MemberPresenceSyncService;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Der Takt der drei Erfassungen, die eigene ESI-Aufrufe kosten.
 *
 * <p>Die vierte - die ISK-Ueberweisungen - steht hier nicht: sie reitet auf dem
 * Wallet-Journal mit, das der Charakter-Sync ohnehin holt, und kostet keinen
 * eigenen Aufruf. Was hier steht, kostet Aufrufe, und deshalb steht hier auch
 * die Rechnung dazu.</p>
 *
 * <h2>Mengengeruest der Anwesenheit</h2>
 * <p>Bei rund <b>400 Mitgliedern</b> und einem Takt von <b>drei Stunden</b>
 * (acht Laeufen am Tag) waeren das in 90 Tagen im schlechtesten Fall
 * 400 &times; 8 &times; 90 = <b>288.000 Zeilen</b>. Erreicht wird der Wert nur,
 * wenn jedes Mitglied sich in jedem Fenster neu ein- oder ausloggt. Weil eine
 * Zeile nur bei einer <em>Aenderung</em> entsteht, liegt der reale Wert weit
 * darunter: bei etwa hundert taeglich aktiven Mitgliedern mit zwei bis drei
 * erkennbaren Wechseln landet man bei <b>20.000 bis 30.000 Zeilen</b> pro 90
 * Tage. Beides ist fuer PostgreSQL nichts.</p>
 *
 * <p><b>Warum nicht stuendlich:</b> derselbe Bestand waere dann
 * 400 &times; 24 &times; 90 = <b>864.000 Zeilen</b> - dreimal so viel fuer eine
 * Angabe, die sich nur bei Logon und Logoff ueberhaupt aendert. Der Zugewinn
 * waere gering: der auswertbare Wert ist der <em>Zeitstempel</em> des Logons,
 * den ESI mitliefert, nicht der Zeitpunkt unserer Messung. Ein engerer Takt
 * verbessert die Genauigkeit also nicht, er faengt nur zusaetzlich die Faelle,
 * in denen sich jemand innerhalb eines Fensters zweimal einloggt.</p>
 *
 * <p><b>Warum nicht seltener:</b> bei zwoelf Stunden wuerde ein ganzer
 * Spieleabend samt Logon und Logoff in ein Fenster fallen; man saehe nur noch
 * den letzten Wechsel und verloere genau die Paare, die gemeinsam gespielt
 * haben.</p>
 *
 * <h2>ESI-Last je Lauf</h2>
 * <ul>
 *   <li><b>Anwesenheit:</b> ein Aufruf je betreuter Corporation. Bei zwei
 *       Corporations und acht Laeufen also 16 Aufrufe am Tag.</li>
 *   <li><b>Kontakte:</b> ein konditionaler Aufruf je registriertem Charakter.</li>
 *   <li><b>Mail:</b> ebenfalls genau einer je registriertem Charakter.</li>
 * </ul>
 * <p>Kontakte und Mail laufen alle sechs Stunden und nicht im Takt des
 * Charakter-Syncs (zehn Minuten). Bei 50 angemeldeten Charakteren sind das
 * 50 &times; 2 &times; 4 = <b>400 Aufrufe am Tag</b> statt 14.400 im
 * Zehn-Minuten-Takt. Beide Angaben aendern sich selten - eine Kontaktliste wird
 * nicht stuendlich umgebaut -, und das Projekt ist bei Discord schon einmal in
 * ein Rate-Limit gelaufen.</p>
 */
@Slf4j
@Component
public class AltSourceScheduler {

    /** Alle drei Stunden. Als Literal, weil {@code @Scheduled} einen Konstantenausdruck verlangt. */
    private static final long PRESENCE_INTERVAL_MS = 3 * 60 * 60 * 1000L;

    /** Alle sechs Stunden. */
    private static final long CONTACT_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    /**
     * Zwei verschiedene Anlaufverzoegerungen.
     *
     * <p>Faenden beide Laeufe gleichzeitig statt, traefen der Corp-Aufruf und
     * die Aufrufe aller Charaktere im selben Moment auf ESI. Versetzt kostet
     * nichts und haelt den Strom gleichmaessig - dieselbe Ueberlegung wie bei
     * der Pause zwischen zwei Charakteren im {@code CharacterSyncScheduler}.</p>
     */
    private static final long PRESENCE_STARTUP_DELAY_MS = 120_000L;
    private static final long CONTACT_STARTUP_DELAY_MS = 300_000L;

    /** Kurze Pause zwischen zwei Charakteren, wie im Charakter-Sync. */
    private static final long PAUSE_BETWEEN_CHARACTERS_MS = 150;

    /** Zwangspause, wenn CCP das Fehler-Budget fuer erschoepft erklaert (Status 420). */
    private static final Duration ERROR_LIMIT_COOLDOWN = Duration.ofSeconds(60);

    private final CorporationScope corporationScope;
    private final CharacterRepository characterRepo;
    private final AuthService authService;
    private final MemberPresenceSyncService presenceSyncService;
    private final ContactSyncService contactSyncService;
    private final MailCountSyncService mailCountSyncService;

    public AltSourceScheduler(CorporationScope corporationScope,
                              CharacterRepository characterRepo,
                              AuthService authService,
                              MemberPresenceSyncService presenceSyncService,
                              ContactSyncService contactSyncService,
                              MailCountSyncService mailCountSyncService) {
        this.corporationScope = corporationScope;
        this.characterRepo = characterRepo;
        this.authService = authService;
        this.presenceSyncService = presenceSyncService;
        this.contactSyncService = contactSyncService;
        this.mailCountSyncService = mailCountSyncService;
    }

    /**
     * Ein Messpunkt je betreuter Corporation.
     *
     * <p>Der Fehlschlag einer Corporation darf die naechste nicht verhindern -
     * ausser bei einem erschoepften Fehler-Budget: dann wuerde jeder weitere
     * Versuch das Zeitfenster nur verlaengern, und der Lauf endet.</p>
     */
    @Scheduled(fixedRate = PRESENCE_INTERVAL_MS, initialDelay = PRESENCE_STARTUP_DELAY_MS)
    public void recordMemberPresence() {
        int written = 0;
        for (Long corporationId : corporationScope.allowedCorporationIds()) {
            try {
                written += presenceSyncService.sync(corporationId);
            } catch (RestClientResponseException e) {
                if (EsiHttpStatus.isErrorLimited(e)) {
                    log.warn("ESI-Fehler-Budget erschoepft, Anwesenheitslauf endet.");
                    return;
                }
                log.error("Anwesenheit fuer Corp {} fehlgeschlagen: {} - {}",
                        corporationId, e.getStatusCode(), e.getResponseBodyAsString());
            } catch (Exception e) {
                log.error("Anwesenheit fuer Corp {} fehlgeschlagen: {}", corporationId, e.getMessage());
            }
        }
        log.info("Anwesenheitslauf beendet: {} veraenderte Mitglieder festgehalten.", written);
    }

    /**
     * Kontakte und Mail-Zaehlung fuer jeden Charakter mit brauchbarem Token.
     *
     * <p>Beide Quellen im selben Lauf, weil beide dasselbe Token brauchen und
     * dieselbe Rangfolge haben. Wer kein Token hinterlegt hat oder dessen Token
     * dauerhaft ungueltig vermerkt ist, wird uebersprungen - ihn zu fragen
     * kostet einen SSO-Rundlauf und endet sicher im selben Fehlschlag, den der
     * Vermerk bereits festhaelt.</p>
     */
    @Scheduled(fixedRate = CONTACT_INTERVAL_MS, initialDelay = CONTACT_STARTUP_DELAY_MS)
    public void syncContactsAndMail() {
        List<Character> characters = characterRepo.findAllWithCorporation().stream()
                .filter(character -> character.getRefreshToken() != null)
                .filter(character -> character.getTokenInvalidSince() == null)
                .toList();

        for (Character character : characters) {
            if (!syncSafely(character)) {
                // Unterbrochen: der Thread soll enden, nicht weiterarbeiten.
                return;
            }
        }
        log.info("Kontakte und Mail-Zaehlung abgeschlossen: {} Charaktere.", characters.size());
    }

    /** @return {@code false}, wenn der ganze Lauf abgebrochen werden soll */
    private boolean syncSafely(Character character) {
        try {
            String token = authService.getValidAccessToken(character);
            if (token == null) {
                return true;
            }
            contactSyncService.sync(character, token);
            mailCountSyncService.sync(character, token);
            return pause(PAUSE_BETWEEN_CHARACTERS_MS);

        } catch (RestClientResponseException e) {
            if (EsiHttpStatus.isErrorLimited(e)) {
                log.warn("ESI-Fehler-Budget bei Charakter {} erschoepft, pausiere {} s.",
                        character.getName(), ERROR_LIMIT_COOLDOWN.toSeconds());
                return pause(ERROR_LIMIT_COOLDOWN.toMillis());
            }
            if (EsiHttpStatus.isAuthFailure(e)) {
                log.warn("Auth-Fehler ({}) bei Charakter {}: Token abgelaufen oder Scope fehlt.",
                        e.getStatusCode(), character.getName());
                return true;
            }
            log.error("Kontakte/Mail von {} fehlgeschlagen: {} - {}",
                    character.getName(), e.getStatusCode(), e.getResponseBodyAsString());
            return true;

        } catch (Exception e) {
            log.error("Kontakte/Mail von {} fehlgeschlagen: {}", character.getName(), e.getMessage());
            return true;
        }
    }

    /** @return {@code false}, wenn die Pause unterbrochen wurde */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Lauf unterbrochen, wird abgebrochen.");
            return false;
        }
    }
}

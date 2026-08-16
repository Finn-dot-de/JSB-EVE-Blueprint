package com.eve.own.auth.backend.domain.auth.scheduler;

import com.eve.own.auth.backend.domain.auth.service.TokenHealthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sagt ueber Discord Bescheid, wenn ein Charakter neu angemeldet werden muss.
 *
 * <p>Das Auth zeigt denselben Hinweis bereits an - aber nur dem, der es
 * oeffnet. Wer sein Konto laufen laesst und sich auf die Zahlen verlaesst,
 * erfaehrt dort nie, dass sie stehengeblieben sind. Discord erreicht ihn
 * trotzdem.</p>
 *
 * <h2>Zwei Regeln, die nicht verhandelbar sind</h2>
 * <ol>
 *   <li><b>Einmal je Vorfall.</b> Der Zeitplan laeuft stuendlich; ohne
 *       {@code tokenInvalidNotifiedAt} bekaeme der Spieler stuendlich dieselbe
 *       Nachricht und haette den Bot nach einem halben Tag stummgeschaltet.</li>
 *   <li><b>An den Main.</b> Der betroffene Charakter ist oft ein Alt, der nie
 *       mit Discord verknuepft wurde. Die Verknuepfung des Kontos haengt am
 *       Hauptcharakter - dorthin geht die Nachricht, und sie <em>nennt</em> den
 *       betroffenen Alt, sonst weiss niemand, welcher gemeint ist.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenAlertScheduler {

    private static final long ONE_HOUR = 3_600_000L;

    private final TokenHealthService tokenHealth;
    private final CharacterRepository characterRepo;
    private final DiscordConnectionRepository discordRepo;
    private final DiscordBotService discordBot;

    /**
     * Stuendlich, mit reichlich Anlauf.
     *
     * <p>Der Anlauf ist Absicht: direkt nach dem Start ist noch kein einziger
     * Abgleich gelaufen, die Vermerke stammen also vom letzten Mal. Wer sich
     * inzwischen neu angemeldet hat, bekaeme sonst eine Nachricht ueber ein
     * Problem, das er laengst geloest hat.</p>
     */
    @Scheduled(fixedRate = ONE_HOUR, initialDelay = 900_000)
    public void meldeAbgelaufeneAnmeldungen() {
        var offen = tokenHealth.invalidTokens().stream()
                .filter(c -> c.getTokenInvalidNotifiedAt() == null)
                .toList();
        if (offen.isEmpty()) {
            return;
        }

        int gemeldet = 0;
        for (Character c : offen) {
            if (benachrichtige(c)) {
                gemeldet++;
            }
            // Auch ohne Zustellung vermerken: ein Nutzer ohne Discord-
            // Verknuepfung oder mit gesperrten Direktnachrichten darf nicht
            // stuendlich einen erfolglosen Versuch ausloesen.
            tokenHealth.markNotified(c.getId());
        }
        log.info("Abgelaufene Anmeldungen: {} von {} über Discord gemeldet.",
                gemeldet, offen.size());
    }

    /** Schickt die Nachricht an den Main des betroffenen Charakters. */
    private boolean benachrichtige(Character betroffen) {
        Long mainId = betroffen.getMainCharacterId() != null
                ? betroffen.getMainCharacterId()
                : betroffen.getId();

        String discordId = discordRepo.findById(mainId)
                .map(v -> v.getDiscordUserId())
                .orElse(null);
        if (discordId == null || discordId.isBlank()) {
            log.debug("Kein Discord für Konto {} - Hinweis bleibt im Auth.", mainId);
            return false;
        }

        String mainName = characterRepo.findById(mainId)
                .map(Character::getName)
                .orElse("dein Hauptcharakter");

        String text = betroffen.getId().equals(mainId)
                ? "**%s** ist bei EVE abgemeldet.".formatted(mainName)
                : "Dein Charakter **%s** ist bei EVE abgemeldet.".formatted(betroffen.getName());

        return discordBot.sendDirectMessage(discordId, text + """

                Für ihn werden keine Daten mehr geholt - Bestände, Skills und \
                Industriejobs bleiben auf dem letzten Stand stehen, ohne dass \
                es weiter auffällt.

                Eine kurze Neuanmeldung im Auth genügt.""");
    }
}

package com.eve.own.auth.backend.domain.discord.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Merkt sich, wann der Abgleich ein Discord-Konto zuletzt angefasst hat.
 *
 * <p>Ohne diese Notiz gibt es eine Ursache, die sich nicht von "unbekannt"
 * trennen laesst: Eine Rolle, die vor fuenf Minuten im Auth vergeben wurde, ist
 * in Discord noch nicht angekommen, weil der Abgleich alle dreissig Minuten
 * laeuft - und nicht, weil etwas kaputt ist. Wer das nicht unterscheiden kann,
 * sucht einen Fehler, den es nicht gibt.</p>
 *
 * <p><b>Absichtlich nur im Arbeitsspeicher.</b> Ein Neustart verliert den Stand -
 * und das ist genau richtig: Nach einem Neustart hat der Abgleich tatsaechlich
 * noch nicht gelaufen. Eine Tabellenspalte wuerde nach dem Hochfahren behaupten,
 * er sei vor zehn Minuten gelaufen, obwohl er es in diesem Prozess nie tat.</p>
 *
 * <p>Bezugsgroesse ist die {@code discord_user_id} und nicht der Charakter: der
 * Abgleich schickt seine Aufrufe an ein Konto, und ob dabei ein Main oder ein Alt
 * den Anlass gab, aendert am Stand des Kontos nichts.</p>
 */
@Component
public class DiscordSyncStand {

    /**
     * Beschraenkt auf die Zahl der verknuepften Konten - also klein und
     * beschraenkt. {@link ConcurrentHashMap}, weil Zeitplan und der von Hand
     * angestossene Abgleich gleichzeitig schreiben koennen.
     */
    private final Map<String, Instant> letzterLauf = new ConcurrentHashMap<>();

    /** Vermerkt, dass der Abgleich dieses Konto soeben angefasst hat. */
    public void notiere(String discordUserId) {
        if (discordUserId == null || discordUserId.isBlank()) {
            return;
        }
        letzterLauf.put(discordUserId, Instant.now());
    }

    /**
     * Wann der Abgleich dieses Konto zuletzt angefasst hat.
     *
     * @return leer, wenn er es in diesem Prozess noch nie tat - dann ist eine
     *         fehlende Rolle kein Befund, sondern eine Wartezeit
     */
    public Optional<Instant> letzterLauf(String discordUserId) {
        return discordUserId == null ? Optional.empty() : Optional.ofNullable(letzterLauf.get(discordUserId));
    }
}

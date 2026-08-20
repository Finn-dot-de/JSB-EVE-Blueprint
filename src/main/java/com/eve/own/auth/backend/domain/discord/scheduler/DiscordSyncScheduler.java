package com.eve.own.auth.backend.domain.discord.scheduler;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import com.eve.own.auth.backend.domain.discord.service.DiscordSyncStand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class DiscordSyncScheduler {

    /**
     * Abstand zwischen zwei Durchlaeufen - dreissig Minuten.
     *
     * <p>Hier stand {@code 1_800_00}. Ein fehlender Unterstrich, drei Minuten
     * statt dreissig, und niemandem faellt beim Lesen auf, dass eine Null
     * fehlt. Deshalb jetzt als benannte Rechnung: {@code 30 * 60 * 1000} laesst
     * sich nachzaehlen.</p>
     */
    private static final long ABSTAND_MS = 30L * 60L * 1000L;

    /**
     * Wie lange nach dem Hochfahren gewartet wird.
     *
     * <p>Nicht der volle Abstand: Nach einem Neustart hat der Abgleich in
     * diesem Prozess noch nie gelaufen, und die Pruefung meldet solange
     * "Abgleich steht aus". Eine halbe Stunde Blindflug nach jedem Deployment
     * waere der falsche Preis fuer eine ruhige Startminute.</p>
     */
    private static final long ERSTER_LAUF_MS = 60L * 1000L;

    /** Verschnaufpause zwischen zwei Konten, damit die Aufrufe nicht am Stueck kommen. */
    private static final Duration ATEMPAUSE_JE_KONTO = Duration.ofMillis(200);

    /**
     * Ob gerade ein Durchlauf laeuft.
     *
     * <p>Zweiter Riegel neben {@code fixedDelay}: Die Reihe hat acht Faeden
     * ({@code spring.task.scheduling.pool.size}), und ein von Hand ausgeloester
     * Lauf oder eine kuenftige zweite Ausloesung darf sich nicht mit dem
     * laufenden ueberlagern. Zwei gleichzeitige Durchlaeufe verdoppeln die
     * Aufrufe an genau der Stelle, an der Discord ohnehin schon bremst.</p>
     */
    private final AtomicBoolean laeuft = new AtomicBoolean(false);

    private final DiscordConnectionRepository connectionRepo;
    private final CharacterRepository characterRepo;
    private final DiscordRoleMappingRepository mappingRepo;
    private final DiscordBotService discordBotService;
    private final DiscordSyncStand syncStand;

    public DiscordSyncScheduler(DiscordConnectionRepository connectionRepo, CharacterRepository characterRepo,
                                DiscordRoleMappingRepository mappingRepo, DiscordBotService discordBotService,
                                DiscordSyncStand syncStand) {
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.discordBotService = discordBotService;
        this.syncStand = syncStand;
    }

    /**
     * Gleicht alle verknuepften Konten ab.
     *
     * <p><b>{@code fixedDelay} und nicht {@code fixedRate}.</b> Im Log stand
     * "Discord Role Sync abgeschlossen" und unmittelbar darauf wieder "Starte
     * Discord Role Sync..." - der naechste Lauf begann in derselben Sekunde.
     * {@code fixedRate} misst den Abstand von Start zu Start: Dauert ein
     * Durchlauf laenger als das Intervall, holt Spring das Versaeumte sofort
     * nach. Und laenger dauerte er zwangslaeufig, weil jeder Lauf ins Rate
     * Limit lief und dort Sekunden verlor. {@code fixedDelay} misst vom Ende
     * des letzten Laufs - nach einem langen Lauf bleibt die Pause, statt zu
     * verschwinden.</p>
     */
    @Scheduled(fixedDelay = ABSTAND_MS, initialDelay = ERSTER_LAUF_MS)
    public void syncDiscordRoles() {
        if (!laeuft.compareAndSet(false, true)) {
            log.warn("Ein Discord Role Sync laeuft noch - dieser Lauf faellt aus.");
            return;
        }
        try {
            fuehreAbgleichAus();
        } finally {
            // In jedem Fall freigeben: Ein Riegel, der nach einer Ausnahme
            // haengen bleibt, legt den Abgleich fuer immer still.
            laeuft.set(false);
        }
    }

    private void fuehreAbgleichAus() {
        Instant beginn = Instant.now();
        log.info("Starte Discord Role Sync...");
        List<DiscordConnection> connections = connectionRepo.findAll();

        List<String> verwalteteRollen = mappingRepo.findAll().stream()
                .map(DiscordRoleMapping::getDiscordRoleId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        int erreicht = 0;
        for (DiscordConnection conn : connections) {
            if (Thread.currentThread().isInterrupted()) {
                // Herunterfahren heisst herunterfahren. Ohne diese Abfrage
                // arbeitete der Durchlauf die restlichen Konten ab, obwohl
                // jede Wartezeit sofort abbricht - viele Aufrufe im
                // Schnelldurchlauf, genau beim Beenden.
                log.warn("Discord Role Sync abgebrochen - noch {} Konten offen.",
                        connections.size() - erreicht);
                break;
            }
            erreicht++;
            try {
                Character character = characterRepo.findById(conn.getCharacterId()).orElse(null);
                if (character == null) continue;

                Character mainChar = character.getMainCharacterId() != null
                        ? characterRepo.findById(character.getMainCharacterId()).orElse(character)
                        : character;
                String expectedNickname = mainChar.getName();

                List<String> expectedDiscordRoles = character.getRoles().stream()
                        .map(mappingRepo::findById)
                        .filter(Optional::isPresent)
                        .map(mapping -> mapping.get().getDiscordRoleId())
                        .toList();

                // Nur die verwalteten Rollen anfassen. Frueher ging hier die
                // Soll-Liste als vollstaendiges "roles"-Feld raus - bei Discord
                // ein Vollersatz, also zugleich der Befehl, jede handvergebene
                // Rolle zu entfernen.
                // Der Rueckgabewert ist neu, hier aber ohne Aufgabe: Der Zeitplan
                // hat niemanden, dem er berichten koennte, und protokolliert
                // seine Fehlschlaege wie bisher je Rolle. Ihn auszuwerten hiesse,
                // alle dreissig Minuten dieselbe Liste ins Log zu schreiben.
                // Anlass ZEITPLAN: Eine Rolle, die Discord mit 403 abgelehnt
                // hat, wird hier nicht bei jedem Lauf erneut versucht. Der
                // Anstoss von Hand kennt diese Ruhezeit nicht - wer den Knopf
                // drueckt, hat meist gerade die Bot-Rolle hoeher gezogen.
                discordBotService.syncManagedRoles(conn.getDiscordUserId(),
                        verwalteteRollen, expectedDiscordRoles, expectedNickname,
                        DiscordBotService.Anlass.ZEITPLAN);

                // Erst jetzt vermerken: Vorher ist es keine Wahrheit, sondern
                // eine Absicht. Ohne diesen Vermerk kann die Pruefung eine
                // fehlende Rolle nicht von einer blossen Wartezeit trennen und
                // meldet "unbekannt", wo nur noch nichts geschehen ist.
                syncStand.notiere(conn.getDiscordUserId());

                atme(ATEMPAUSE_JE_KONTO);

            } catch (HttpClientErrorException.TooManyRequests e) {
                // Der Aufruf selbst hat schon einmal gewartet und erneut
                // geklopft; kommt der 429 bis hierher, bremst Discord ernsthaft.
                // Gewartet wird die Zeit aus dem Header Retry-After, nicht die
                // frueheren pauschalen fuenf Sekunden - die waren geraten und
                // meist zu lang, gelegentlich zu kurz.
                Duration warten = DiscordBotService.wartezeit(e);
                log.warn("Rate Limit bei User {}. Discord nennt {} ms - so lange wird gewartet.",
                        conn.getDiscordUserId(), warten.toMillis());
                atme(warten);
            } catch (HttpClientErrorException.Forbidden e) {
                log.info("403 Forbidden bei User {}: Server-Owner oder Bot-Rolle zu niedrig.", conn.getDiscordUserId());
            } catch (HttpClientErrorException.NotFound e) {
                log.info("404 Not Found: User {} hat den Discord-Server verlassen.", conn.getDiscordUserId());
            } catch (Exception e) {
                log.error("Unerwarteter Fehler beim Sync für Discord User {}: {}", conn.getDiscordUserId(), e.getMessage());
            }
        }
        // Die Dauer gehoert dazu: Sie ist die Zahl, an der sich ablesen laesst,
        // ob der Abstand zum naechsten Lauf noch reicht. Ohne sie liess sich
        // aus dem Log nur erschliessen, dass etwas nicht stimmt.
        log.info("Discord Role Sync abgeschlossen - {} Konten in {} s. Naechster Lauf in {} min.",
                erreicht, Duration.between(beginn, Instant.now()).toSeconds(),
                ABSTAND_MS / 60_000);
    }

    /**
     * Wartet, ohne den Abbruchwunsch zu verschlucken.
     *
     * <p>Frueher fing jede Pause ihre {@link InterruptedException} an Ort und
     * Stelle. Beim Herunterfahren wartete der Durchlauf dadurch weiter Konto
     * fuer Konto ab, statt zum Ende zu kommen.</p>
     */
    private void atme(Duration dauer) {
        if (dauer.isNegative() || dauer.isZero()) {
            return;
        }
        try {
            Thread.sleep(dauer.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord Role Sync wurde unterbrochen", e);
        }
    }
}
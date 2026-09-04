package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import com.eve.own.auth.backend.domain.discord.service.DiscordErwaehnungen;
import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.PingZustand;
import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import com.eve.own.auth.backend.domain.fleet.repository.FleetPingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Die Regeln um den Ping herum: wer darf, wie oft, und was passiert, wenn
 * Discord nicht mitspielt.
 *
 * <p>Die Rechtepruefung steht hier im Dienst und nicht nur am Endpunkt. Die
 * Annotation am Controller gehoert zu <em>einem</em> Einstiegspunkt; sie faellt
 * bei einem Umbau lautlos weg. Was hier durchkommt, kommt ueberall durch - und
 * am Ende dieser Kette klingelt das Telefon jedes Corp-Mitglieds.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Regeln der Flotten-Pings")
class FleetPingServiceTest {

    private static final Long FC = 1001L;
    private static final Long ZWEITER_FC = 1002L;
    private static final Long DIREKTOR = 1003L;
    private static final Long MITGLIED = 1004L;
    private static final String NACHRICHT = "9998887776665554";
    /** Die eine im Auth hinterlegte - und damit einzige pingbare - Rolle. */
    private static final String ROLLE = "5555";

    @Mock private FleetPingRepository pingRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private DiscordBotService discord;
    @Mock private DiscordRoleMappingRepository rollenRepo;

    private FleetPingService dienst;

    @BeforeEach
    void setUp() {
        when(discord.istPingKanalKonfiguriert()).thenReturn(true);
        when(discord.posteInKanal(any(), any())).thenReturn(NACHRICHT);
        when(pingRepo.save(any(FleetPing.class))).thenAnswer(a -> a.getArgument(0));
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        when(rollenRepo.findAll()).thenReturn(List.of(zuordnung("ROLE_PING", ROLLE)));

        charakter(FC, "Erster FC", "ROLE_1337");
        charakter(ZWEITER_FC, "Zweiter FC", "ROLE_A38");
        charakter(DIREKTOR, "Der Direktor", SystemRoles.DIRECTOR);
        charakter(MITGLIED, "Gewoehnliches Mitglied", SystemRoles.USER, SystemRoles.MEMBER);

        dienst = neuerDienst(Duration.ofMinutes(1));
    }

    private FleetPingService neuerDienst(Duration wartezeit) {
        return new FleetPingService(pingRepo, characterRepo, discord, rollenRepo, ROLLE, wartezeit);
    }

    private static DiscordRoleMapping zuordnung(String authRolle, String discordId) {
        DiscordRoleMapping zuordnung = new DiscordRoleMapping();
        zuordnung.setAuthRole(authRolle);
        zuordnung.setDiscordRoleId(discordId);
        return zuordnung;
    }

    private void charakter(Long id, String name, String... rollen) {
        Character c = new Character();
        c.setId(id);
        c.setName(name);
        c.setRoles(Set.of(rollen));
        when(characterRepo.findById(id)).thenReturn(Optional.of(c));
    }

    private FleetPingService.PingBefehl befehl() {
        return new FleetPingService.PingBefehl("Roam", "Ferox Fleet", "Jita IV-4",
                Instant.parse("2026-09-03T19:00:00Z"), "Mumble", true, null, PingErwaehnung.HIER,
                null);
    }

    /** Ein bereits abgesetzter Ping des ersten FC. */
    private FleetPing bestehenderPing(Long besitzer, Instant abgesetzt) {
        FleetPing ping = new FleetPing();
        ping.setId(42L);
        ping.setFcCharacterId(besitzer);
        ping.setFcCharacterName("Erster FC");
        ping.setFleetType("Roam");
        ping.setFormupLocation("Jita IV-4");
        ping.setErwaehnung(PingErwaehnung.HIER);
        ping.setZustand(PingZustand.GEPOSTET);
        ping.setDiscordMessageId(NACHRICHT);
        ping.setCreatedAt(abgesetzt);
        ping.setUpdatedAt(abgesetzt);
        when(pingRepo.findById(42L)).thenReturn(Optional.of(ping));
        return ping;
    }

    // ==================================================================
    // Wer darf
    // ==================================================================

    @Test
    @DisplayName("laesst Director und die beiden FC-Rollen pingen")
    void erlaubtFleetStaff() {
        // Genau die drei Namen aus AccessRules.FLEET_STAFF. Wer sie dort
        // aendert, muss sie hier mitaendern - sonst kommt jemand am Endpunkt
        // vorbei und im Dienst nicht, oder schlimmer, umgekehrt.
        for (Long id : List.of(FC, ZWEITER_FC, DIREKTOR)) {
            assertThat(dienst.senden(id, befehl()).getDiscordMessageId()).isEqualTo(NACHRICHT);
        }
    }

    @Test
    @DisplayName("ohne FC-Rolle kein Ping")
    void ohneFcRolleKeinPing() {
        // OHNE DIESE REGEL koennte jedes angemeldete Mitglied die ganze
        // Corporation wecken - und zwar so oft, wie es den Knopf findet.
        assertThatThrownBy(() -> dienst.senden(MITGLIED, befehl()))
                .isInstanceOf(AccessDeniedException.class);

        // Und es geht nichts hinaus. Eine Rechtepruefung, die erst nach dem
        // Discord-Aufruf greift, ist keine.
        verify(discord, never()).posteInKanal(any(), any());
        verify(pingRepo, never()).save(any());
    }

    @Test
    @DisplayName("weist eine unbekannte Charakter-ID ab")
    void unbekannterCharakter() {
        // Nicht als AccessDeniedException: Der Unterschied zwischen "darf nicht"
        // und "gibt es nicht" gehoert in die Meldung, sonst sucht jemand nach
        // einer Berechtigung, die gar nicht das Problem ist.
        when(characterRepo.findById(7777L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dienst.senden(7777L, befehl()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // Fremde Pings
    // ==================================================================

    @Test
    @DisplayName("ein fremder Ping laesst sich nicht bearbeiten")
    void fremderPingNichtBearbeitbar() {
        bestehenderPing(FC, Instant.now());

        // OHNE DIESE REGEL koennte ein FC umschreiben, was ein Kollege unter
        // seinem eigenen Namen angekuendigt hat - und der Kollege erfuehre es
        // nicht einmal. Unter der Nachricht steht weiterhin sein Name.
        assertThatThrownBy(() -> dienst.bearbeiten(ZWEITER_FC, 42L, befehl()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Erster FC");

        verify(discord, never()).aendereImKanal(any(), any(), any());
        verify(pingRepo, never()).save(any());
    }

    @Test
    @DisplayName("ein fremder Ping laesst sich nicht absagen")
    void fremderPingNichtAbsagbar() {
        bestehenderPing(FC, Instant.now());

        assertThatThrownBy(() -> dienst.absagen(ZWEITER_FC, 42L, "weil"))
                .isInstanceOf(AccessDeniedException.class);

        verify(discord, never()).aendereImKanal(any(), any(), any());
    }

    @Test
    @DisplayName("der Direktor darf auch einen fremden Ping absagen")
    void direktorDarfFremdeAbsagen() {
        // Die Ausnahme mit dem praktischsten Grund: Der haeufigste Fall eines
        // falschen Pings ist der, bei dem der FC danach ausgeloggt ist. Ohne
        // jemanden, der eine tote Flotte absagen kann, steht sie bis zum
        // naechsten Login im Kanal - und Leute fliegen hin.
        bestehenderPing(FC, Instant.now());

        FleetPing abgesagt = dienst.absagen(DIREKTOR, 42L, "FC ist offline, Flotte faellt aus");

        assertThat(abgesagt.getZustand()).isEqualTo(PingZustand.ABGESAGT);
        assertThat(abgesagt.getCancelledAt()).isNotNull();
        verify(discord).aendereImKanal(org.mockito.ArgumentMatchers.eq(NACHRICHT), any(), any());
    }

    @Test
    @DisplayName("der eigene Ping laesst sich bearbeiten und behaelt seine Nachrichten-ID")
    void eigenerPingBearbeitbar() {
        bestehenderPing(FC, Instant.now());

        FleetPing geaendert = dienst.bearbeiten(FC, 42L,
                new FleetPingService.PingBefehl("Roam", "Ferox Fleet", "Amarr VIII",
                        null, "Mumble", true, null, PingErwaehnung.HIER, null));

        assertThat(geaendert.getFormupLocation()).isEqualTo("Amarr VIII");
        assertThat(geaendert.getZustand()).isEqualTo(PingZustand.GEAENDERT);
        // Dieselbe Nachricht, nicht eine zweite daneben: Zwei widerspruechliche
        // Pings im Kanal sind schlimmer als ein falscher, weil niemand weiss,
        // welcher gilt.
        assertThat(geaendert.getDiscordMessageId()).isEqualTo(NACHRICHT);
        verify(discord).aendereImKanal(org.mockito.ArgumentMatchers.eq(NACHRICHT), any(), any());
        verify(discord, never()).posteInKanal(any(), any());
    }

    @Test
    @DisplayName("ein abgesagter Ping laesst sich nicht wiederbeleben")
    void abgesagterPingBleibtAbgesagt() {
        FleetPing ping = bestehenderPing(FC, Instant.now());
        ping.setZustand(PingZustand.ABGESAGT);

        // Ein PATCH benachrichtigt niemanden. Eine Absage zurueck in eine
        // Ankuendigung zu verwandeln hiesse also: Im Kanal steht wieder eine
        // Flotte, und kein Mensch erfaehrt davon.
        assertThatThrownBy(() -> dienst.bearbeiten(FC, 42L, befehl()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.absagen(FC, 42L, "nochmal"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // Wartezeit
    // ==================================================================

    @Test
    @DisplayName("zwei Pings kurz hintereinander: der zweite wird abgewiesen")
    void zweiterPingZuFrueh() {
        // Der Anlass ist kein boeser Wille, sondern ein Doppelklick auf einen
        // Knopf, der ein paar hundert Millisekunden ueber Discord nachdenkt.
        // OHNE DIESE REGEL gehen daraus zwei @here hervor.
        FleetPing letzter = bestehenderPing(FC, Instant.now());
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(FC)).thenReturn(Optional.of(letzter));

        assertThatThrownBy(() -> dienst.senden(FC, befehl()))
                // Eigener Typ und keine IllegalStateException: aus der macht der
                // ApiExceptionHandler eine 500 - also "die Anwendung ist kaputt"
                // fuer eine Bremse, die genau so gegriffen hat, wie sie soll.
                // Daraus wird hier eine 429, an der das Frontend einen
                // Wartehinweis festmachen kann.
                .isInstanceOf(FleetPingWartezeitException.class)
                .hasMessageContaining("Sekunden");

        verify(discord, never()).posteInKanal(any(), any());
        verify(pingRepo, never()).save(any());
    }

    @Test
    @DisplayName("nach abgelaufener Wartezeit geht der naechste Ping wieder durch")
    void wartezeitLaeuftAb() {
        FleetPing alter = bestehenderPing(FC, Instant.now().minusSeconds(120));
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(FC)).thenReturn(Optional.of(alter));

        assertThat(dienst.senden(FC, befehl())).isNotNull();
    }

    @Test
    @DisplayName("die Wartezeit gilt je Charakter, nicht fuer alle FCs zusammen")
    void wartezeitGiltJeCharakter() {
        // Zwei FCs, zwei Flotten - das ist der Normalfall an einem Abend und
        // kein Doppelklick. Eine gemeinsame Bremse liesse den zweiten FC
        // warten, weil der erste gerade gepingt hat.
        FleetPing letzter = bestehenderPing(FC, Instant.now());
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(FC)).thenReturn(Optional.of(letzter));
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(ZWEITER_FC))
                .thenReturn(Optional.empty());

        assertThat(dienst.senden(ZWEITER_FC, befehl())).isNotNull();
    }

    @Test
    @DisplayName("eine auf Null gesetzte Wartezeit schaltet die Bremse ab")
    void wartezeitAbschaltbar() {
        // Der Weg dorthin fuehrt ueber die Konfiguration und nicht ueber die
        // Anfrage - sonst waere die Bremse eine, die der Bremsende selbst loest.
        FleetPingService ohneBremse = neuerDienst(Duration.ZERO);
        FleetPing letzter = bestehenderPing(FC, Instant.now());
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(FC)).thenReturn(Optional.of(letzter));

        assertThat(ohneBremse.senden(FC, befehl())).isNotNull();
    }

    @Test
    @DisplayName("das Bearbeiten laeuft nicht in die Wartezeit")
    void bearbeitenOhneWartezeit() {
        // Die Bremse soll den doppelten PING verhindern. Eine Aenderung ist
        // keiner - Discord benachrichtigt bei einem PATCH niemanden. Sie hier
        // mitzuziehen hiesse, einen falschen Treffpunkt eine Minute lang im
        // Kanal stehen zu lassen.
        FleetPing letzter = bestehenderPing(FC, Instant.now());
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(FC)).thenReturn(Optional.of(letzter));

        assertThat(dienst.bearbeiten(FC, 42L, befehl())).isNotNull();
    }

    // ==================================================================
    // Discord schlaegt fehl
    // ==================================================================

    @Test
    @DisplayName("schlaegt Discord fehl, entsteht kein Ping-Datensatz")
    void keinDatensatzOhneDiscord() {
        when(discord.posteInKanal(any(), any()))
                .thenThrow(HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "Discord kaputt", null, null, null));

        assertThatThrownBy(() -> dienst.senden(FC, befehl()))
                .isInstanceOf(HttpServerErrorException.class);

        // OHNE DIESE REIHENFOLGE stuende in der Rechenschaftsliste eine
        // Ankuendigung, von der der Kanal nie erfahren hat - und der FC saehe
        // seinen Ping in der Liste stehen, waehrend niemand ihn gelesen hat.
        verify(pingRepo, never()).save(any());
    }

    @Test
    @DisplayName("ist der Ping gesendet, ist die Nachrichten-ID gespeichert")
    void nachrichtenIdWirdGespeichert() {
        FleetPing gespeichert = dienst.senden(FC, befehl());

        // Die Umkehrung der vorigen Regel und genauso wichtig: Ohne die ID
        // laesst sich der Ping nie wieder korrigieren oder absagen, und eine
        // Flotte, die es nicht mehr gibt, steht fuer immer im Kanal.
        assertThat(gespeichert.getDiscordMessageId()).isEqualTo(NACHRICHT);
        verify(pingRepo).save(any(FleetPing.class));
    }

    @Test
    @DisplayName("schlaegt das Aendern in Discord fehl, bleibt der alte Stand gespeichert")
    void keinSpeichernOhneErfolgreicheAenderung() {
        bestehenderPing(FC, Instant.now());
        org.mockito.Mockito.doThrow(HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "Discord kaputt", null, null, null))
                .when(discord).aendereImKanal(any(), any(), any());

        assertThatThrownBy(() -> dienst.bearbeiten(FC, 42L, befehl()))
                .isInstanceOf(HttpServerErrorException.class);

        // Datensatz und Kanaltext bleiben so zusammen, wie sie waren. Ein
        // gespeicherter neuer Treffpunkt bei unveraendertem Kanaltext waere die
        // schlimmste Variante: Die Liste sagt A, die Leute lesen B.
        verify(pingRepo, never()).save(any());
    }

    // ==================================================================
    // Eingaben
    // ==================================================================

    @Test
    @DisplayName("ein Ping ohne Flottenart oder Treffpunkt wird abgewiesen")
    void pflichtfelder() {
        // Die beiden Angaben, ohne die niemand entscheiden kann, ob er andockt.
        // Ein Ping ohne sie waere Laerm ohne Auskunft.
        assertThatThrownBy(() -> dienst.senden(FC, new FleetPingService.PingBefehl(
                " ", null, "Jita", null, null, null, null, PingErwaehnung.STILL, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Flottenart");

        assertThatThrownBy(() -> dienst.senden(FC, new FleetPingService.PingBefehl(
                "Roam", null, null, null, null, null, null, PingErwaehnung.STILL, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Treffpunkt");

        verify(discord, never()).posteInKanal(any(), any());
    }

    @Test
    @DisplayName("ein zu langer Hinweis wird abgewiesen statt gekuerzt")
    void zuLangerHinweis() {
        // Discord lehnt eine Nachricht ueber 2000 Zeichen komplett ab - der Ping
        // ginge also gar nicht raus, und der FC saehe nur einen Fehler von
        // Discord. Hier bekommt er eine Meldung, mit der er etwas anfangen kann.
        String zuLang = "x".repeat(1001);

        assertThatThrownBy(() -> dienst.senden(FC, new FleetPingService.PingBefehl(
                "Roam", null, "Jita", null, null, null, zuLang, PingErwaehnung.STILL, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zu lang");
    }

    @Test
    @DisplayName("eine fehlende Erwaehnung faellt auf \"still\" zurueck und nie auf laut")
    void fehlendeErwaehnungIstStill() {
        // Die Richtung ist der ganze Punkt: Ein vergessenes Feld darf leiser
        // machen, nie lauter.
        FleetPing ping = dienst.senden(FC, new FleetPingService.PingBefehl(
                "Roam", null, "Jita", null, null, null, null, null, null));

        assertThat(ping.getErwaehnung()).isEqualTo(PingErwaehnung.STILL);
        verify(discord).posteInKanal(any(),
                org.mockito.ArgumentMatchers.argThat(DiscordErwaehnungen::istStill));
    }

    // ==================================================================
    // Abgeschaltet
    // ==================================================================

    @Test
    @DisplayName("ohne konfigurierte Kanal-ID meldet die Funktion sich sauber ab")
    void ohneKanalSauberAb() {
        when(discord.istPingKanalKonfiguriert()).thenReturn(false);

        assertThat(dienst.istVerfuegbar()).isFalse();
        assertThatThrownBy(() -> dienst.senden(FC, befehl()))
                // Ein eigener Typ und keine IllegalStateException: aus der macht
                // der ApiExceptionHandler eine 500 mit ERROR-Zeile - also
                // "die Anwendung ist kaputt" fuer eine fehlende
                // Umgebungsvariable. Daraus wird hier eine 503 mit Hinweis.
                .isInstanceOf(FleetPingAbgeschaltetException.class)
                .hasMessageContaining("DISCORD_FLEET_PING_CHANNEL_ID");

        verify(discord, never()).posteInKanal(any(), any());
        verify(pingRepo, never()).save(any());
    }

    @Test
    @DisplayName("die Rechenschaftsliste antwortet auch ohne eingerichteten Kanal")
    void listeLaeuftAuchAbgeschaltet() {
        // Ein Rechenschaftsbericht, der mit der Funktion ausfaellt, ueber die er
        // Rechenschaft ablegt, ist keiner.
        when(discord.istPingKanalKonfiguriert()).thenReturn(false);
        FleetPing vorhanden = bestehenderPing(FC, Instant.now());
        when(pingRepo.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(vorhanden));

        assertThat(dienst.letzte()).hasSize(1);
    }
}

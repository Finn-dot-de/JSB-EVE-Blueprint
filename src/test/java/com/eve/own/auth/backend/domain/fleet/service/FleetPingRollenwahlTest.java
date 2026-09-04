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
import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import com.eve.own.auth.backend.domain.fleet.repository.FleetPingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.web.client.ResourceAccessException;

/**
 * Welche Rolle ein Ping anleuchten darf - und welche nicht.
 *
 * <h2>Was sich geaendert hat und warum das gefaehrlich ist</h2>
 * <p>Bis vor kurzem stand die Rollenkennung in der Konfiguration. Sie war damit
 * unbestreitbar: Ein Aufrufer konnte sie nicht beeinflussen, und die Frage
 * "wessen Telefon klingelt" war beim Start der Anwendung beantwortet. Damit ein
 * FC gezielt eine bestimmte Gruppe rufen kann, kommt sie jetzt aus dem
 * <b>Request</b> - und ein Request ist nichts weiter als eine Behauptung.</p>
 *
 * <p>Ohne die Pruefung im Dienst waere aus "eine hinterlegte Gruppe rufen" ein
 * Werkzeug geworden, mit dem sich jede Rolle des Servers anleuchten laesst - die
 * der Serverleitung, die einer fremden Corporation im selben Discord - und ueber
 * ein hineingeschmuggeltes {@code <@...>} sogar eine einzelne Person. Der Kreis
 * derer, die pingen duerfen, bliebe dabei unveraendert; nur waere aus der Auswahl
 * ein Zielfernrohr geworden.</p>
 *
 * <p>Deshalb prueft jeder Test hier <b>zwei</b> Dinge: dass die Ausnahme fliegt,
 * und dass tatsaechlich nichts hinausgegangen ist. Eine Ausnahme nach dem
 * Discord-Aufruf waere keine Sperre, sondern eine Nachricht.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Die waehlbaren Ping-Rollen")
class FleetPingRollenwahlTest {

    private static final Long FC = 2001L;
    private static final Long MITGLIED = 2002L;
    private static final String NACHRICHT = "9998887776665554";

    /** Eine im Auth verknuepfte Rolle - und zugleich die vorbelegte. */
    private static final String AZUBI_ROLLE = "1539289011737329796";
    /** Eine zweite verknuepfte Rolle, damit "gezielt waehlen" ueberhaupt etwas heisst. */
    private static final String MARAUDER_ROLLE = "2222222222222222222";
    /** Existiert auf dem Discord-Server, ist im Auth aber NICHT verknuepft. */
    private static final String FREMDE_ROLLE = "3333333333333333333";

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

        charakter(FC, "Erster FC", "ROLE_1337");
        charakter(MITGLIED, "Gewoehnliches Mitglied", SystemRoles.USER, SystemRoles.MEMBER);

        // Der Bestand des Nutzers, nachgebaut: drei Zuordnungen, eine davon
        // ohne hinterlegte Discord-Kennung.
        zuordnungen(
                zuordnung("ROLE_CAP_AZUBI_PROGRAMM", AZUBI_ROLLE),
                zuordnung("ROLE_IT_ADMIN", null),
                zuordnung("ROLE_MARAUDERS_ASSOCIATED", MARAUDER_ROLLE));

        guildRollen(
                new DiscordBotService.GuildRole(AZUBI_ROLLE, "Cap Azubi Programm"),
                new DiscordBotService.GuildRole(MARAUDER_ROLLE, "Marauders Associated"),
                new DiscordBotService.GuildRole(FREMDE_ROLLE, "Serverleitung"));

        dienst = neuerDienst(AZUBI_ROLLE);
    }

    private FleetPingService neuerDienst(String vorbelegt) {
        return new FleetPingService(
                pingRepo, characterRepo, discord, rollenRepo, vorbelegt, Duration.ZERO);
    }

    private void charakter(Long id, String name, String... rollen) {
        Character c = new Character();
        c.setId(id);
        c.setName(name);
        c.setRoles(Set.of(rollen));
        when(characterRepo.findById(id)).thenReturn(Optional.of(c));
    }

    private static DiscordRoleMapping zuordnung(String authRolle, String discordId) {
        DiscordRoleMapping zuordnung = new DiscordRoleMapping();
        zuordnung.setAuthRole(authRolle);
        zuordnung.setDiscordRoleId(discordId);
        return zuordnung;
    }

    private void zuordnungen(DiscordRoleMapping... zeilen) {
        when(rollenRepo.findAll()).thenReturn(new ArrayList<>(List.of(zeilen)));
    }

    private void guildRollen(DiscordBotService.GuildRole... rollen) {
        when(discord.getGuildRoles()).thenReturn(List.of(rollen));
    }

    private FleetPingService.PingBefehl rollenPingAuf(String rolleId) {
        return new FleetPingService.PingBefehl("Home Defense", "Ferox Fleet", "Jita IV-4",
                Instant.parse("2026-09-03T19:00:00Z"), "Mumble", true, null,
                PingErwaehnung.ROLLE, rolleId);
    }

    /** Weder eine Discord-Nachricht noch ein Datensatz - die Probe auf "nichts ging hinaus". */
    private void nichtsGingHinaus() {
        verify(discord, never()).posteInKanal(any(), any());
        verify(pingRepo, never()).save(any());
    }

    // ==================================================================
    // Die Pruefung der gewaehlten Kennung
    // ==================================================================

    @Test
    @DisplayName("eine im Auth nicht hinterlegte Rollenkennung wird abgewiesen, und es geht nichts hinaus")
    void unbekannteRolleWirdAbgewiesen() {
        // FREMDE_ROLLE gibt es auf dem Discord-Server wirklich - sie ist im Auth
        // nur nicht verknuepft. Genau der interessante Fall: Die Kennung ist
        // echt, sie waere sofort wirksam, und trotzdem gehoert sie nicht zu dem,
        // was dieses Werkzeug anleuchten darf.
        assertThatThrownBy(() -> dienst.senden(FC, rollenPingAuf(FREMDE_ROLLE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht als Discord-Rolle hinterlegt");

        // OHNE DIESE ZEILEN waere der Test mit einer Ausnahme zufrieden, die
        // NACH dem Discord-Aufruf fliegt - die Rolle waere dann laengst
        // angeklingelt und der Fehler nur noch eine Nachricht an den FC.
        nichtsGingHinaus();
    }

    @Test
    @DisplayName("eine unbekannte Kennung wird abgewiesen und NICHT still auf die Vorbelegung zurueckgesetzt")
    void unbekannteRolleFaelltNichtStillAufDieVorbelegung() {
        assertThatThrownBy(() -> dienst.senden(FC, rollenPingAuf("404404404404404404")))
                .isInstanceOf(IllegalArgumentException.class);

        // OHNE DIESE ZEILE duerfte die Umsetzung den unbekannten Wert
        // stillschweigend durch die vorbelegte Rolle ersetzen. Der Ping ginge
        // dann hinaus - nur an jemand anderen, als der FC gewaehlt hat, und
        // niemand erfuehre davon. Still zu scheitern hat in diesem Projekt
        // gerade erst dazu gefuehrt, dass ein @everyone-Ping lautlos verpuffte.
        nichtsGingHinaus();
    }

    @Test
    @DisplayName("auch eine boesartige ZUORDNUNG kommt nicht durch")
    void boesartigeZuordnungWirdAbgewiesen() {
        // Der Fall, fuer den die Gestaltpruefung ueberhaupt existiert - und der
        // andere als der Angriff ueber das Formular: Hier steht der boesartige
        // Wert in der ZUORDNUNG selbst. Das Feld unter /admin/discord ist
        // freier Text, es schuetzt sich also nicht von allein.
        // Die Bekanntheitspruefung greift hier NICHT: der Wert ist ja
        // hinterlegt und damit "bekannt". Ohne die Pruefung der Gestalt
        // stuende im Nachrichtentext "<@&1539...796> <@444555666>" - die
        // erlaubte Rolle UND daneben eine einzelne Person, angeklingelt aus
        // einer Zeile, die niemand mehr liest, nachdem sie einmal
        // eingetragen wurde.
        String vergiftet = AZUBI_ROLLE + "> <@444555666";
        zuordnungen(zuordnung("ROLE_CAP_AZUBI_PROGRAMM", vergiftet));

        assertThatThrownBy(() -> dienst.senden(FC, rollenPingAuf(vergiftet)))
                .isInstanceOf(IllegalArgumentException.class);

        nichtsGingHinaus();
    }

    @Test
    @DisplayName("eine Kennung, die wie eine Nutzererwaehnung aussieht, wird abgewiesen")
    void nutzererwaehnungWirdAbgewiesen() {
        // Der eigentliche Angriff. Die Kennung landet in <@&...> im
        // Nachrichtentext, und dieser Teil des Textes wird bewusst NICHT
        // entschaerft - er ist ja die gewollte Erwaehnung. Aus
        // "<@&" + "1539...796> <@444555666" + ">" wuerden zwei Erwaehnungen:
        // die erlaubte Rolle und daneben eine EINZELNE PERSON.
        for (String angriff : List.of(
                AZUBI_ROLLE + "> <@444555666",
                "<@&" + AZUBI_ROLLE + ">",
                "<@444555666>",
                AZUBI_ROLLE + "\n<@444555666>")) {
            assertThatThrownBy(() -> dienst.senden(FC, rollenPingAuf(angriff)))
                    .as("Kennung '%s' darf nicht durchkommen", angriff)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // OHNE DIESE ZEILE koennte ein FC ueber das Rollenfeld gezielt eine
        // einzelne Person anklingeln - jemanden, den dieses Werkzeug nie
        // anpingen sollte, und ohne dass es in der Rechenschaftsliste
        // erkennbar waere.
        nichtsGingHinaus();
    }

    @Test
    @DisplayName("die gewaehlte hinterlegte Rolle kommt durch und steht im Datensatz")
    void bekannteRolleKommtDurch() {
        // Die Gegenprobe. Eine Sperre, die alles abweist, ist keine Sperre,
        // sondern ein Abschalter - und faellt in keinem der Tests darueber auf.
        FleetPing ping = dienst.senden(FC, rollenPingAuf(MARAUDER_ROLLE));

        assertThat(ping.getErwaehnungRolleId()).isEqualTo(MARAUDER_ROLLE);
        // Nicht die vorbelegte: Der FC hat gezielt eine andere Gruppe gerufen,
        // und genau das war der Sinn des Umbaus.
        assertThat(ping.getErwaehnungRolleId()).isNotEqualTo(AZUBI_ROLLE);
        verify(discord).posteInKanal(any(), any());
    }

    @Test
    @DisplayName("ohne eigene Angabe greift die Vorbelegung aus der Konfiguration")
    void ohneAngabeGreiftDieVorbelegung() {
        // Der Normalfall und der Grund, warum DISCORD_FLEET_PING_ROLE_ID
        // ueberhaupt geblieben ist: Ein Aufrufer, der nichts angibt - ein altes
        // Frontend, ein Skript - bekommt weiterhin die Rolle, die der
        // Administrator hinterlegt hat.
        assertThat(dienst.senden(FC, rollenPingAuf(null)).getErwaehnungRolleId())
                .isEqualTo(AZUBI_ROLLE);
    }

    @Test
    @DisplayName("eine Vorbelegung, die in den Zuordnungen fehlt, wird genauso abgewiesen wie eine aus der Anfrage")
    void toteVorbelegungWirdAbgewiesen() {
        // Der Fall, nach dem niemand fragt, bis er eintritt: In
        // DISCORD_FLEET_PING_ROLE_ID steht eine Kennung, die in den Zuordnungen
        // gar nicht vorkommt - eine geloeschte Rolle, ein Tippfehler, oder eine
        // aus der Zeit vor diesem Umbau.
        FleetPingService mitToterVorbelegung = neuerDienst(FREMDE_ROLLE);

        assertThatThrownBy(() -> mitToterVorbelegung.senden(FC, rollenPingAuf(null)))
                .isInstanceOf(IllegalArgumentException.class)
                // Die Meldung nennt die Umgebungsvariable, weil den Fall ein
                // Administrator repariert und nicht der FC vor dem Formular.
                .hasMessageContaining("DISCORD_FLEET_PING_ROLE_ID");

        // OHNE DIESE ZEILE duerfte die Vorbelegung an der Pruefung vorbeilaufen.
        // Dann gaebe es zwei Wege zu einer Erwaehnung - die gepruefte Auswahl
        // und einen stillen Sonderweg fuer eine Kennung, die in keiner Liste
        // steht und die deshalb auch in keiner Liste mehr auffaellt.
        nichtsGingHinaus();
    }

    @Test
    @DisplayName("ohne jede Rolle scheitert ein Rollen-Ping laut statt still zu verpuffen")
    void ohneRolleWirdAbgewiesen() {
        FleetPingService ohneVorbelegung = neuerDienst("");

        assertThatThrownBy(() -> ohneVorbelegung.senden(FC, rollenPingAuf(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rollen-Ping");

        // OHNE DIESE ZEILE ginge der Ping als STILLER Ping hinaus: Der FC saehe
        // eine Erfolgsmeldung, die Nachricht stuende im Kanal, und niemand
        // waere benachrichtigt. Genau so ist hier schon einmal ein Ping
        // lautlos verpufft.
        nichtsGingHinaus();
    }

    @Test
    @DisplayName("wechselt ein Ping von \"Rolle\" auf \"still\", bleibt keine Rolle im Datensatz stehen")
    void wechselDerLautstaerkeRaeumtDieRolleAb() {
        FleetPing ping = dienst.senden(FC, rollenPingAuf(MARAUDER_ROLLE));
        ping.setId(42L);
        when(pingRepo.findById(42L)).thenReturn(Optional.of(ping));

        FleetPing geaendert = dienst.bearbeiten(FC, 42L,
                new FleetPingService.PingBefehl("Home Defense", "Ferox Fleet", "Jita IV-4",
                        null, "Mumble", true, null, PingErwaehnung.STILL, MARAUDER_ROLLE));

        // OHNE DIESE ZEILE behauptete die Rechenschaftsliste weiterhin eine
        // angeleuchtete Rolle, obwohl dieser Ping niemanden mehr erwaehnt.
        assertThat(geaendert.getErwaehnungRolleId()).isNull();
    }

    // ==================================================================
    // Die Liste der waehlbaren Rollen
    // ==================================================================

    @Test
    @DisplayName("ohne FC-Rolle gibt es die Liste der pingbaren Rollen nicht")
    void ohneFcRolleKeineListe() {
        assertThatThrownBy(() -> dienst.pingbareRollen(MITGLIED))
                .isInstanceOf(AccessDeniedException.class);

        // OHNE DIESE ZEILE haenge die Regel allein an der Annotation des
        // Controllers. Die gehoert zu EINEM Einstiegspunkt und faellt bei einem
        // Umbau lautlos weg - dieselbe Ueberlegung wie beim Absetzen. Nebenbei:
        // Es wird auch nicht erst bei Discord nachgefragt und danach abgewiesen.
        verify(discord, never()).getGuildRoles();
    }

    @Test
    @DisplayName("die Liste zeigt die echten Discord-Rollennamen, nicht die nackten Kennungen")
    void listeZeigtDiscordNamen() {
        List<FleetPingService.PingRolle> rollen = dienst.pingbareRollen(FC);

        // OHNE DIESE ZEILE stuende im Auswahlfeld eine achtzehnstellige Zahl.
        // Niemand weiss, welche Rolle 1539289011737329796 ist - und wer raet,
        // ruft die falsche Gruppe.
        assertThat(rollen).extracting(FleetPingService.PingRolle::name)
                .containsExactly("Cap Azubi Programm", "Marauders Associated");
        assertThat(rollen).extracting(FleetPingService.PingRolle::vorbelegt)
                .containsExactly(true, false);
    }

    @Test
    @DisplayName("Zuordnungen ohne Discord-Kennung erscheinen nicht in der Auswahl")
    void zuordnungOhneKennungFehltInDerAuswahl() {
        zuordnungen(
                zuordnung("ROLE_CAP_AZUBI_PROGRAMM", AZUBI_ROLLE),
                zuordnung("ROLE_IT_ADMIN", null),
                zuordnung("ROLE_OHNE_ROLLE", "   "),
                zuordnung("ROLE_KEINE_KENNUNG", "noch nicht eingetragen"));

        List<FleetPingService.PingRolle> rollen = dienst.pingbareRollen(FC);

        // OHNE DIESE ZEILE stuenden Auth-Rollen zur Auswahl, denen im Discord
        // gar keine Rolle gegenuebersteht. Der FC waehlte eine davon, saehe eine
        // Erfolgsmeldung - und der Ping ginge ins Leere.
        assertThat(rollen).extracting(FleetPingService.PingRolle::authRole)
                .containsExactly("ROLE_CAP_AZUBI_PROGRAMM");
    }

    @Test
    @DisplayName("eine im Discord geloeschte Rolle steht nicht mehr zur Auswahl")
    void geloeschteRolleFehltInDerAuswahl() {
        // Die Zuordnung bleibt stehen, wenn jemand die Rolle in Discord
        // loescht - und sieht dort weiter gueltig aus. Beim Neuanlegen unter
        // demselben Namen vergibt Discord eine NEUE Kennung; die alte zeigt ab
        // dann auf nichts.
        guildRollen(new DiscordBotService.GuildRole(AZUBI_ROLLE, "Cap Azubi Programm"));

        assertThat(dienst.pingbareRollen(FC))
                .extracting(FleetPingService.PingRolle::discordRoleId)
                // OHNE DIESE ZEILE boete die Auswahl eine Rolle an, die es auf
                // dem Server nicht mehr gibt. Discord nimmt die Nachricht an,
                // loest die Erwaehnung nicht auf, und niemand wird
                // benachrichtigt - ein Ping, der aussieht wie einer.
                .containsExactly(AZUBI_ROLLE);
    }

    @Test
    @DisplayName("ist Discord nicht erreichbar, kommt die Liste trotzdem - mit den Auth-Rollennamen")
    void ohneDiscordBleibtDieListeBenutzbar() {
        when(discord.getGuildRoles())
                .thenThrow(new ResourceAccessException("Verbindung zu Discord fehlgeschlagen"));

        List<FleetPingService.PingRolle> rollen = dienst.pingbareRollen(FC);

        // OHNE DIESE ZEILEN gaebe es zwei schlechte Antworten: eine Ausnahme
        // (dann kann der FC nicht pingen, obwohl das Pingen selbst noch geht)
        // oder eine leere Liste (dann sieht er ein leeres Auswahlfeld und
        // glaubt, es sei nichts hinterlegt). Der Name ist Beiwerk; die Auswahl
        // ist es nicht.
        assertThat(rollen).extracting(FleetPingService.PingRolle::name)
                .containsExactly("ROLE_CAP_AZUBI_PROGRAMM", "ROLE_MARAUDERS_ASSOCIATED");
        assertThat(rollen).extracting(FleetPingService.PingRolle::discordRoleId)
                .containsExactly(AZUBI_ROLLE, MARAUDER_ROLLE);

        // Und ausdruecklich: Der Ausfall filtert NICHT. Waere eine leere
        // Rollenliste dasselbe wie "keine dieser Rollen existiert", dann leerte
        // ein Discord-Ausfall die Auswahl vollstaendig.
        assertThat(rollen).hasSize(2);
    }

    @Test
    @DisplayName("zwei Auth-Rollen auf derselben Discord-Rolle stehen nur einmal in der Auswahl")
    void doppelteKennungNurEinmal() {
        zuordnungen(
                zuordnung("ROLE_MARAUDERS_ASSOCIATED", MARAUDER_ROLLE),
                zuordnung("ROLE_MARAUDER_ALT", MARAUDER_ROLLE));

        // OHNE DIESE ZEILE stuenden zwei Eintraege mit demselben Namen und
        // derselben Wirkung im Auswahlfeld, und der FC muesste raten, welcher
        // der richtige ist.
        assertThat(dienst.pingbareRollen(FC)).hasSize(1);
    }

    @Test
    @DisplayName("ohne verknuepfte Discord-Rolle meldet der Status die Auswahl als unbenutzbar")
    void statusOhneZuordnungen() {
        assertThat(dienst.istRolleKonfiguriert()).isTrue();

        zuordnungen(zuordnung("ROLE_IT_ADMIN", null));

        // OHNE DIESE ZEILE boete das Formular die Auswahl "Rolle" an, hinter
        // der ein leeres Auswahlfeld steht. Die Frage lautet seit dem Umbau
        // nicht mehr "steht eine ID in der .env", sondern "gibt es ueberhaupt
        // etwas zu waehlen".
        assertThat(dienst.istRolleKonfiguriert()).isFalse();
        // Und sie beantwortet sich ohne Discord: Der Kreis der Leser ist hier
        // weiter als der der Pinger, und die Auskunft darf nicht daran haengen,
        // ob der Bot gerade erreichbar ist.
        verify(discord, never()).getGuildRoles();
    }
}

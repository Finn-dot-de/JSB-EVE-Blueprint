package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Die Sperre zwischen dem Text eines Pings und der Klingel jedes Corp-Mitglieds.
 *
 * <p>Discord wertet Erwaehnungen <b>aus dem Fliesstext</b> aus. Ein FC, der
 * "@everyone" in das Notizfeld schreibt, pingt damit die ganze Corporation -
 * auch wenn er "still" angeklickt hat, denn die Auswahl im Frontend interessiert
 * Discord nicht. Was Discord interessiert, ist das Feld
 * {@code allowed_mentions}: Es sagt, welche Erwaehnungen ueberhaupt aufgeloest
 * werden duerfen. Fehlt es, gilt Discords grosszuegige Vorgabe - alles.</p>
 *
 * <p>Deshalb wird hier nicht der Rueckgabewert einer Methode geprueft, sondern
 * <b>der Rumpf der HTTP-Anfrage, die tatsaechlich Richtung Discord geht</b>. Das
 * ist die einzige Ebene, auf der die Frage "wird jemand geweckt" ehrlich
 * beantwortet wird; jede Abstraktion darueber koennte das Feld unterwegs
 * verlieren, ohne dass ein Test es merkt.</p>
 *
 * <p>Das ist der Unterschied zwischen einem Werkzeug und einer Waffe, die jedes
 * Corp-Mitglied nachts um drei ausloesen kann.</p>
 */
class FleetPingErwaehnungTest {

    private static final String KANAL = "555000111";
    private static final String ROLLE = "999888777";
    /** Eine zweite hinterlegte Rolle - die, die der FC im Formular auswaehlt. */
    private static final String GEWAEHLTE_ROLLE = "111000222";
    private static final String NACHRICHTEN_URL = "https://discord.com/api/v10/channels/" + KANAL
            + "/messages";
    private static final Long FC_ID = 90_000_001L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockRestServiceServer server;
    private FleetPingRepository pingRepo;

    /** Faengt den Rumpf der ausgehenden Anfrage ab - darum geht es hier. */
    private final AtomicReference<String> gesendeterRumpf = new AtomicReference<>();

    private FleetPingService dienst(String kanalId) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        DiscordBotService bot = new DiscordBotService(builder, "token", "42", "cid", "secret", kanalId);

        pingRepo = mock(FleetPingRepository.class);
        when(pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(FC_ID)).thenReturn(Optional.empty());
        when(pingRepo.save(any(FleetPing.class))).thenAnswer(aufruf -> aufruf.getArgument(0));

        Character fc = new Character();
        fc.setId(FC_ID);
        fc.setName("Aiko Danuja");
        fc.setRoles(Set.of("ROLE_1337"));
        CharacterRepository characterRepo = mock(CharacterRepository.class);
        when(characterRepo.findById(FC_ID)).thenReturn(Optional.of(fc));

        return new FleetPingService(
                pingRepo, characterRepo, bot, zuordnungenMit(ROLLE, GEWAEHLTE_ROLLE),
                ROLLE, Duration.ZERO);
    }

    /**
     * Die im Auth gepflegten Zuordnungen - ab jetzt die Liste der ueberhaupt
     * pingbaren Rollen.
     */
    private static DiscordRoleMappingRepository zuordnungenMit(String... discordRollenIds) {
        DiscordRoleMappingRepository repo = mock(DiscordRoleMappingRepository.class);
        List<DiscordRoleMapping> zeilen = new java.util.ArrayList<>();
        for (int i = 0; i < discordRollenIds.length; i++) {
            DiscordRoleMapping zuordnung = new DiscordRoleMapping();
            zuordnung.setAuthRole("ROLE_PING_" + i);
            zuordnung.setDiscordRoleId(discordRollenIds[i]);
            zeilen.add(zuordnung);
        }
        when(repo.findAll()).thenReturn(zeilen);
        return repo;
    }

    /** Erwartet den POST und legt seinen Rumpf fuer die Auswertung beiseite. */
    private void erwartePost() {
        server.expect(requestTo(NACHRICHTEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(anfrage ->
                        gesendeterRumpf.set(((MockClientHttpRequest) anfrage).getBodyAsString()))
                .andRespond(withSuccess("{\"id\":\"1234567890\"}", MediaType.APPLICATION_JSON));
    }

    private JsonNode erlaubteErwaehnungen() {
        JsonNode rumpf = JSON.readTree(gesendeterRumpf.get());
        assertThat(rumpf.has("allowed_mentions"))
                .as("""
                        Ohne das Feld allowed_mentions gilt Discords Vorgabe, und die lautet \
                        "alles, was im Text steht". Ein einziger vergessener Aufruf reicht, damit \
                        ein Notizfeld die ganze Corporation weckt.""")
                .isTrue();
        return rumpf.get("allowed_mentions");
    }

    private FleetPingService.PingBefehl befehlMit(String notiz, PingErwaehnung erwaehnung) {
        return befehlMit(notiz, erwaehnung, null);
    }

    private FleetPingService.PingBefehl befehlMit(String notiz, PingErwaehnung erwaehnung,
                                                  String rolleId) {
        return new FleetPingService.PingBefehl(
                "Home Defense", "Ferox Fleet", "Jita IV-4",
                Instant.parse("2026-09-03T19:00:00Z"), "Mumble Kanal 1", true, notiz, erwaehnung,
                rolleId);
    }

    @Test
    @DisplayName("@everyone im freien Text loest bei Auswahl \"still\" KEINE Erwaehnung aus")
    void stillBleibtStillTrotzEveryoneImText() {
        FleetPingService dienst = dienst(KANAL);
        erwartePost();

        // Der Angriff in seiner einfachsten Form: Die Auswahl ist "still", aber
        // im Freitext steht die lauteste Erwaehnung, die Discord kennt.
        dienst.senden(FC_ID, befehlMit("Bitte kommen @everyone, es eilt!", PingErwaehnung.STILL));

        server.verify();
        JsonNode erlaubt = erlaubteErwaehnungen();

        // OHNE DIESE ZUSICHERUNG genuegt ein leeres Notizfeld-Wort, um jedes
        // Mitglied der Corporation zu benachrichtigen - der Ping waere dann
        // nicht mehr "still", sondern lauter als jede Auswahl im Frontend.
        assertThat(erlaubt.get("parse").size()).isZero();
        assertThat(erlaubt.get("roles").size()).isZero();

        // Und zweitens: Der Text selbst kann gar keine Erwaehnung mehr sein.
        // Zwei Schloesser, weil das erste bei der Auswahl "@here" zwangslaeufig
        // offen steht - siehe den naechsten Test.
        String inhalt = JSON.readTree(gesendeterRumpf.get()).get("content").asString();
        assertThat(inhalt).doesNotContain("@everyone");
        assertThat(inhalt).contains("everyone");
    }

    @Test
    @DisplayName("Die lauteste Stufe weckt jeden - aber nur einmal")
    void alleWecktJedenUndZwarNurEinmal() {
        // Die lauteste Stufe, und die gibt es auf ausdruecklichen Wunsch:
        // Homedefense holt Leute aus dem Feierabend, dafuer ist sie da.
        FleetPingService dienst = dienst(KANAL);
        erwartePost();

        dienst.senden(FC_ID, befehlMit("Rasseln @everyone bitte kommen", PingErwaehnung.JEDER));

        server.verify();
        String inhalt = JSON.readTree(gesendeterRumpf.get()).get("content").asString();

        // Das gewaehlte @everyone steht vorn und wirkt.
        assertThat(inhalt).startsWith("@everyone ");
        // OHNE DIE ENTSCHAERFUNG stuende hier ein ZWEITES @everyone aus dem
        // Freitext. Discord klingelt zwar nur einmal, aber die Nachricht
        // saehe aus, als haette jemand geschrien - und beim naechsten Ping
        // mit Auswahl "still" waere derselbe Text eine echte Erwaehnung.
        assertThat(inhalt.split("@everyone", -1).length - 1)
                .as("genau eine Erwaehnung, und zwar die gewaehlte")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("bei Auswahl \"@here\" wird aus einem @everyone im Text kein @everyone-Ping")
    void hierWirdNichtHeimlichZuEveryone() {
        FleetPingService dienst = dienst(KANAL);
        erwartePost();

        dienst.senden(FC_ID, befehlMit("und zwar @everyone", PingErwaehnung.HIER));

        server.verify();
        // Discord kennt in allowed_mentions keinen Schalter, der @here erlaubt
        // und @everyone verbietet - beides ist die Gattung "everyone". Bei
        // dieser Auswahl steht das Schloss also offen, und allein die
        // Entschaerfung des Textes haelt den Unterschied aufrecht.
        assertThat(erlaubteErwaehnungen().get("parse").size()).isEqualTo(1);

        String inhalt = JSON.readTree(gesendeterRumpf.get()).get("content").asString();
        // OHNE DIESE ZEILE waere "@here" in Wahrheit nur eine Untergrenze: Wer
        // sie waehlt, koennte ueber das Notizfeld auf @everyone hochgehen und
        // damit auch jeden wecken, der offline ist.
        assertThat(inhalt).doesNotContain("@everyone");
        // Das selbst gesetzte @here bleibt dagegen unangetastet - es kommt aus
        // der Auswahl und nicht aus dem Text.
        assertThat(inhalt).startsWith("@here ");
    }

    @Test
    @DisplayName("bei Auswahl \"Rolle\" ist genau die konfigurierte Rolle erlaubt und sonst nichts")
    void rolleErlaubtNurDieKonfigurierte() {
        FleetPingService dienst = dienst(KANAL);
        erwartePost();

        // Im Text steht die Erwaehnung einer FREMDEN Rolle in maschinenlesbarer
        // Form - so, wie man sie aus Discord herauskopiert.
        dienst.senden(FC_ID, befehlMit("cc <@&111222333>", PingErwaehnung.ROLLE));

        server.verify();
        JsonNode erlaubt = erlaubteErwaehnungen();

        // parse bleibt leer: Stuende dort "roles", duerfte Discord JEDE im Text
        // genannte Rolle aufloesen, und die Aufzaehlung waere Zierde.
        assertThat(erlaubt.get("parse").size()).isZero();
        assertThat(erlaubt.get("roles").size()).isEqualTo(1);
        assertThat(erlaubt.get("roles").get(0).asString()).isEqualTo(ROLLE);

        String inhalt = JSON.readTree(gesendeterRumpf.get()).get("content").asString();
        assertThat(inhalt).startsWith("<@&" + ROLLE + "> ");
    }

    @Test
    @DisplayName("auch die Absage schickt allowed_mentions mit - und zwar leer")
    void absageIstStill() {
        FleetPingService dienst = dienst(KANAL);
        // Beide Erwartungen VOR dem ersten Aufruf: MockRestServiceServer nimmt
        // nach der ersten tatsaechlichen Anfrage keine neuen mehr an.
        erwartePost();
        server.expect(requestTo(NACHRICHTEN_URL + "/1234567890"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(anfrage ->
                        gesendeterRumpf.set(((MockClientHttpRequest) anfrage).getBodyAsString()))
                .andRespond(withSuccess());

        FleetPing ping = dienst.senden(FC_ID, befehlMit("los geht's", PingErwaehnung.HIER));
        ping.setId(7L);
        when(pingRepo.findById(7L)).thenReturn(Optional.of(ping));

        dienst.absagen(FC_ID, 7L, "Ziel ist weg");

        server.verify();
        // OHNE DIESE ZEILE koennte eine Absage lauter sein als der Ping, den sie
        // zuruecknimmt: Der durchgestrichene Text enthaelt weiterhin das Wort
        // "here", und ohne gesetztes Feld wuerde Discord es wieder aufloesen.
        JsonNode erlaubt = erlaubteErwaehnungen();
        assertThat(erlaubt.get("parse").size()).isZero();
        assertThat(erlaubt.get("roles").size()).isZero();
    }

    @Test
    @DisplayName("allowed_mentions enthaelt genau die gewaehlte Rolle und nicht die Gattung \"roles\"")
    void gewaehlteRolleUndNichtDieGattung() {
        FleetPingService dienst = dienst(KANAL);
        erwartePost();

        // Der FC waehlt gezielt eine ANDERE als die vorbelegte Rolle - seit dem
        // Umbau kommt die Kennung aus der Anfrage. Im Freitext steht daneben
        // eine dritte, fremde Rolle in maschinenlesbarer Form.
        dienst.senden(FC_ID, befehlMit("cc <@&111222333>", PingErwaehnung.ROLLE, GEWAEHLTE_ROLLE));

        server.verify();
        JsonNode erlaubt = erlaubteErwaehnungen();

        // OHNE DIESE ZEILE duerfte in parse die Gattung "roles" stehen. Dann
        // zoege Discord JEDE im Freitext genannte Rolle heraus, die Aufzaehlung
        // darunter waere Zierde, und aus "eine bestimmte Gruppe rufen" wuerde
        // "jede Gruppe, die jemand ins Notizfeld kopiert".
        assertThat(erlaubt.get("parse").size()).isZero();

        // Genau eine, und zwar die gewaehlte. Nicht die vorbelegte aus der
        // Konfiguration, nicht die aus dem Freitext.
        assertThat(erlaubt.get("roles").size()).isEqualTo(1);
        assertThat(erlaubt.get("roles").get(0).asString()).isEqualTo(GEWAEHLTE_ROLLE);
        assertThat(erlaubt.get("roles").get(0).asString()).isNotEqualTo(ROLLE);

        String inhalt = JSON.readTree(gesendeterRumpf.get()).get("content").asString();
        assertThat(inhalt).startsWith("<@&" + GEWAEHLTE_ROLLE + "> ");
        // Und die fremde Rolle aus dem Freitext ist entschaerft - sie steht
        // lesbar da, ist aber keine Erwaehnung mehr.
        assertThat(inhalt).doesNotContain("<@&111222333>");
    }

    @Test
    @DisplayName("eine nicht hinterlegte Rollenkennung erreicht Discord gar nicht erst")
    void unbekannteRolleErreichtDiscordNicht() {
        FleetPingService dienst = dienst(KANAL);
        // KEINE erwartete Anfrage: Der MockRestServiceServer meldet jede
        // ungeplante als Fehler. Genau das ist hier die Zusicherung.

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> dienst.senden(FC_ID,
                                befehlMit("egal", PingErwaehnung.ROLLE, "424242424242424242"))))
                .isInstanceOf(IllegalArgumentException.class);

        // OHNE DIESE ZEILEN koennte die Pruefung erst NACH dem Discord-Aufruf
        // stehen. Die fremde Rolle waere dann angeklingelt, und die Ausnahme
        // waere nur noch eine Meldung an den FC.
        server.verify();
        org.mockito.Mockito.verify(pingRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("ohne konfigurierte Kanal-ID meldet die Funktion sich sauber ab")
    void ohneKanalKeinPing() {
        // Leere Kanal-ID heisst: nicht eingerichtet. Genau der Zustand, in dem
        // die Anwendung frisch hochkommt, bevor jemand die .env ergaenzt.
        FleetPingService dienst = dienst("");

        assertThat(dienst.istVerfuegbar())
                .as("Der Statusendpunkt muss die Funktion als abwesend melden koennen, "
                        + "damit das Frontend den Knopf gar nicht erst anbietet.")
                .isFalse();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> dienst.senden(FC_ID, befehlMit("egal", PingErwaehnung.HIER))))
                // Ein eigener Typ und keine IllegalStateException: aus der macht
                // der ApiExceptionHandler eine 500 samt ERROR-Zeile - also die
                // Meldung "die Anwendung ist kaputt" fuer eine fehlende
                // Umgebungsvariable. Hier wird daraus eine 503 mit Hinweis.
                .isInstanceOf(FleetPingAbgeschaltetException.class)
                .hasMessageContaining("DISCORD_FLEET_PING_CHANNEL_ID");

        // Und es geht weder eine Anfrage hinaus noch entsteht ein Datensatz.
        server.verify();
        org.mockito.Mockito.verify(pingRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("die Erwaehnung entsteht aus der Auswahl und nie aus einem unbekannten Wert")
    void unbekannteAuswahlFaelltAufStill() {
        // Der Weg, auf dem die Zeichenkette des Frontends zur Lautstaerke wird.
        // Ein Tippfehler muss leiser machen, nie lauter - sonst entscheidet ein
        // Rechtschreibfehler ueber die Nachtruhe der Corporation.
        assertThat(PingErwaehnung.of("HIER")).isEqualTo(PingErwaehnung.HIER);
        assertThat(PingErwaehnung.of("hier")).isEqualTo(PingErwaehnung.HIER);
        assertThat(PingErwaehnung.of("JEDER")).isEqualTo(PingErwaehnung.JEDER);
        assertThat(PingErwaehnung.of("jeder")).isEqualTo(PingErwaehnung.JEDER);
        // Die lauteste Stufe heisst JEDER und nicht EVERYONE. Wer den
        // englischen Namen schickt, bekommt einen STILLEN Ping - unschoen,
        // aber in der sicheren Richtung. Das Frontend muss JEDER senden;
        // faellt es je auf STILL zurueck, ist das der Grund.
        assertThat(PingErwaehnung.of("EVERYONE")).isEqualTo(PingErwaehnung.STILL);
        assertThat(PingErwaehnung.of(null)).isEqualTo(PingErwaehnung.STILL);
        assertThat(PingErwaehnung.of("")).isEqualTo(PingErwaehnung.STILL);
        assertThat(List.of(PingErwaehnung.values())).hasSize(4);
    }
}

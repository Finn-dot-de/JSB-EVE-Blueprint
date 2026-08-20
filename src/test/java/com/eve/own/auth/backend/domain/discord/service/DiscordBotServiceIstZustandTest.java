package com.eve.own.auth.backend.domain.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Der erste lesende Aufruf gegen Discord.
 *
 * <p>Bis hierher schrieb das Auth nur: vier Aufrufe, alle setzend, keiner
 * fragend. Was aus einem PUT wurde, stand nirgends - ein 403 je Rolle ging als
 * WARN ins Log und war danach vergessen. Ohne diesen Aufruf laesst sich gar
 * nicht feststellen, ob Discord traegt, was das Auth vorsieht.</p>
 */
class DiscordBotServiceIstZustandTest {

    private static final String GUILD = "42";
    private static final String USER = "777";
    private static final String MITGLIED =
            "https://discord.com/api/v10/guilds/" + GUILD + "/members/" + USER;

    private MockRestServiceServer server;

    private DiscordBotService dienst() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new DiscordBotService(builder, "token", GUILD, "cid", "secret");
    }

    @Test
    @DisplayName("liest die Rollenliste des Mitglieds per GET")
    void liestRollen() {
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"roles": ["1000", "2000"], "nick": "Irgendwer"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(bot.getMemberRoles(USER)).containsExactly("1000", "2000");

        // GET und sonst nichts. Eine Pruefung, die beim Pruefen schreibt, kann
        // man nicht gefahrlos laufen lassen - ein PUT oder PATCH waere hier ein
        // unerwarteter zweiter Aufruf und liesse den Test scheitern.
        server.verify();
    }

    @Test
    @DisplayName("stolpert nicht ueber Felder, die Discord zusaetzlich schickt")
    void unbekannteFelderStoerenNicht() {
        // Die echte Antwort traegt ein Dutzend weiterer Felder, und Discord darf
        // jederzeit neue hinzufuegen. Ohne ignoreUnknown brauchte es nur ein
        // neues Feld in der API, und die Pruefung faellt aus - fuer alle Konten
        // gleichzeitig, mit einer Meldung, die nach Rollenfehler aussieht.
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED)).andRespond(withSuccess("""
                {"user": {"id": "777", "username": "x"}, "roles": ["1000"],
                 "joined_at": "2026-01-01T00:00:00Z", "flags": 0, "brandneu": true}
                """, MediaType.APPLICATION_JSON));

        assertThat(bot.getMemberRoles(USER)).containsExactly("1000");
    }

    @Test
    @DisplayName("reicht die verweigerte Auskunft weiter, statt eine leere Liste zu liefern")
    void verweigerungWirdNichtGeschluckt() {
        // Der Unterschied, auf dem die ganze Pruefung steht: "keine Rollen" und
        // "durfte nicht nachsehen" sind nicht dasselbe. Wuerde hier eine leere
        // Liste zurueckkommen, meldete die Pruefung anschliessend jede
        // Soll-Rolle als fehlend - ausgerechnet am Server-Owner, an dem sich
        // ohnehin nichts aendern laesst.
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> bot.getMemberRoles(USER))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @DisplayName("liefert eine leere Liste, wenn Discord gar kein roles-Feld schickt")
    void ohneRollenfeldLeereListe() {
        // Nicht dasselbe wie der 403-Fall: Hier hat Discord geantwortet, das
        // Mitglied traegt nur nichts. Ohne die Absicherung liefe die Auswertung
        // in eine NullPointerException und riss den ganzen Durchlauf mit.
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(bot.getMemberRoles(USER)).isEmpty();
    }

    @Test
    @DisplayName("vertraegt eine leere Antwort")
    void leererKoerper() {
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED)).andRespond(withSuccess());

        assertThat(bot.getMemberRoles(USER)).isEqualTo(List.of());
    }

    @Test
    @DisplayName("liest die Rollen des Servers samt Namen")
    void liestServerrollen() {
        // Zwei Dinge haengen daran, die sich sonst nicht sagen lassen: dass eine
        // hinterlegte Rollen-Id auf dem Server gar nicht mehr existiert - sonst
        // bliebe genau dieser Fall unter "unbekannt" liegen - und wie die Rolle
        // heisst. Ohne den Namen steht in der Uebersicht nur eine
        // achtzehnstellige Zahl.
        DiscordBotService bot = dienst();
        server.expect(requestTo("https://discord.com/api/v10/guilds/" + GUILD + "/roles"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id": "1000", "name": "Mitglied", "position": 3, "permissions": "0"},
                         {"id": "9999", "name": "Marauders Associated", "position": 1}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(bot.getGuildRoles())
                .extracting(DiscordBotService.GuildRole::id, DiscordBotService.GuildRole::name)
                .containsExactly(tuple("1000", "Mitglied"), tuple("9999", "Marauders Associated"));
        server.verify();
    }

    @Test
    @DisplayName("vertraegt eine leere Rollenliste")
    void keineServerrollen() {
        // Die Auswertung muss zwischen "leer" und "nicht gelesen" unterscheiden
        // koennen; eine NullPointerException an dieser Stelle risse die Pruefung
        // aller Konten mit.
        DiscordBotService bot = dienst();
        server.expect(requestTo("https://discord.com/api/v10/guilds/" + GUILD + "/roles"))
                .andRespond(withSuccess());

        assertThat(bot.getGuildRoles()).isEmpty();
    }
}

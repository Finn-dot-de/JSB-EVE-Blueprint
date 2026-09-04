package com.eve.own.auth.backend.domain.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Der Weg einer Kanalnachricht - posten, aendern, und was bei Fehlern passiert.
 *
 * <p>Derselbe HTTP-Weg wie {@code sendDirectMessage}, aber mit einem
 * entscheidenden Unterschied im Verhalten: Dort wird ein Fehlschlag geschluckt,
 * weil ein Nutzer Direktnachrichten abgeschaltet haben darf und ein Zeitplan
 * weiterlaufen soll. Hier haengt am Ergebnis, ob ein Ping als abgesetzt gilt -
 * ein geschluckter Fehler waere hier ein Ping in der Datenbank, den niemand
 * gelesen hat.</p>
 */
@DisplayName("Flotten-Kanal in Discord")
class DiscordBotServicePingKanalTest {

    private static final String KANAL = "555000111";
    private static final String URL = "https://discord.com/api/v10/channels/" + KANAL + "/messages";

    private MockRestServiceServer server;

    private DiscordBotService bot(String kanalId) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new DiscordBotService(builder, "token", "42", "cid", "secret", kanalId);
    }

    @Test
    @DisplayName("posten liefert die Discord-Nachrichten-ID zurueck")
    void postenLiefertId() {
        DiscordBotService bot = bot(KANAL);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"1234567890\",\"channel_id\":\"" + KANAL + "\"}",
                        MediaType.APPLICATION_JSON));

        String id = bot.posteInKanal("Test", DiscordErwaehnungen.keine());

        server.verify();
        // OHNE DIESEN RUECKGABEWERT liesse sich der Ping nie wieder korrigieren
        // oder absagen - eine Flotte, die es nicht mehr gibt, stuende dann bis
        // in alle Ewigkeit im Kanal.
        assertThat(id).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("aendern greift genau die gepostete Nachricht per PATCH an")
    void aendernTrifftDieseEineNachricht() {
        DiscordBotService bot = bot(KANAL);
        server.expect(requestTo(URL + "/1234567890"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        bot.aendereImKanal("1234567890", "Korrektur", DiscordErwaehnungen.keine());

        // Die Korrektur steht damit an genau der Stelle, an der jemand den Ping
        // gelesen hat. Ein zweiter POST waere eine zweite Wahrheit im Kanal,
        // und wer nur die erste sieht, fliegt zu einer toten Flotte.
        server.verify();
    }

    @Test
    @DisplayName("ein Fehler von Discord wird weitergeworfen und nicht geschluckt")
    void fehlerWirdWeitergeworfen() {
        DiscordBotService bot = bot(KANAL);
        // 403: dem Bot fehlt "Send Messages" im Kanal - der haeufigste Fall bei
        // der Einrichtung.
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        // OHNE DAS WEITERWERFEN liefe der Aufrufer weiter und legte einen
        // Ping-Datensatz an, von dem der Kanal nie erfahren hat. Anders als bei
        // einer Direktnachricht ist ein Fehlschlag hier nicht hinnehmbar.
        assertThatThrownBy(() -> bot.posteInKanal("Test", DiscordErwaehnungen.keine()))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @DisplayName("eine Antwort ohne Nachrichten-ID gilt als Fehlschlag")
    void antwortOhneIdIstFehlschlag() {
        DiscordBotService bot = bot(KANAL);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // Ein halber Erfolg waere hier der teuerste Ausgang: Die Nachricht
        // stuende im Kanal und waere fuer dieses Werkzeug fuer immer
        // unerreichbar. Lieber ein Fehler, den jemand sieht.
        assertThatThrownBy(() -> bot.posteInKanal("Test", DiscordErwaehnungen.keine()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ohne konfigurierten Kanal geht keine einzige Anfrage hinaus")
    void ohneKanalKeineAnfrage() {
        DiscordBotService bot = bot("   ");

        assertThat(bot.istPingKanalKonfiguriert()).isFalse();
        assertThatThrownBy(() -> bot.posteInKanal("Test", DiscordErwaehnungen.keine()))
                .isInstanceOf(IllegalStateException.class);

        // Kein erwarteter Aufruf, und trotzdem keiner erfolgt: Ohne diese
        // Pruefung ginge die Anfrage an /channels//messages und Discord
        // antwortete mit einer Meldung, die niemandem sagt, was fehlt.
        server.verify();
    }

    @Test
    @DisplayName("Retry-After wird auch beim Posten beachtet")
    void bremseWirdBeachtet() {
        DiscordBotService bot = bot(KANAL);
        // Das Projekt ist bei Discord schon einmal ins Rate-Limit gelaufen. Der
        // Ping laeuft ueber dieselbe mitGeduld-Behandlung wie der Rollen-Sync;
        // OHNE SIE waere ein 429 beim Formup-Ping ein verlorener Ping.
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0.05"));
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"777\"}", MediaType.APPLICATION_JSON));

        assertThat(bot.posteInKanal("Test", DiscordErwaehnungen.keine())).isEqualTo("777");
        server.verify();
    }
}

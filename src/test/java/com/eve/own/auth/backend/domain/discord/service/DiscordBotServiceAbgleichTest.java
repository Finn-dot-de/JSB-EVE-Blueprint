package com.eve.own.auth.backend.domain.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Der Abgleich liest zuerst und schreibt nur die Differenz.
 *
 * <p>Anlass ist ein Produktionslog, in dem "Rate Limit erreicht" bei fast jedem
 * Nutzer stand. Die Ursache war nicht die Menge der Konten, sondern dass jeder
 * Lauf je Konto und Zuordnung <b>blind</b> ein PUT oder ein DELETE absetzte -
 * auch wenn seit Monaten alles richtig stand. Dreissig Konten mal zehn
 * Zuordnungen sind dreihundert Schreibzugriffe alle dreissig Minuten fuer
 * nichts.</p>
 *
 * <p>Die Zusicherungen hier sind deshalb Aufrufzahlen und nicht Ergebnisse.
 * Ohne sie faellt ein Rueckfall ins Blindschreiben nicht auf: Die Rollen saessen
 * hinterher richtig, alle uebrigen Tests blieben gruen, und der Fehler zeigte
 * sich erst wieder im Log der Produktion.</p>
 */
class DiscordBotServiceAbgleichTest {

    private static final String GUILD = "42";
    private static final String USER = "777";
    private static final String MITGLIED =
            "https://discord.com/api/v10/guilds/" + GUILD + "/members/" + USER;

    /** Rollen mit Zuordnung - nur diese darf der Abgleich anfassen. */
    private static final String VERWALTET_A = "1000";
    private static final String VERWALTET_B = "2000";
    /** Von Hand vergeben, ohne Zuordnung. Farbrolle, Pingrolle, so etwas. */
    private static final String HANDVERGEBEN = "9999";

    private MockRestServiceServer server;

    /**
     * Der Dienst mit zwei aufgetrennten Naehten: Warten und Uhrzeit.
     *
     * <p>Ohne sie liessen sich zwei Zusicherungen nur pruefen, indem der Test
     * sie tatsaechlich absitzt - die Wartezeit aus dem Rate Limit und die
     * Ruhezeit einer abgelehnten Rolle. Eine Testreihe, die eine Stunde
     * schlaeft, wird nicht ausgefuehrt.</p>
     */
    private static class PruefbarerBot extends DiscordBotService {
        private final List<Duration> gewartet = new ArrayList<>();
        private Instant uhr = Instant.now();

        PruefbarerBot(RestClient.Builder builder) {
            super(builder, "token", GUILD, "cid", "secret", "");
        }

        @Override
        protected void pausiere(Duration dauer) {
            gewartet.add(dauer);
        }

        @Override
        protected Instant jetzt() {
            return uhr;
        }
    }

    private PruefbarerBot dienst() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new PruefbarerBot(builder);
    }

    /** Was Discord auf den Lesezugriff antwortet. */
    private void mitgliedHat(String... rollen) {
        String liste = rollen.length == 0 ? "" : "\"" + String.join("\", \"", rollen) + "\"";
        server.expect(requestTo(MITGLIED))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"roles\": [" + liste + "]}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("steht alles richtig, bleibt es bei einem Lesezugriff")
    void alleRollenRichtigKeinSchreibzugriff() {
        // DER wichtigste Test dieser Klasse. Vorher setzte der Abgleich hier
        // zwei Schreibzugriffe ab - ein PUT auf die vorhandene Rolle und ein
        // DELETE auf die nicht vorhandene -, beide ohne Wirkung, beide bei
        // jedem Lauf, bei jedem Konto. Genau daraus entstand das Rate Limit.
        //
        // Faellt dieser Test weg, faellt der Rueckfall ins Blindschreiben nicht
        // auf: Die Rollen stuenden hinterher richtig, und kein anderer Test
        // wuerde etwas merken.
        DiscordBotService bot = dienst();
        mitgliedHat(VERWALTET_A, HANDVERGEBEN);

        List<DiscordRollenErgebnis> ergebnis = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A, VERWALTET_B), List.of(VERWALTET_A), null);

        // Ein GET und sonst nichts: Jeder weitere Aufruf waere hier unerwartet
        // und liesse den Test scheitern.
        server.verify();

        // Trotzdem eine Zeile je Rolle - der Knopf soll "steht richtig" zeigen
        // koennen und nicht eine leere Liste, die auch "nichts geprueft"
        // heissen kann.
        assertThat(ergebnis).extracting(DiscordRollenErgebnis::discordRoleId,
                        DiscordRollenErgebnis::erfolg, DiscordRollenErgebnis::geaendert)
                .containsExactly(
                        tuple(VERWALTET_A, true, false),
                        tuple(VERWALTET_B, true, false));
    }

    @Test
    @DisplayName("fehlt eine Rolle, geht genau ein PUT hinaus")
    void fehlendeRolleGenauEinPut() {
        // Die Differenz und nur die Differenz: Die zweite verwaltete Rolle
        // steht bereits richtig (naemlich gar nicht) und darf kein DELETE
        // ausloesen. Der alte Weg schickte hier zwei Aufrufe.
        DiscordBotService bot = dienst();
        mitgliedHat(HANDVERGEBEN);
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_A))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        List<DiscordRollenErgebnis> ergebnis = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A, VERWALTET_B), List.of(VERWALTET_A), null);

        server.verify();
        assertThat(ergebnis).extracting(DiscordRollenErgebnis::discordRoleId,
                        DiscordRollenErgebnis::aktion, DiscordRollenErgebnis::geaendert)
                .containsExactly(
                        tuple(VERWALTET_A, DiscordRollenErgebnis.Aktion.VERGEBEN, true),
                        tuple(VERWALTET_B, DiscordRollenErgebnis.Aktion.ENTZOGEN, false));
    }

    @Test
    @DisplayName("ist eine verwaltete Rolle zuviel, geht genau ein DELETE hinaus")
    void ueberzaehligeRolleGenauEinDelete() {
        // Gegenrichtung zum Test darueber. Die gewuenschte Rolle sitzt schon
        // und darf kein PUT ausloesen.
        DiscordBotService bot = dienst();
        mitgliedHat(VERWALTET_A, VERWALTET_B);
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_B))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        List<DiscordRollenErgebnis> ergebnis = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A, VERWALTET_B), List.of(VERWALTET_A), null);

        server.verify();
        assertThat(ergebnis).extracting(DiscordRollenErgebnis::discordRoleId,
                        DiscordRollenErgebnis::aktion, DiscordRollenErgebnis::geaendert)
                .containsExactly(
                        tuple(VERWALTET_A, DiscordRollenErgebnis.Aktion.VERGEBEN, false),
                        tuple(VERWALTET_B, DiscordRollenErgebnis.Aktion.ENTZOGEN, true));
    }

    @Test
    @DisplayName("eine handvergebene Rolle ohne Zuordnung bleibt unberuehrt")
    void handvergebeneRolleBleibtLiegen() {
        // Die Zusicherung, wegen der es diese Methode ueberhaupt gibt. Der
        // Ist-Zustand nennt jetzt jede Rolle des Mitglieds - auch die, die das
        // Auth nichts angehen. Wer die Differenz gegen den ganzen Ist-Zustand
        // rechnet statt gegen die verwalteten Rollen, raeumt sie ab, und der
        // Fehler von damals waere zurueck.
        DiscordBotService bot = dienst();
        mitgliedHat(HANDVERGEBEN, VERWALTET_A);

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(VERWALTET_A), null);

        // Kein DELETE auf die 9999: Es wurde nur gelesen.
        server.verify();
    }

    @Test
    @DisplayName("wartet bei 429 die Zeit aus dem Header, nicht pauschal fuenf Sekunden")
    void wartetDieGenannteZeit() {
        // Der Header Retry-After wurde bisher gar nicht gelesen; gewartet
        // wurden pauschal fuenf Sekunden, und der Nutzer fiel aus dem Lauf.
        // Beides falsch: Bei 0,75 Sekunden verschenkte der Durchlauf ueber vier
        // Sekunden je Treffer - bei dreissig Konten der Grund, warum ein Lauf
        // laenger dauerte als der Abstand zum naechsten.
        PruefbarerBot bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "0.75"));
        server.expect(requestTo(MITGLIED))
                .andRespond(withSuccess("{\"roles\": [\"" + VERWALTET_A + "\"]}",
                        MediaType.APPLICATION_JSON));

        List<DiscordRollenErgebnis> ergebnis = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A), List.of(VERWALTET_A), null);

        assertThat(bot.gewartet).containsExactly(Duration.ofMillis(750));
        // Und danach wird der Aufruf wiederholt, statt das Konto zu ueberspringen.
        server.verify();
        assertThat(ergebnis).singleElement()
                .extracting(DiscordRollenErgebnis::erfolg).isEqualTo(true);
    }

    @Test
    @DisplayName("nennt Discord keine Wartezeit, wird kurz gewartet statt lang geraten")
    void ohneHeaderKurzeWartezeit() {
        // Discord schickt den Header immer; verschluckt ihn eine
        // Zwischeninstanz, ist eine Sekunde die bessere Schaetzung als fuenf -
        // zu kurz gewartet kostet einen weiteren 429, zu lang gewartet den
        // ganzen Durchlauf.
        PruefbarerBot bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(MITGLIED))
                .andRespond(withSuccess("{\"roles\": []}", MediaType.APPLICATION_JSON));

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(), null);

        assertThat(bot.gewartet).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("sitzt eine sehr lange Bremse nicht ab, sondern reicht sie weiter")
    void sehrLangeBremseWirdWeitergereicht() {
        // Eine Minute und mehr nennt Discord nur bei einer globalen Bremse fuer
        // den ganzen Bot. Sie im Aufruf abzuwarten hiesse, den Zeitplan-Faden
        // schlafen zu legen; der Aufrufer soll den Durchlauf beenden.
        PruefbarerBot bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "300"));

        assertThatThrownBy(() -> bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(), null))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        assertThat(bot.gewartet).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("versucht eine mit 403 abgelehnte Rolle im Zeitplan nicht bei jedem Lauf erneut")
    void abgelehnteRolleRuhtImZeitplan() {
        // Eine Rolle ueber der Bot-Rolle kann ohne eine Aenderung IN DISCORD
        // nie gelingen. Sie alle dreissig Minuten erneut zu versuchen, kostet
        // dauerhaft Aufrufe fuer ein sicheres Scheitern - im Log waren es
        // dieselben zwei Rollen-Ids bei denselben Nutzern, Lauf fuer Lauf.
        PruefbarerBot bot = dienst();
        // Alle erwarteten Aufrufe beider Laeufe im Voraus, der Reihe nach:
        // ein Lesezugriff und ein abgelehntes PUT - und danach nur noch ein
        // zweiter Lesezugriff. Ein PUT im zweiten Lauf waere hier ein Aufruf
        // zuviel und liesse den Test scheitern.
        mitgliedHat();
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_A))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        mitgliedHat();

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(VERWALTET_A), null,
                DiscordBotService.Anlass.ZEITPLAN);

        List<DiscordRollenErgebnis> zweiterLauf = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A), List.of(VERWALTET_A), null,
                DiscordBotService.Anlass.ZEITPLAN);

        server.verify();
        // Uebersprungen heisst nicht verschwiegen: Die Rolle steht weiter als
        // gescheitert da. Wuerde sie als erfolgreich gemeldet, waere die
        // Daempfung eine Luege - jemand wartete auf eine Rolle, die nie kommt.
        assertThat(zweiterLauf).singleElement()
                .extracting(DiscordRollenErgebnis::erfolg).isEqualTo(false);
        assertThat(zweiterLauf.getFirst().grund()).contains("403");
    }

    @Test
    @DisplayName("der Anstoss von Hand kennt keine Ruhezeit")
    void anstossVersuchtSofortErneut() {
        // Wer den Knopf drueckt, hat gerade etwas geaendert - meist die
        // Bot-Rolle hoeher gezogen. Ihn auf eine Ruhezeit zu vertroesten,
        // verweigerte ihm die Antwort auf genau die Frage, wegen der er
        // drueckt: "wirkt es jetzt?"
        PruefbarerBot bot = dienst();
        mitgliedHat();
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_A))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        // Und der zweite Lauf, unmittelbar danach und ohne jede Wartezeit:
        // Der Versuch geht wieder hinaus.
        mitgliedHat();
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_A))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(VERWALTET_A), null,
                DiscordBotService.Anlass.ZEITPLAN);

        List<DiscordRollenErgebnis> ergebnis = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A), List.of(VERWALTET_A), null);

        server.verify();
        assertThat(ergebnis).singleElement()
                .extracting(DiscordRollenErgebnis::erfolg).isEqualTo(true);
    }

    @Test
    @DisplayName("nimmt eine behobene Rangfolge auch ohne Knopfdruck wieder auf")
    void ruhezeitLaeuftAus() {
        // Die Gegenprobe zur Daempfung und ihre Bedingung: Sie darf nicht dazu
        // fuehren, dass eine spaeter in Discord behobene Rangfolge unbemerkt
        // bleibt. Deshalb ist die Ruhezeit gedeckelt - spaetestens nach einem
        // Tag versucht es der Zeitplan von selbst erneut, auch wenn niemand
        // hinsieht.
        PruefbarerBot bot = dienst();
        mitgliedHat();
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_A))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        // Der Lauf einen Tag spaeter, als die Bot-Rolle hoeher gezogen ist.
        mitgliedHat();
        server.expect(requestTo(MITGLIED + "/roles/" + VERWALTET_A))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(VERWALTET_A), null,
                DiscordBotService.Anlass.ZEITPLAN);

        bot.uhr = bot.uhr.plus(Duration.ofHours(25));
        List<DiscordRollenErgebnis> ergebnis = bot.syncManagedRoles(USER,
                List.of(VERWALTET_A), List.of(VERWALTET_A), null,
                DiscordBotService.Anlass.ZEITPLAN);

        server.verify();
        assertThat(ergebnis).singleElement()
                .extracting(DiscordRollenErgebnis::erfolg).isEqualTo(true);
    }

    @Test
    @DisplayName("schreibt den Spitznamen nur, wenn er abweicht")
    void spitznameNurBeiAbweichung() {
        // Der letzte Schreibzugriff, der bei jedem Lauf ungefragt hinausging:
        // ein PATCH je Konto und halbe Stunde fuer einen Namen, der sich im
        // Jahr einmal aendert. Ohne diesen Vergleich waere der Test oben -
        // "kein Schreibzugriff" - fuer jedes Konto mit Spitzname falsch.
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"roles\": [\"" + VERWALTET_A + "\"], \"nick\": \"Tom\"}",
                        MediaType.APPLICATION_JSON));

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(VERWALTET_A), "Tom");

        server.verify();
    }

    @Test
    @DisplayName("setzt einen abweichenden Spitznamen mit einem PATCH")
    void abweichenderSpitznameWirdGesetzt() {
        // Die Gegenprobe: Ohne sie bestuende der Test darueber auch dann, wenn
        // der Spitzname gar nicht mehr gesetzt wuerde.
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"roles\": [\"" + VERWALTET_A + "\"], \"nick\": \"Alter Name\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(MITGLIED))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        bot.syncManagedRoles(USER, List.of(VERWALTET_A), List.of(VERWALTET_A), "Tom");

        server.verify();
    }

    @Test
    @DisplayName("schreibt einen zu langen Spitznamen nicht bei jedem Lauf erneut")
    void gekuerzterSpitznameGiltAlsGleich() {
        // Discord nimmt 32 Zeichen. Wuerde erst beim Senden gekuerzt und
        // vorher der volle Name verglichen, gaelte ein langer Name fuer immer
        // als abweichend - und jeder Lauf schriebe ihn erneut. Genau die Sorte
        // Dauerlast, wegen der dieser Umbau noetig war.
        String zuLang = "Ein Charaktername mit deutlich mehr als 32 Zeichen";
        DiscordBotService bot = dienst();
        server.expect(requestTo(MITGLIED))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"roles\": [], \"nick\": \"" + zuLang.substring(0, 32) + "\"}",
                        MediaType.APPLICATION_JSON));

        bot.syncManagedRoles(USER, List.of(), List.of(), zuLang);

        server.verify();
    }
}

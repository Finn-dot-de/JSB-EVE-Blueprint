package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Der Text, den am Ende tausend Leute lesen.
 *
 * <p>Hier faellt die Entscheidung ueber die Zeitzone. EVE-Zeit ist UTC, und
 * {@link Instant} hat gar keine Zone - es gibt also nichts umzurechnen. Wer
 * einen {@code LocalDateTime} genommen haette, haette an dieser Stelle die
 * Zeitzone des Servers eingebaut, ohne es zu merken: Der Ping saehe im Test
 * richtig aus und stuende in Produktion eine Stunde daneben.</p>
 */
@DisplayName("Text einer Ping-Nachricht")
class FleetPingNachrichtTest {

    /** 19:00 EVE-Zeit - die uebliche Formup-Zeit einer europaeischen Corp. */
    private static final Instant FORMUP = Instant.parse("2026-09-03T19:00:00Z");

    private FleetPing ping(Instant formup) {
        FleetPing ping = new FleetPing();
        ping.setFcCharacterName("Aiko Danuja");
        ping.setFleetType("Home Defense");
        ping.setDoctrine("Ferox Fleet");
        ping.setFormupLocation("Jita IV-4");
        ping.setFormupTime(formup);
        ping.setComms("Mumble, Kanal Fleet 1");
        ping.setSrpCovered(true);
        ping.setErwaehnung(PingErwaehnung.HIER);
        ping.setCreatedAt(FORMUP);
        ping.setUpdatedAt(FORMUP);
        return ping;
    }

    @Test
    @DisplayName("die Formup-Zeit steht als UTC da und zusaetzlich als Discord-Zeitmarke")
    void zeitStehtDoppeltUndInUtc() {
        String text = FleetPingNachricht.aufbauen(ping(FORMUP), "", false);

        // Die EVE-Schreibweise, unverrueckt: 19:00 UTC bleibt 19:00, egal in
        // welcher Zeitzone der Server steht, der diesen Test ausfuehrt. OHNE
        // das feste UTC im Formatierer stuende hier die Serverzeit - und die
        // faellt in einem deutschen Sommer eine Stunde daneben.
        assertThat(text).contains("2026-09-03 19:00 EVE");

        // Und dieselbe Zeit noch einmal als Marke, die Discord jedem Leser in
        // SEINER Zone anzeigt. Die Umrechnung im Kopf ist die haeufigste
        // Ursache dafuer, dass jemand eine Stunde zu spaet andockt.
        assertThat(text).contains("<t:" + FORMUP.getEpochSecond() + ":R>");
    }

    @Test
    @DisplayName("ohne Formup-Zeit steht JETZT da und keine erfundene Uhrzeit")
    void ohneZeitStehtJetzt() {
        // "form up now" ist die haeufigste Ansage ueberhaupt. Sie mit der
        // aktuellen Uhrzeit auszuschreiben saehe gleich aus, waere aber eine
        // andere Aussage - eine Minute spaeter stuende dort Vergangenheit.
        assertThat(FleetPingNachricht.aufbauen(ping(null), "", false)).contains("JETZT");
    }

    @Test
    @DisplayName("SRP kennt drei Antworten, nicht zwei")
    void srpHatDreiAntworten() {
        FleetPing ohneAngabe = ping(FORMUP);
        ohneAngabe.setSrpCovered(null);

        // "nicht gesagt" darf nicht als "nein" gelesen werden: Daran haengt, ob
        // jemand den teuren Rumpf mitbringt oder den, den er verschmerzen kann.
        assertThat(FleetPingNachricht.aufbauen(ohneAngabe, "", false)).contains("nicht angegeben");
        assertThat(FleetPingNachricht.aufbauen(ping(FORMUP), "", false)).contains("**SRP:** ja");
    }

    @Test
    @DisplayName("die Absage streicht die alten Angaben durch, statt sie zu loeschen")
    void absageStreichtDurch() {
        FleetPing ping = ping(FORMUP);
        ping.setCancelledAt(FORMUP);
        ping.setCancelReason("Ziel ist weg");

        String text = FleetPingNachricht.absage(ping, "Der Direktor");

        assertThat(text).startsWith("**ABGESAGT");
        assertThat(text).contains("Der Direktor").contains("Ziel ist weg");
        // Die alten Angaben bleiben lesbar: Wer die Nachricht wiederfindet, soll
        // erkennen, dass genau DIESE Flotte abgesagt ist - und nicht irgendeine.
        assertThat(text).contains("~~**Doktrin:** Ferox Fleet~~");
        // Aber ohne die Erwaehnung davor. Ein durchgestrichenes "@here" waere
        // nur eine Frage mehr fuer den Leser.
        assertThat(text).doesNotContain("@here");
    }

    @Test
    @DisplayName("ein geaenderter Ping sagt, dass er geaendert wurde")
    void aenderungIstSichtbar() {
        // OHNE DIESEN HINWEIS liest sich ein korrigierter Ping wie der
        // urspruengliche - und wer ihn schon gelesen hat, sieht keinen Grund,
        // ihn noch einmal zu lesen.
        assertThat(FleetPingNachricht.aufbauen(ping(FORMUP), "", true)).contains("Geaendert:");
        assertThat(FleetPingNachricht.aufbauen(ping(FORMUP), "", false)).doesNotContain("Geaendert:");
    }

    @Test
    @DisplayName("ein zu langer Text wird gekuerzt, statt von Discord ganz abgelehnt")
    void textWirdGekuerzt() {
        FleetPing ping = ping(FORMUP);
        ping.setNotes("y".repeat(5000));

        String text = FleetPingNachricht.aufbauen(ping, "", false);

        // Discord lehnt eine Nachricht ueber 2000 Zeichen komplett ab. Lieber
        // ein abgeschnittener Ping als gar keiner - dann steht der FC vor einer
        // Flotte statt vor einem Fehler.
        assertThat(text).hasSizeLessThanOrEqualTo(FleetPingNachricht.DISCORD_HOECHSTLAENGE);
        assertThat(text).endsWith("...");
    }

    @Test
    @DisplayName("die Erwaehnung vor dem Titel kommt aus der Auswahl, nicht aus dem Text")
    void prefixKommtAusDerAuswahl() {
        assertThat(FleetPingNachricht.erwaehnungsPrefix(PingErwaehnung.STILL, "777")).isEmpty();
        assertThat(FleetPingNachricht.erwaehnungsPrefix(PingErwaehnung.HIER, "777"))
                .isEqualTo("@here ");
        assertThat(FleetPingNachricht.erwaehnungsPrefix(PingErwaehnung.ROLLE, "777"))
                .isEqualTo("<@&777> ");
        // Ohne konfigurierte Rolle bleibt der Ping still - nie ersatzweise laut.
        assertThat(FleetPingNachricht.erwaehnungsPrefix(PingErwaehnung.ROLLE, null)).isEmpty();
    }
}

package com.eve.own.auth.backend.domain.discord.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Das Feld, ohne das Discord grosszuegig wird.
 *
 * <p>Die Regel dahinter ist unangenehm einfach: Fehlt {@code allowed_mentions},
 * dann loest Discord jede Erwaehnung auf, die im Fliesstext steht. Es gibt
 * keinen Zwischenzustand und keine stille Vorgabe "nichts" - entweder das Feld
 * ist da und sagt genau, was erlaubt ist, oder alles ist erlaubt.</p>
 */
@DisplayName("Erlaubte Erwaehnungen einer Discord-Nachricht")
class DiscordErwaehnungenTest {

    @Test
    @DisplayName("\"keine\" schickt leere Listen und nicht etwa gar nichts")
    void keineIstLeerUndNichtAbwesend() {
        Map<String, Object> feld = DiscordErwaehnungen.keine().alsKoerperFeld();

        // OHNE DIESE ZEILEN waere der Unterschied unsichtbar: {"parse": []}
        // heisst bei Discord "nichts", ein fehlendes Feld heisst "alles". Wer
        // hier null durchliesse, baute genau den Fehler, gegen den die ganze
        // Klasse existiert.
        assertThat(feld).containsKeys("parse", "roles");
        assertThat(feld.get("parse")).isEqualTo(List.of());
        assertThat(feld.get("roles")).isEqualTo(List.of());
        assertThat(DiscordErwaehnungen.keine().istStill()).isTrue();
    }

    @Test
    @DisplayName("bei einer Rolle bleibt parse leer, sonst waere die Aufzaehlung wirkungslos")
    void rolleOhneParse() {
        DiscordErwaehnungen erwaehnungen = DiscordErwaehnungen.rolle("777");

        // Stuende in parse zusaetzlich "roles", duerfte Discord JEDE im Text
        // genannte Rolle aufloesen - die Aufzaehlung waere dann Zierde, und ein
        // aus Discord kopiertes <@&...> im Notizfeld erreichte jede Rolle des
        // Servers.
        assertThat(erwaehnungen.parse()).isEmpty();
        assertThat(erwaehnungen.roles()).containsExactly("777");
        assertThat(erwaehnungen.istStill()).isFalse();
    }

    @Test
    @DisplayName("eine fehlende Rollen-ID macht den Ping still und nicht laut")
    void fehlendeRolleWirdStill() {
        // Der Fall entsteht durch eine nicht gesetzte Umgebungsvariable. Die
        // einzig vertretbare Richtung ist leiser: Ein vergessener Eintrag darf
        // nicht dazu fuehren, dass ersatzweise alle geweckt werden.
        assertThat(DiscordErwaehnungen.rolle(null).istStill()).isTrue();
        assertThat(DiscordErwaehnungen.rolle("  ").istStill()).isTrue();
    }

    @Test
    @DisplayName("die Listen lassen sich nach dem Bauen nicht mehr veraendern")
    void listenSindUnveraenderlich() {
        List<String> beweglich = new java.util.ArrayList<>(List.of("777"));
        DiscordErwaehnungen erwaehnungen = new DiscordErwaehnungen(List.of(), beweglich);

        beweglich.add("boese");

        // OHNE DIE KOPIE im Konstruktor waere eine Erwaehnung noch aenderbar,
        // nachdem sie geprueft wurde - und geprueft wird sie vor dem Absenden.
        assertThat(erwaehnungen.roles()).containsExactly("777");
    }

    @Test
    @DisplayName("entschaerfter Text enthaelt kein @everyone, @here und kein <@ mehr")
    void entschaerfenZerlegtJedeErwaehnung() {
        String entschaerft = DiscordErwaehnungen.entschaerfe(
                "Hallo @everyone und @here, cc <@&123> und <@456>");

        assertThat(entschaerft).doesNotContain("@everyone", "@here", "<@");
        // Lesbar bleibt alles: Wer "@everyone" schreibt, meint meistens etwas
        // mit dem Wort und soll es im Kanal auch lesen koennen.
        assertThat(entschaerft).contains("everyone", "here", "123", "456");
    }

    @Test
    @DisplayName("entschaerfen laesst null und harmlosen Text unveraendert")
    void entschaerfenIstHarmlosFuerHarmloses() {
        assertThat(DiscordErwaehnungen.entschaerfe(null)).isNull();
        // Die Zeitmarke von Discord darf nicht mit zerlegt werden - sonst
        // stuende im Ping statt einer Uhrzeit ein Stueck Quelltext.
        assertThat(DiscordErwaehnungen.entschaerfe("Formup <t:1756918800:F> in Jita"))
                .isEqualTo("Formup <t:1756918800:F> in Jita");
    }
}

package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.domain.discord.service.DiscordErwaehnungen;
import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Baut den Text einer Ping-Nachricht.
 *
 * <p>Eigene Klasse und kein privater Block im Dienst: Der Text ist das, was
 * tausend Leute lesen, und er laesst sich hier ohne Discord, ohne Datenbank und
 * ohne Sicherheitskontext pruefen. Wer ihn im Dienst versteckt, kann ihn nur
 * noch zusammen mit einem gemockten HTTP-Aufruf testen.</p>
 *
 * <h2>Die Zeitangabe steht doppelt da</h2>
 * <p>Einmal als {@code 2026-09-03 19:00 EVE} - so reden EVE-Spieler, und so
 * steht es in jedem Kalender einer Allianz. Und einmal als Discord-Marke
 * {@code <t:...:R>}, die Discord jedem Leser in <em>seiner</em> Zeitzone und als
 * "in 45 Minuten" anzeigt. Die Umrechnung im Kopf ist die haeufigste Ursache
 * dafuer, dass jemand eine Stunde zu spaet andockt - und sie ist vermeidbar,
 * weil Discord sie selbst erledigt.</p>
 */
final class FleetPingNachricht {

    /** EVE-Zeit ist UTC. Der Zeitstempel wird nicht umgerechnet, nur beschriftet. */
    private static final DateTimeFormatter EVE_ZEIT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /**
     * Discords Obergrenze fuer den Inhalt einer Nachricht.
     *
     * <p>Wird sie ueberschritten, lehnt Discord die ganze Nachricht mit 400 ab -
     * der Ping ginge also gar nicht raus. Die Felder sind deshalb schon in
     * {@code FleetPingService} einzeln begrenzt; diese Zahl steht hier als
     * letzte Bremse fuer den Fall, dass jemand eine Grenze lockert.</p>
     */
    static final int DISCORD_HOECHSTLAENGE = 2000;

    private FleetPingNachricht() {
        throw new AssertionError("Nur statische Bauteile.");
    }

    /**
     * Der Text eines frischen oder geaenderten Pings.
     *
     * <p>Jedes vom FC eingetippte Feld laeuft durch
     * {@link DiscordErwaehnungen#entschaerfe}. Nicht nur das Notizfeld: ein
     * Treffpunkt "Jita @everyone" ist genauso ein Text wie eine Notiz, und die
     * Sperre darf nicht davon abhaengen, in welches Feld jemand tippt.</p>
     *
     * @param erwaehnungsPrefix was vor dem Titel steht - kommt aus
     *     {@link #erwaehnungsPrefix} und damit aus der Auswahl des FC, nie aus
     *     seinem Text
     * @param geaendert setzt den Hinweis, dass hier etwas nachtraeglich anders
     *     wurde. Ohne ihn liest sich ein korrigierter Ping wie der urspruengliche,
     *     und wer ihn schon gelesen hat, sieht den Unterschied nicht.
     */
    static String aufbauen(FleetPing ping, String erwaehnungsPrefix, boolean geaendert) {
        List<String> zeilen = new ArrayList<>();
        zeilen.add(erwaehnungsPrefix + "**FLOTTEN-PING - " + sicher(ping.getFleetType()) + "**");
        zeilen.add("**Doktrin:** " + wert(ping.getDoctrine()));
        zeilen.add("**Treffpunkt:** " + wert(ping.getFormupLocation()));
        zeilen.add("**Formup:** " + formupZeit(ping.getFormupTime()));
        zeilen.add("**Comms:** " + wert(ping.getComms()));
        zeilen.add("**SRP:** " + srp(ping.getSrpCovered()));
        if (ping.getNotes() != null && !ping.getNotes().isBlank()) {
            zeilen.add("**Hinweis:** " + sicher(ping.getNotes()));
        }
        zeilen.add("*FC: " + sicher(ping.getFcCharacterName()) + "*");
        if (geaendert) {
            zeilen.add("*Geaendert: " + marke(ping.getUpdatedAt(), "f") + "*");
        }
        return kuerzen(String.join("\n", zeilen));
    }

    /**
     * Der Text einer Absage.
     *
     * <p>Die urspruenglichen Angaben bleiben stehen, durchgestrichen. Sie
     * herauszuloeschen waere bequemer und falsch: Wer die Nachricht wiederfindet,
     * weil er sie vorhin gelesen hat, soll erkennen, dass genau <em>diese</em>
     * Flotte abgesagt ist - und nicht irgendeine.</p>
     */
    static String absage(FleetPing ping, String absagenderName) {
        List<String> zeilen = new ArrayList<>();
        zeilen.add("**ABGESAGT - diese Flotte findet nicht statt.**");
        zeilen.add("Abgesagt von " + sicher(absagenderName)
                + " um " + marke(ping.getCancelledAt(), "f") + ".");
        if (ping.getCancelReason() != null && !ping.getCancelReason().isBlank()) {
            zeilen.add("**Grund:** " + sicher(ping.getCancelReason()));
        }
        zeilen.add("");
        // Der durchgestrichene Rest bekommt KEINE Erwaehnung vorangestellt. Er
        // soll erklaeren, was abgesagt ist - ein durchgestrichenes "@here" waere
        // nur eine Frage mehr fuer den Leser.
        for (String zeile : aufbauen(ping, "", false).split("\n")) {
            zeilen.add(durchgestrichen(zeile));
        }
        return kuerzen(String.join("\n", zeilen));
    }

    /** Die Erwaehnung, die vor dem Text steht - und nur sie darf laut sein. */
    static String erwaehnungsPrefix(PingErwaehnung erwaehnung, String rollenId) {
        return switch (erwaehnung) {
            case STILL -> "";
            case HIER -> "@here ";
            case JEDER -> "@everyone ";
            // Die maschinenlesbare Form. Sie stammt aus der Konfiguration und
            // nie aus einer Anfrage - deshalb ist sie hier erlaubt, waehrend
            // dasselbe Muster im Freitext entschaerft wird.
            case ROLLE -> rollenId == null || rollenId.isBlank() ? "" : "<@&" + rollenId + "> ";
        };
    }

    /**
     * Wandelt einen Zeitpunkt in Discords Zeitmarke.
     *
     * <p>{@code <t:Sekunden:Stil>} ist keine Erwaehnung und faellt deshalb nicht
     * unter die Entschaerfung - Discord setzt hier eine Uhrzeit ein, keine
     * Benachrichtigung.</p>
     */
    private static String marke(Instant zeitpunkt, String stil) {
        return zeitpunkt == null ? "-" : "<t:" + zeitpunkt.getEpochSecond() + ":" + stil + ">";
    }

    private static String formupZeit(Instant formup) {
        if (formup == null) {
            // "form up now" ist die haeufigste Ansage ueberhaupt. Sie mit der
            // aktuellen Uhrzeit auszuschreiben saehe gleich aus, waere aber eine
            // andere Aussage - eine Minute spaeter stuende dort Vergangenheit.
            return "**JETZT**";
        }
        return EVE_ZEIT.format(formup) + " EVE (" + marke(formup, "R") + ")";
    }

    private static String srp(Boolean gedeckt) {
        // Drei Antworten und nicht zwei: "nicht gesagt" darf nicht als "nein"
        // gelesen werden - daran haengt, ob jemand den teuren Rumpf mitbringt.
        if (gedeckt == null) {
            return "nicht angegeben";
        }
        return gedeckt ? "ja" : "nein";
    }

    private static String wert(String eingabe) {
        return eingabe == null || eingabe.isBlank() ? "-" : sicher(eingabe);
    }

    /** Kurz fuer: von diesem Text darf keine Erwaehnung ausgehen. */
    private static String sicher(String eingabe) {
        return DiscordErwaehnungen.entschaerfe(eingabe);
    }

    private static String durchgestrichen(String zeile) {
        // Leerzeilen bleiben leer: "~~~~" waere in Discord kein Strich, sondern
        // sichtbarer Muell.
        return zeile.isBlank() ? zeile : "~~" + zeile + "~~";
    }

    private static String kuerzen(String text) {
        if (text.length() <= DISCORD_HOECHSTLAENGE) {
            return text;
        }
        // Lieber ein abgeschnittener Ping als gar keiner: Discord wuerde die zu
        // lange Nachricht komplett ablehnen, und dann steht der FC vor einem
        // Fehler statt vor einer Flotte.
        return text.substring(0, DISCORD_HOECHSTLAENGE - 3) + "...";
    }
}

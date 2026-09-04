package com.eve.own.auth.backend.domain.discord.service;

import java.util.List;
import java.util.Map;

/**
 * Das Feld {@code allowed_mentions} einer Discord-Nachricht - die Sperre
 * zwischen dem Text einer Nachricht und der Klingel jedes Corp-Mitglieds.
 *
 * <h2>Warum es diese Klasse ueberhaupt gibt</h2>
 * <p>Discord entscheidet ueber Erwaehnungen <b>am Fliesstext</b>. Steht irgendwo
 * in der Nachricht {@code @everyone}, dann klingelt es bei jedem Mitglied des
 * Servers - unabhaengig davon, was die Anwendung gemeint hat. Ein FC, der in das
 * Notizfeld eines Pings "kein @everyone Spam bitte" schreibt, loest damit genau
 * das aus, wovon er abraet.</p>
 *
 * <p>{@code allowed_mentions} kehrt das um: Was hier nicht ausdruecklich erlaubt
 * ist, wird von Discord als blosser Text dargestellt und benachrichtigt
 * niemanden. Genau deshalb darf das Feld <b>nie</b> fehlen. Laesst man es weg,
 * gilt Discords grosszuegige Vorgabe - und die lautet "alles, was im Text
 * steht".</p>
 *
 * <p>Als eigener Typ und nicht als {@code Map} an der Aufrufstelle: Ein
 * Parameter, den man vergessen kann, wird irgendwann vergessen. Die
 * Sendemethoden verlangen ihn, es gibt keine Ueberladung ohne ihn, und jede
 * Auspraegung entsteht ueber eine der drei Fabriken unten. Damit ist die Menge
 * der ueberhaupt moeglichen Erwaehnungen abzaehlbar und steht in einer Datei.</p>
 *
 * @param parse welche Gattungen von Erwaehnungen Discord aus dem Text ziehen
 *     darf. Die leere Liste heisst "keine" - und nicht "Vorgabe".
 * @param roles die einzelnen erlaubten Rollen-IDs. Nur wirksam, solange
 *     {@code parse} nicht {@code "roles"} enthaelt: die Gattung schlaegt die
 *     Aufzaehlung, und Discord weist die Kombination sogar als Fehler ab.
 */
public record DiscordErwaehnungen(List<String> parse, List<String> roles) {

    /**
     * Discords Name fuer die Gattung {@code @everyone} <em>und</em> {@code @here}.
     *
     * <p>Die beiden lassen sich in {@code allowed_mentions} nicht trennen -
     * Discord kennt dafuer nur diesen einen Schalter. Das ist der Grund, warum
     * der freie Text zusaetzlich entschaerft wird, bevor er in die Nachricht
     * geht: Sonst waere die Auswahl "@here" in Wahrheit die Erlaubnis, ueber das
     * Notizfeld ein {@code @everyone} nachzuschieben.</p>
     */
    private static final String GATTUNG_ALLE = "everyone";

    /**
     * Zero-Width-Space - das Zeichen, das den Text nicht veraendert und die
     * Erkennung doch zerlegt.
     *
     * <p>Ueber den Codepunkt gebildet und nicht als Zeichen in die Quelldatei
     * getippt: Ein unsichtbares Zeichen im Quelltext ueberlebt weder einen
     * Kopiervorgang noch ein falsch geratenes Encoding - und sein Verschwinden
     * faellt niemandem auf, weil man es nicht sieht. Die Entschaerfung waere
     * dann still weg, und der Test dazu gruen, weil er dasselbe kaputte Zeichen
     * erwartet.</p>
     */
    private static final String UNSICHTBAR = String.valueOf((char) 0x200B);

    public DiscordErwaehnungen {
        // Unveraenderlich ab hier: Diese Listen wandern in den Rumpf einer
        // ausgehenden HTTP-Anfrage. Eine von aussen noch aenderbare Liste waere
        // eine Erwaehnung, die sich nach der Pruefung noch umschreiben laesst.
        parse = parse == null ? List.of() : List.copyOf(parse);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /**
     * Kein Ton: Die Nachricht steht im Kanal, niemand wird benachrichtigt.
     *
     * <p>Ausdruecklich die leere Liste und nicht {@code null}. Der Unterschied
     * ist der ganze Zweck dieser Klasse: {@code {"parse": []}} heisst "nichts",
     * ein fehlendes Feld heisst bei Discord "alles".</p>
     */
    public static DiscordErwaehnungen keine() {
        return new DiscordErwaehnungen(List.of(), List.of());
    }

    /** {@code @here} - alle, die gerade online sind. */
    public static DiscordErwaehnungen alle() {
        return new DiscordErwaehnungen(List.of(GATTUNG_ALLE), List.of());
    }

    /**
     * Genau eine Rolle und sonst nichts.
     *
     * <p>{@code parse} bleibt leer: Waere dort {@code "roles"} eingetragen,
     * duerfte Discord <em>jede</em> im Text genannte Rolle aufloesen und die
     * Aufzaehlung waere wirkungslos. Die Rolle wird also nicht erlaubt, weil sie
     * im Text steht, sondern weil sie hier steht.</p>
     */
    public static DiscordErwaehnungen rolle(String rollenId) {
        if (rollenId == null || rollenId.isBlank()) {
            // Eine leere Rollen-ID stillschweigend als "keine Erwaehnung" zu
            // behandeln ist hier die richtige Richtung: Der Fall entsteht durch
            // eine nicht gesetzte Umgebungsvariable, und daraus darf kein
            // lauterer Ping werden, als jemand gewaehlt hat.
            return keine();
        }
        return new DiscordErwaehnungen(List.of(), List.of(rollenId));
    }

    /** Ob diese Auspraegung ueberhaupt jemanden benachrichtigt - fuers Protokoll. */
    public boolean istStill() {
        return parse.isEmpty() && roles.isEmpty();
    }

    /** Die Gestalt, in der Discord das Feld erwartet. */
    public Map<String, Object> alsKoerperFeld() {
        return Map.of("parse", parse, "roles", roles);
    }

    /**
     * Entschaerft fremden Text, damit er keine Erwaehnung mehr enthalten kann.
     *
     * <p>Ein zweites Schloss neben {@code allowed_mentions}, und es hat einen
     * eigenen Grund: Bei der Auswahl {@code @here} muss die Gattung
     * {@link #GATTUNG_ALLE} erlaubt sein, und die deckt {@code @everyone} mit
     * ab. Ohne diese Zeile koennte ein FC "still" umgehen, indem er
     * {@code @here} waehlt und {@code @everyone} in die Notiz schreibt - die
     * Auswahl waere dann nur noch eine Untergrenze.</p>
     *
     * <p>Eingefuegt wird ein Zero-Width-Space hinter dem {@code @}. Fuer den
     * Leser bleibt der Text unveraendert, fuer Discords Erkennung ist es kein
     * {@code @everyone} mehr. Das ist der uebliche Weg und besser als Loeschen:
     * Wer "@everyone" schreibt, meint meistens etwas mit dem Wort und soll es
     * lesen koennen.</p>
     *
     * @param text darf {@code null} sein - dann kommt {@code null} zurueck
     */
    public static String entschaerfe(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("@everyone", "@" + UNSICHTBAR + "everyone")
                .replace("@here", "@" + UNSICHTBAR + "here")
                // <@123>, <@!123> und <@&123> sind die maschinenlesbaren Formen
                // fuer Nutzer und Rollen. Sie entstehen nicht durch Tippen,
                // sondern durch Kopieren aus Discord - und wirken genauso.
                .replace("<@", "<" + UNSICHTBAR + "@");
    }
}

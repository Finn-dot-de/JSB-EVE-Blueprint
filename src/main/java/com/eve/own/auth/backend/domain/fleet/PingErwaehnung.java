package com.eve.own.auth.backend.domain.fleet;

/**
 * Wen ein Flotten-Ping aus dem Bett holen darf.
 *
 * <p>Je Ping waehlbar und nicht einmal fuer die Anwendung festgelegt: ein
 * Testping und ein "Hostiles im Heimatsystem" sind derselbe Knopf, aber nicht
 * dieselbe Stoerung. Wer nur die Ankuendigung im Kanal stehen haben will,
 * waehlt {@link #STILL}; wer wirklich Leute braucht, {@link #HIER}.</p>
 *
 * <p>Diese Auswahl ist die <b>einzige</b> Quelle fuer das Feld
 * {@code allowed_mentions} der Discord-Nachricht. Nicht der Nachrichtentext -
 * der gehoert dem FC und darf ueber die Lautstaerke nicht mitbestimmen. Was
 * dabei schiefginge, steht bei
 * {@code DiscordErwaehnungen}.</p>
 */
public enum PingErwaehnung {

    /**
     * Kein Ton. Die Nachricht steht im Kanal, aber niemandes Telefon leuchtet.
     *
     * <p>Der Normalfall und deshalb auch die Vorgabe bei fehlender Angabe: Wer
     * eine ganze Corporation wecken will, soll das ausdruecklich anklicken
     * muessen. Ein vergessenes Feld darf nie in die laute Richtung ausfallen.</p>
     */
    STILL,

    /**
     * {@code @here} - alle, die gerade online sind.
     *
     * <p>Nicht {@code @everyone}: der Unterschied sind die Leute, die schlafen.
     * Fuer eine Flotte, die in zehn Minuten formt, ist wer offline ist ohnehin
     * keine Hilfe.</p>
     */
    HIER,

    /**
     * {@code @everyone} - alle Mitglieder des Servers, auch die gerade
     * abwesenden.
     *
     * <p>Die lauteste Stufe und die einzige, die Leute aus dem Feierabend
     * holt. Gedacht fuer Homedefense und Strat-Ops, nicht fuer eine
     * Trainingsflotte. Discord kennt in {@code allowed_mentions} keinen
     * Unterschied zwischen dieser Stufe und {@link #HIER} - beides ist die
     * Gattung {@code everyone}. Getrennt werden sie allein durch das Praefix,
     * das die Anwendung selbst setzt, und dadurch, dass jeder vom FC
     * eingetippte Text vorher entschaerft wird.</p>
     */
    JEDER,

    /**
     * Genau eine hinterlegte Rolle, etwa eine Ping-Rolle, die man abonnieren kann.
     *
     * <p>Welche das ist, waehlt der FC je Ping - ein FC soll gezielt die Gruppe
     * rufen koennen, um die es geht, und nicht immer dieselbe. Die Kennung kommt
     * damit aus der <em>Anfrage</em>, und genau deshalb wird sie im
     * {@code FleetPingService} gegen die im Auth gepflegten Zuordnungen
     * ({@code discord_role_mappings}) geprueft. Ohne diesen Abgleich waere jede
     * Rolle des Servers erreichbar - und ueber ein hineingeschmuggeltes
     * {@code <@...>} sogar eine einzelne Person.</p>
     */
    ROLLE;

    /** Vorgabe, wenn ein Aufrufer nichts angibt - bewusst die leise. */
    public static final PingErwaehnung DEFAULT = STILL;

    /**
     * Wandelt eine uebergebene oder gespeicherte Zeichenkette um.
     *
     * <p>Unbekanntes wird <b>nicht</b> abgewiesen, sondern still: Ein Tippfehler
     * im Frontend soll kein {@code @here} ausloesen, und ein spaeter entfernter
     * Wert soll einen alten Datensatz nicht unlesbar machen.</p>
     */
    public static PingErwaehnung of(String value) {
        if (value == null) {
            return DEFAULT;
        }
        for (PingErwaehnung erwaehnung : values()) {
            if (erwaehnung.name().equalsIgnoreCase(value.trim())) {
                return erwaehnung;
            }
        }
        return DEFAULT;
    }
}

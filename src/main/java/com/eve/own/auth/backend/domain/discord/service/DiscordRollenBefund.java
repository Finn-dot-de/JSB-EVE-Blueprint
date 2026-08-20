package com.eve.own.auth.backend.domain.discord.service;

/**
 * Eine Zeile der Gegenueberstellung: eine Auth-Rolle und das, was in Discord aus
 * ihr geworden ist.
 *
 * <p>Bis hierher sagte die Pruefung nur, <em>dass</em> eine Rolle fehlt. Das ist
 * die Haelfte der Auskunft, die man braucht: "Cap Azubi fehlt" laesst offen, ob
 * die Zuordnung nicht gepflegt ist, ob die Rollen-Id auf dem Server nicht mehr
 * existiert, ob der Bot nicht darf oder ob der Abgleich schlicht noch nicht
 * gelaufen ist. Jede dieser Ursachen verlangt eine andere Handlung - und wer sie
 * nicht unterscheiden kann, sieht in Discord von Hand nach.</p>
 *
 * <p>Deshalb tragen {@link Zustand} und {@link Ursache} die Aussage, nicht der
 * Text. Der Text ist fuer den Leser; verglichen und geprueft wird auf den
 * Aufzaehlungswerten. Ein Befund, der sich nur im Wortlaut von einem anderen
 * unterscheidet, waere in der Anzeige nicht filterbar und im Test nicht
 * unterscheidbar.</p>
 *
 * @param authRolle       die Rolle, wie sie im Auth heisst
 * @param discordRoleId   die hinterlegte Discord-Rolle - {@code null}, wenn es
 *                        keine gibt. Genau dieses {@code null} ist eine der
 *                        Ursachen und wird deshalb nicht wegabgebildet.
 * @param discordRoleName der Name der Rolle auf dem Server, falls er sich lesen
 *                        liess - Kuer, die Id genuegt zur Arbeit
 * @param zustand         vorhanden, fehlt, oder nicht feststellbar
 * @param ursache         warum sie fehlt oder nicht feststellbar ist;
 *                        {@code null}, wenn sie vorhanden ist
 * @param grund           dieselbe Aussage im Klartext, ggf. mit Einzelheiten
 *                        (Zeitpunkt, Fehlermeldung), die kein fester Text kennt
 */
public record DiscordRollenBefund(
        String authRolle,
        String discordRoleId,
        String discordRoleName,
        Zustand zustand,
        Ursache ursache,
        String grund) {

    /**
     * Der Stand einer einzelnen Rolle.
     *
     * <p>{@link #NICHT_FESTSTELLBAR} ist bewusst kein Sonderfall von
     * {@link #FEHLT}. Verweigert Discord die Auskunft, ist ueber die Rollen des
     * Kontos <em>nichts</em> bekannt; wer das als "fehlt" fuehrt, meldet
     * ausgerechnet am Server-Owner saemtliche Rollen als verloren - und zwar
     * dauerhaft, weil sich daran nichts aendern laesst.</p>
     */
    public enum Zustand {
        VORHANDEN,
        FEHLT,
        NICHT_FESTSTELLBAR
    }

    /**
     * Warum eine Auth-Rolle in Discord nicht ankommt.
     *
     * <p>Die Werte sind aus dem Weg abgelesen, den eine Rolle nimmt: Zuordnung
     * pflegen, Konto verknuepfen, Rolle auf dem Server anlegen, Bot-Rolle hoch
     * genug haengen, Abgleich laufen lassen. An jeder dieser Stationen kann es
     * enden, und jedes Ende hat eine andere Abhilfe.</p>
     *
     * <p>{@link #UNBEKANNT} bleibt ausdruecklich stehen. Wo sich keine der
     * bekannten Stationen als Ursache nachweisen laesst, ist "unbekannt" die
     * einzige ehrliche Auskunft - eine geratene waere schlimmer als keine, weil
     * ihr jemand folgt.</p>
     */
    public enum Ursache {

        /** Keine Zeile in {@code discord_role_mappings} - der Bot weiss nicht, welche Rolle gemeint ist. */
        KEIN_MAPPING("Zu dieser Auth-Rolle ist keine Discord-Rolle zugeordnet. "
                + "Der Bot kann sie nicht vergeben, weil er nicht weiss, welche Rolle gemeint ist."),

        /** Die Zeile existiert, das Feld {@code discord_role_id} ist leer. */
        MAPPING_OHNE_ROLLEN_ID("Die Zuordnung zu dieser Auth-Rolle existiert, traegt aber keine "
                + "Discord-Rollen-Id. So gespeichert wird ein geloeschtes Mapping."),

        /** Der Charakter hat sein Discord-Konto nie verknuepft. */
        KEINE_VERKNUEPFUNG("Weder dieser Charakter noch ein Geschwistercharakter hat ein "
                + "Discord-Konto verknuepft. Ohne Konto gibt es niemanden, dem der Bot etwas geben koennte."),

        /** 403: Bot-Rolle zu tief oder Server-Owner. Ueber die Rollen ist nichts aussagbar. */
        ZUGRIFF_VERWEIGERT("Discord verweigert die Auskunft ueber dieses Konto (403). Entweder ist der "
                + "Nutzer Server-Owner, oder die Bot-Rolle steht zu tief. Solange das gilt, laesst sich "
                + "ueber seine Rollen nichts sagen - auch nicht, dass eine fehlt."),

        /** 404: Das Konto ist kein Mitglied des Servers (mehr). */
        KONTO_NICHT_AUF_SERVER("Das Konto ist kein Mitglied des Servers (404). Rollen kann nur tragen, wer da ist."),

        /** Zeitablauf, Rate Limit, abgebrochene Verbindung - keine Aussage ueber Rollen. */
        DISCORD_NICHT_ERREICHBAR("Discord hat nicht geantwortet. Das ist eine Aussage ueber die "
                + "Verbindung, keine ueber die Rollen."),

        /** Die hinterlegte Id steht nicht in der Rollenliste des Servers. */
        ROLLE_AUF_SERVER_UNBEKANNT("Die hinterlegte Rollen-Id gibt es auf dem Server nicht (mehr). "
                + "Meist wurde die Rolle in Discord geloescht oder neu angelegt - dann hat sie eine neue Id."),

        /** Der Abgleich hat dieses Konto seit dem Start noch nicht angefasst. */
        ABGLEICH_STEHT_AUS("Der Abgleich hat dieses Konto seit dem Start der Anwendung noch nicht "
                + "angefasst; er laeuft alle 30 Minuten. Wer nicht warten will, stoesst ihn an."),

        /** Keine der bekannten Stationen erklaert es. Nicht raten. */
        UNBEKANNT("Ursache unbekannt.");

        private final String erklaerung;

        Ursache(String erklaerung) {
            this.erklaerung = erklaerung;
        }

        /** Der Standardtext zu dieser Ursache - Grundlage von {@link DiscordRollenBefund#grund()}. */
        public String erklaerung() {
            return erklaerung;
        }
    }

    /** Die Rolle sitzt. Keine Ursache, kein Grund - sonst stuende an jeder Zeile Text. */
    public static DiscordRollenBefund vorhanden(String authRolle, String discordRoleId, String name) {
        return new DiscordRollenBefund(authRolle, discordRoleId, name, Zustand.VORHANDEN, null, null);
    }

    /** Die Rolle fehlt, und zwar aus diesem Grund. */
    public static DiscordRollenBefund fehlt(String authRolle, String discordRoleId, String name,
                                            Ursache ursache) {
        return new DiscordRollenBefund(authRolle, discordRoleId, name,
                Zustand.FEHLT, ursache, ursache.erklaerung());
    }

    /** Wie {@link #fehlt}, aber mit einem Grund, der mehr weiss als der feste Text. */
    public static DiscordRollenBefund fehlt(String authRolle, String discordRoleId, String name,
                                            Ursache ursache, String grund) {
        return new DiscordRollenBefund(authRolle, discordRoleId, name, Zustand.FEHLT, ursache, grund);
    }

    /**
     * Ueber diese Rolle laesst sich nichts sagen.
     *
     * <p>Getrennt von {@link #fehlt} zu halten ist der ganze Zweck der
     * Unterscheidung: Ein Konto, das 403 liefert, darf keine einzige Zeile
     * erzeugen, die nach Fehler aussieht.</p>
     */
    public static DiscordRollenBefund nichtFeststellbar(String authRolle, String discordRoleId,
                                                        String name, Ursache ursache, String grund) {
        return new DiscordRollenBefund(authRolle, discordRoleId, name,
                Zustand.NICHT_FESTSTELLBAR, ursache, grund);
    }
}

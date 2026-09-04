package com.eve.own.auth.backend.domain.discord.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
public class DiscordBotService {

    /**
     * Wartezeit, wenn Discord bei 429 keine nennt.
     *
     * <p>Discord schickt den Header immer; das hier greift nur bei einer
     * Zwischeninstanz, die ihn verschluckt. Eine Sekunde und nicht fuenf: Wer
     * die Zahl raet, soll sie klein raten - zu kurz gewartet kostet einen
     * weiteren 429, zu lang gewartet kostet den ganzen Durchlauf.</p>
     */
    private static final Duration STANDARD_WARTEZEIT = Duration.ofSeconds(1);

    /**
     * Ab hier wird nicht mehr gewartet, sondern weitergeworfen.
     *
     * <p>Nennt Discord eine laengere Zeit, dann ist es kein Rollenlimit mehr,
     * sondern eine globale Bremse fuer den ganzen Bot. Sie im Aufruf
     * abzusitzen hiesse, den Zeitplan-Faden minutenlang schlafen zu legen -
     * mit ihm alles andere, was an dieser Reihe haengt.</p>
     */
    private static final Duration LAENGSTE_WARTEZEIT = Duration.ofSeconds(30);

    /** Wie lange eine mit 403 abgelehnte Rolle nach dem ersten Fehlschlag ruht. */
    private static final Duration ERSTE_RUHE = Duration.ofHours(1);

    /**
     * Und wie lange hoechstens.
     *
     * <p>Die Deckelung ist der Kern der Daempfung: Eine in Discord
     * hoeher gezogene Bot-Rolle wird spaetestens nach einem Tag von selbst
     * bemerkt, auch wenn niemand den Knopf drueckt. Ohne Deckel liefe die
     * Verdopplung ins Wochenlange, und eine behobene Rangfolge bliebe
     * unbemerkt.</p>
     */
    private static final Duration LAENGSTE_RUHE = Duration.ofHours(24);

    private final RestClient botClient;
    private final String guildId;
    private final String clientId;
    private final String clientSecret;

    /**
     * Der Kanal, in den Flotten-Pings gehen - leer heisst "Funktion aus".
     *
     * <p>Ohne Vorgabewert in der Konfiguration ({@code :}), und das ist der
     * Unterschied zwischen einer abgeschalteten Funktion und einer, die in
     * irgendeinen geratenen Kanal schreibt. Es gibt keinen vernuenftigen
     * Standardkanal; jeder geratene waere der falsche.</p>
     */
    private final String pingKanalId;

    /**
     * Rollen, die Discord zuletzt mit 403 abgelehnt hat - je Konto und Rolle.
     *
     * <p>Nur im Arbeitsspeicher, wie {@link DiscordSyncStand}: Nach einem
     * Neustart darf jede Rolle einmal neu versucht werden. Der Neustart ist
     * genau der Moment, in dem sich in Discord etwas geaendert haben kann,
     * ohne dass es hier jemand mitbekommen hat.</p>
     *
     * <p>Beschraenkt auf verknuepfte Konten mal zugeordnete Rollen, und nur
     * die abgelehnten stehen darin - die Karte bleibt klein.</p>
     */
    private final Map<String, Ruhevermerk> abgelehnteRollen = new ConcurrentHashMap<>();

    /**
     * Warum der Abgleich laeuft - davon haengt ab, ob die Daempfung greift.
     *
     * <p>{@link #ZEITPLAN} laeuft alle dreissig Minuten und ungefragt; hier
     * kostet jeder aussichtslose Versuch dauerhaft Aufrufe. {@link #ANGESTOSSEN}
     * kommt von einem Menschen, der gerade etwas geaendert hat - typischerweise
     * die Bot-Rolle hoeher gezogen. Ihn auf die Ruhezeit zu vertroesten hiesse,
     * ihm die Antwort auf genau die Frage zu verweigern, wegen der er drueckt.</p>
     */
    public enum Anlass {
        ANGESTOSSEN,
        ZEITPLAN
    }

    /**
     * Ein abgelehnter Versuch samt der Zeit, ab der es sich wieder lohnt.
     *
     * @param fehlversuche wie oft hintereinander abgelehnt - treibt die Verdopplung
     * @param nichtVor     ab wann erneut versucht werden darf
     */
    private record Ruhevermerk(int fehlversuche, Instant nichtVor) {}

    public DiscordBotService(RestClient.Builder builder,
                             @Value("${discord.bot-token}") String botToken,
                             @Value("${discord.server-id}") String guildId,
                             @Value("${discord.client-id}") String clientId,
                             @Value("${discord.client-secret}") String clientSecret,
                             @Value("${discord.fleet-ping-channel-id:}") String pingKanalId) {
        this.guildId = guildId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.pingKanalId = pingKanalId == null ? "" : pingKanalId.trim();
        if (this.pingKanalId.isBlank()) {
            // Einmal beim Start und laut, statt bei jedem Versuch leise: Wer die
            // Anwendung hochfaehrt, soll erfahren, dass die Flotten-Pings
            // abgeschaltet sind - und nicht erst der FC, der um drei Uhr nachts
            // auf den Knopf drueckt.
            log.warn("discord.fleet-ping-channel-id ist nicht gesetzt - Flotten-Pings sind "
                    + "abgeschaltet. Setze DISCORD_FLEET_PING_CHANNEL_ID, um sie einzuschalten.");
        }
        // Client für Bot-Befehle (mit Bot-Token)
        this.botClient = builder.baseUrl("https://discord.com/api/v10")
                .defaultHeader("Authorization", "Bot " + botToken)
                .build();
    }

    /**
     * Ein Mitglied, wie Discord es liefert - gebraucht werden {@code roles} und {@code nick}.
     *
     * <p>{@code ignoreUnknown}, weil die Antwort noch ein Dutzend weiterer
     * Felder traegt (user, joined_at, flags ...) und Discord jederzeit neue
     * hinzufuegen darf. Ohne das wuerde ein Feld, das niemanden hier
     * interessiert, die Pruefung zum Absturz bringen.</p>
     *
     * <p>{@code nick} steht dabei, seit der Abgleich vergleicht statt zu
     * setzen: Der Spitzname war der letzte Schreibzugriff, der bei jedem Lauf
     * ungefragt hinausging, auch wenn er laengst richtig stand.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildMember(List<String> roles, String nick) {}

    /**
     * Liest, welche Rollen ein Mitglied in Discord <b>tatsaechlich</b> traegt.
     *
     * <p>Der erste lesende Aufruf dieser Klasse. Bis hierher waren alle vier
     * Aufrufe schreibend: das Auth schickte seinen Soll-Zustand hinaus und
     * erfuhr nie, was daraus wurde. Ein 403 je Rolle wird protokolliert und
     * dann vergessen - ob die Rolle am Ende sass, stand nirgends.</p>
     *
     * <p>Wirft weiter, statt zu schlucken: Ob Discord die Auskunft verweigert
     * (403 am Server-Owner, oder die Bot-Rolle steht zu tief) oder das Mitglied
     * gar nicht mehr da ist (404), muss der Aufrufer unterscheiden koennen.
     * Wer beides zu einer leeren Liste einebnete, meldete anschliessend jede
     * Soll-Rolle als fehlend - ein Fehlalarm ueber genau die Konten, bei denen
     * man am wenigsten weiss.</p>
     *
     * @return die Rollen-Ids des Mitglieds, auch die von Hand vergebenen
     */
    public List<String> getMemberRoles(String discordUserId) {
        return getMember(discordUserId).roles();
    }

    /**
     * Dasselbe wie {@link #getMemberRoles}, nur mit dem Spitznamen dazu.
     *
     * <p>Ein Aufruf statt zwei: Der Abgleich braucht beides, und Discord
     * liefert beides in derselben Antwort. Sie zweimal zu holen waere der
     * teuerste Weg zur selben Auskunft.</p>
     *
     * @return nie {@code null}, aber {@link GuildMember#nick()} darf es sein -
     *         dann traegt das Mitglied seinen Discord-Namen
     */
    public GuildMember getMember(String discordUserId) {
        GuildMember mitglied = mitGeduld(() -> botClient.get()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .retrieve()
                .body(GuildMember.class));
        // Discord liefert das Feld immer; die Absicherung kostet nichts und
        // haelt einen leeren Koerper von der Auswertung fern.
        if (mitglied == null) {
            return new GuildMember(List.of(), null);
        }
        return new GuildMember(
                mitglied.roles() == null ? List.of() : List.copyOf(mitglied.roles()),
                mitglied.nick());
    }

    /**
     * Fuehrt einen Aufruf aus und wiederholt ihn genau einmal, wenn Discord bremst.
     *
     * <p>Bisher wurde bei 429 pauschal fuenf Sekunden gewartet und der Nutzer
     * uebersprungen - der Header {@code Retry-After}, in dem Discord die
     * tatsaechliche Wartezeit nennt, blieb ungelesen. Das war in beide
     * Richtungen falsch: Bei einer Wartezeit von 0,4 Sekunden verschenkte der
     * Durchlauf viereinhalb, und bei einer laengeren lief er in den naechsten
     * 429 hinein.</p>
     *
     * <p>Nur ein Wiederholungsversuch. Wer beliebig oft nachfasst, macht aus
     * einer Bremse eine Endlosschleife; der zweite 429 wandert nach oben, wo
     * der Aufrufer ihn als das behandeln kann, was er ist - ein Grund,
     * spaeter wiederzukommen.</p>
     */
    private <T> T mitGeduld(Supplier<T> aufruf) {
        try {
            return aufruf.get();
        } catch (HttpClientErrorException.TooManyRequests e) {
            Duration warten = wartezeit(e);
            if (warten.compareTo(LAENGSTE_WARTEZEIT) > 0) {
                // Nicht absitzen: So lange bremst Discord nur global. Der
                // Aufrufer soll den Durchlauf beenden, statt hier zu schlafen.
                log.warn("Discord bremst fuer {} - das ist zu lang zum Abwarten.", warten);
                throw e;
            }
            log.info("Discord bremst (429), Retry-After {} ms - so lange und keine Sekunde laenger.",
                    warten.toMillis());
            pausiere(warten);
            return aufruf.get();
        }
    }

    /**
     * Wie lange Discord bei einem 429 gewartet haben will.
     *
     * <p>Oeffentlich, weil der Zeitplan dieselbe Auskunft braucht, wenn ein
     * Durchlauf trotz Wiederholung in die Bremse laeuft. Zwei Stellen, die
     * denselben Header verschieden auslegen, waeren ein Fehler mit Ansage.</p>
     *
     * <p>Discord nennt Sekunden, gern mit Nachkommastellen ({@code 0.75}).
     * Steht dort etwas anderes - ein Datum nach RFC, oder gar nichts -, gilt
     * die Standardwartezeit; geraten wird lieber kurz.</p>
     */
    public static Duration wartezeit(HttpClientErrorException.TooManyRequests e) {
        HttpHeaders kopf = e.getResponseHeaders();
        String angabe = kopf == null ? null : kopf.getFirst(HttpHeaders.RETRY_AFTER);
        if (angabe == null || angabe.isBlank()) {
            return STANDARD_WARTEZEIT;
        }
        try {
            double sekunden = Double.parseDouble(angabe.trim());
            // Negativ oder Null waere eine Aufforderung, sofort erneut
            // anzuklopfen - genau das Verhalten, das den 429 erzeugt hat.
            return sekunden <= 0
                    ? STANDARD_WARTEZEIT
                    : Duration.ofMillis(Math.round(sekunden * 1000));
        } catch (NumberFormatException nichtInSekunden) {
            return STANDARD_WARTEZEIT;
        }
    }

    /**
     * Legt den Faden schlafen - ueberschreibbar, damit Tests nicht warten muessen.
     *
     * <p>Ohne diese Naht liesse sich die Zusicherung "es wird die genannte Zeit
     * gewartet" nur pruefen, indem der Test sie tatsaechlich absitzt.</p>
     */
    protected void pausiere(Duration dauer) {
        if (dauer.isNegative() || dauer.isZero()) {
            return;
        }
        try {
            Thread.sleep(dauer.toMillis());
        } catch (InterruptedException e) {
            // Das Flag wieder setzen: Wer unterbricht, faehrt herunter, und
            // der naechste blockierende Aufruf soll das merken.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warten auf Discord wurde unterbrochen", e);
        }
    }

    /** Fuer Tests: die aktuelle Zeit, damit sich Ruhezeiten vorspulen lassen. */
    protected Instant jetzt() {
        return Instant.now();
    }

    /** Eine Rolle des Servers - gebraucht werden Id und Name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildRole(String id, String name) {}

    /**
     * Liest die Rollen, die es auf dem Server ueberhaupt gibt.
     *
     * <p>Zwei Dinge haengen daran, die sich ohne diese Liste nicht sagen lassen.
     * Erstens die Ursache: Steht in der Zuordnung eine Id, die auf dem Server
     * niemand kennt, dann fehlt die Rolle nicht wegen des Bots - sie wurde in
     * Discord geloescht oder neu angelegt und hat seither eine andere Id. Ohne
     * die Liste bliebe genau dieser Fall unter "unbekannt" liegen. Zweitens der
     * Name: In der Uebersicht steht sonst nur eine achtzehnstellige Zahl, und
     * niemand weiss, welche Rolle das ist.</p>
     *
     * <p>Ein Aufruf je Durchlauf, nicht je Konto - die Liste gilt fuer den
     * ganzen Server.</p>
     */
    public List<GuildRole> getGuildRoles() {
        GuildRole[] rollen = mitGeduld(() -> botClient.get()
                .uri("/guilds/{guildId}/roles", guildId)
                .retrieve()
                .body(GuildRole[].class));
        return rollen == null ? List.of() : List.of(rollen);
    }

    // --- DTOs für die Discord API Antworten ---
    public record DiscordTokenResponse(String access_token, String refresh_token, Integer expires_in) {}
    public record DiscordUserResponse(String id, String username) {}

    // 1. Code gegen Token tauschen
    public DiscordTokenResponse exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        return RestClient.create().post()
                .uri("https://discord.com/api/v10/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(DiscordTokenResponse.class);
    }

    // 2. Discord Profil des Nutzers laden
    public DiscordUserResponse getDiscordUserProfile(String userAccessToken) {
        return RestClient.create().get()
                .uri("https://discord.com/api/v10/users/@me")
                .header("Authorization", "Bearer " + userAccessToken)
                .retrieve()
                .body(DiscordUserResponse.class);
    }

    // 3. User auf den Server einladen (JETZT MIT NICKNAME)
    public void addMemberToServer(String discordUserId, String userAccessToken, List<String> discordRoleIds, String nickname) {
        Map<String, Object> body = new HashMap<>();
        body.put("access_token", userAccessToken);
        body.put("roles", discordRoleIds);
        if (nickname != null && !nickname.isBlank()) {
            body.put("nick", nickname.length() > 32 ? nickname.substring(0, 32) : nickname);
        }

        botClient.put()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // 4. Rollen und Nickname synchronisieren
    public void syncMemberData(String discordUserId, List<String> discordRoleIds, String nickname) {
        Map<String, Object> body = new HashMap<>();
        body.put("roles", discordRoleIds);
        if (nickname != null && !nickname.isBlank()) {
            body.put("nick", nickname.length() > 32 ? nickname.substring(0, 32) : nickname);
        }

        botClient.patch()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Setzt nur die Rollen, die dieses Auth verwaltet - und laesst alle anderen in Ruhe.
     *
     * <p>Ersetzt {@link #syncMemberData}, weil dessen Feld {@code roles} bei
     * Discord ein <b>Vollersatz</b> ist, kein Zuwachs. Der alte Weg schickte die
     * Liste der gemappten Auth-Rollen und damit zugleich den Befehl, alles andere
     * zu entfernen: Farbrollen, Pingrollen, alles von Hand Vergebene. Bei einem
     * Konto mit fuenf Auth-Rollen und einem einzigen Mapping ging jede halbe
     * Stunde der Rest verloren.</p>
     *
     * <p>Dass es niemandem auffiel, war Zufall: die einzige verknuepfte Person war
     * der IT-Admin, dessen Rolle ueber der Bot-Rolle steht - dort scheitert der
     * Aufruf mit 403, bevor er Schaden anrichtet. Bei normalen Mitgliedern greift
     * dieser Schutz nicht.</p>
     *
     * <p>Deshalb einzeln statt am Stueck: je verwalteter Rolle ein PUT oder ein
     * DELETE. Was das Auth nicht verwaltet, wird nicht angefasst - es kann gar
     * nicht mehr verloren gehen.</p>
     *
     * <p><b>Und deshalb wird zuerst gelesen.</b> Auch einzeln ging der Schreib-
     * zugriff blind hinaus: je Konto und Zuordnung einer, bei jedem Lauf, auch
     * wenn seit Monaten alles richtig stand. Bei dreissig Konten und zehn
     * Zuordnungen sind das dreihundert Schreibzugriffe alle dreissig Minuten,
     * und Discord antwortete mit dem, was in den Logs steht: 429 an fast jedem
     * Nutzer. Jetzt steht ein Lesezugriff am Anfang, und geschrieben wird nur
     * die Differenz. Der Normalfall - es hat sich nichts geaendert - kostet
     * genau einen Aufruf je Konto und ist damit der billigste.</p>
     *
     * <p><b>Der Rueckgabewert ist neu, die Aufrufer sind es nicht.</b> Wer ihn
     * nicht braucht - Zeitplan und Trennen-Endpunkt - ruft die Methode
     * unveraendert auf und laesst ihn liegen. Wer den Abgleich von Hand
     * anstoesst, braucht ihn: ohne Rueckmeldung stuende auch danach nur im Log,
     * was passiert ist, und die Frage "hat es gewirkt?" bliebe unbeantwortet.</p>
     *
     * @param verwalteteRollen alle Discord-Rollen, fuer die es ein Mapping gibt.
     *                         Nur diese werden angefasst.
     * @param sollRollen       die Teilmenge davon, die das Mitglied haben soll
     * @return je verwalteter Rolle, was zu tun war und was daraus wurde - auch
     *         fuer die, an denen nichts zu tun war. Wer den Abgleich anstoesst,
     *         will "steht richtig" lesen und nicht eine leere Liste deuten.
     */
    public List<DiscordRollenErgebnis> syncManagedRoles(String discordUserId,
                                                        Collection<String> verwalteteRollen,
                                                        Collection<String> sollRollen,
                                                        String nickname) {
        // Von Hand angestossen, solange niemand etwas anderes sagt: Der
        // Trennen-Endpunkt und der Knopf sind Einzelfaelle, bei denen ein
        // Mensch auf das Ergebnis wartet.
        return syncManagedRoles(discordUserId, verwalteteRollen, sollRollen, nickname,
                Anlass.ANGESTOSSEN);
    }

    /**
     * Wie oben, aber mit der Angabe, wer den Abgleich ausloest.
     *
     * <p>Der Unterschied betrifft nur Rollen, die Discord zuletzt mit 403
     * abgelehnt hat - siehe {@link Anlass}.</p>
     */
    public List<DiscordRollenErgebnis> syncManagedRoles(String discordUserId,
                                                        Collection<String> verwalteteRollen,
                                                        Collection<String> sollRollen,
                                                        String nickname,
                                                        Anlass anlass) {
        Set<String> verwaltet = new LinkedHashSet<>(verwalteteRollen);
        String gewuenschterName = gekuerzt(nickname);
        if (verwaltet.isEmpty() && gewuenschterName == null) {
            // Nichts zugeordnet, kein Spitzname: Dann gibt es auch nichts
            // nachzusehen. Der Lesezugriff waere hier kein Sparen, sondern ein
            // Aufruf fuer eine Frage, die niemand gestellt hat.
            return List.of();
        }

        // Der eine Lesezugriff, auf dem alles Weitere steht. Er wird bewusst
        // NICHT geschluckt: Wer bei 403 oder 404 den Ist-Zustand nicht kennt,
        // koennte nur wieder blind schreiben - und genau das soll aufhoeren.
        // Der Aufrufer unterscheidet die Faelle ohnehin schon.
        GuildMember mitglied = getMember(discordUserId);
        Set<String> ist = new HashSet<>(mitglied.roles());
        Set<String> soll = new HashSet<>(sollRollen);

        List<DiscordRollenErgebnis> ergebnisse = new ArrayList<>();
        for (String rolle : verwaltet) {
            boolean gewuenscht = soll.contains(rolle);
            DiscordRollenErgebnis.Aktion aktion = gewuenscht
                    ? DiscordRollenErgebnis.Aktion.VERGEBEN
                    : DiscordRollenErgebnis.Aktion.ENTZOGEN;

            if (gewuenscht == ist.contains(rolle)) {
                // Der Normalfall. Kein Aufruf, und der Vermerk ueber einen
                // frueheren 403 faellt weg: Was richtig steht, muss nicht
                // gedaempft werden.
                abgelehnteRollen.remove(vermerkSchluessel(discordUserId, rolle));
                ergebnisse.add(DiscordRollenErgebnis.unveraendert(rolle, aktion));
                continue;
            }

            String schluessel = vermerkSchluessel(discordUserId, rolle);
            Ruhevermerk ruhe = abgelehnteRollen.get(schluessel);
            if (anlass == Anlass.ZEITPLAN && ruhe != null && jetzt().isBefore(ruhe.nichtVor())) {
                // Gedaempft, nicht vergessen: Die Rolle steht weiter als
                // gescheitert im Ergebnis, und die Pruefung liest den
                // Ist-Zustand ohnehin selbst. Was hier wegfaellt, ist allein
                // der aussichtslose Schreibzugriff.
                log.debug("Rolle {} bei Nutzer {} ruht nach {} Ablehnungen bis {}.",
                        rolle, discordUserId, ruhe.fehlversuche(), ruhe.nichtVor());
                ergebnisse.add(DiscordRollenErgebnis.gescheitert(rolle, aktion,
                        "Discord hat diese Rolle zuletzt verweigert (403). Der Zeitplan versucht "
                                + "es erst wieder nach " + ruhe.nichtVor()
                                + " - der Anstoss von Hand jederzeit sofort."));
                continue;
            }

            try {
                if (gewuenscht) {
                    mitGeduld(() -> botClient.put()
                            .uri("/guilds/{guildId}/members/{userId}/roles/{roleId}",
                                    guildId, discordUserId, rolle)
                            .retrieve()
                            .toBodilessEntity());
                } else {
                    mitGeduld(() -> botClient.delete()
                            .uri("/guilds/{guildId}/members/{userId}/roles/{roleId}",
                                    guildId, discordUserId, rolle)
                            .retrieve()
                            .toBodilessEntity());
                }
                abgelehnteRollen.remove(schluessel);
                ergebnisse.add(DiscordRollenErgebnis.gelungen(rolle, aktion));
            } catch (HttpClientErrorException.Forbidden e) {
                Instant naechsterVersuch = vermerkeAblehnung(schluessel);
                // WARN, nicht INFO: Ein Abgleich, der bei jedem Lauf scheitert
                // und nichts bewirkt, ist kein Nebenschauplatz. Auf INFO ging
                // die Meldung im Rauschen unter - gemerkt wurde es an der
                // Wirkung, nicht am Log.
                //
                // Und die Rolle wird BENANNT. Discord vergibt nur Rollen, die
                // unter der eigenen des Bots stehen; ohne die Nummer weiss
                // niemand, unter welche er gezogen werden muss. Frueher ging
                // der ganze Aufruf gemeinsam unter, die Nummer war gar nicht
                // zu haben.
                log.warn("Discord verweigert Rolle {} bei Nutzer {} ({}). "
                        + "Entweder steht die Bot-Rolle darunter - dann in den "
                        + "Servereinstellungen hoeher ziehen - oder der Nutzer "
                        + "ist Server-Owner; an dem kann kein Bot etwas aendern. "
                        + "Der Zeitplan laesst die Rolle bis {} ruhen.",
                        rolle, discordUserId, gewuenscht ? "vergeben" : "entziehen",
                        naechsterVersuch);
                // Weitermachen: Eine gesperrte Rolle darf die uebrigen nicht
                // mitreissen. Genau das tat der alte Sammelaufruf.
                ergebnisse.add(DiscordRollenErgebnis.gescheitert(rolle, aktion,
                        "Discord verweigert diese Rolle (403). Entweder steht die Bot-Rolle "
                                + "darunter - dann in den Servereinstellungen hoeher ziehen - oder "
                                + "der Nutzer ist Server-Owner; an dem kann kein Bot etwas aendern."));
            }
        }

        // Der Spitzname genauso: nur schreiben, wenn er abweicht. Er war der
        // letzte Aufruf, der bei jedem Lauf ungefragt hinausging - ein PATCH je
        // Konto und halbe Stunde fuer einen Namen, der sich im Jahr einmal
        // aendert.
        if (gewuenschterName != null && !Objects.equals(gewuenschterName, mitglied.nick())) {
            try {
                mitGeduld(() -> botClient.patch()
                        .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("nick", gewuenschterName))
                        .retrieve()
                        .toBodilessEntity());
            } catch (HttpClientErrorException.Forbidden e) {
                // Der Spitzname ist eine Hoeflichkeit, die Rollen sind die
                // Aufgabe. Frueher warf dieser Aufruf und nahm das gesamte
                // Ergebnis mit: Wer den Abgleich anstiess, bekam eine
                // Fehlermeldung ueber den Spitznamen und kein Wort darueber,
                // dass seine Rollen laengst gesetzt waren. Am Server-Owner
                // scheitert er ohnehin immer.
                log.warn("Discord verweigert den Spitznamen fuer Nutzer {}: {}",
                        discordUserId, e.getMessage());
            }
        }
        return ergebnisse;
    }

    /**
     * Der Spitzname, wie Discord ihn annimmt - oder {@code null}, wenn keiner gesetzt werden soll.
     *
     * <p>Gekuerzt wird vor dem Vergleich und nicht erst beim Senden: Sonst
     * gaelte ein 40 Zeichen langer Name ewig als abweichend, weil Discord die
     * gekuerzten 32 zurueckmeldet - und jeder Lauf schriebe ihn erneut.</p>
     */
    private static String gekuerzt(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        return nickname.length() > 32 ? nickname.substring(0, 32) : nickname;
    }

    /** Konto und Rolle zusammen - gedaempft wird das Paar, nicht die Rolle allein. */
    private static String vermerkSchluessel(String discordUserId, String rolle) {
        return discordUserId + "/" + rolle;
    }

    /**
     * Haelt einen 403 fest und verdoppelt die Ruhezeit.
     *
     * <p>Eine wegen zu tiefer Bot-Rolle abgelehnte Rolle gelingt ohne eine
     * Aenderung <b>in Discord</b> nie. Sie alle dreissig Minuten erneut zu
     * versuchen, kostet dauerhaft Aufrufe fuer ein sicheres Scheitern - und
     * das waren im Log genau die Aufrufe, die neben dem Blindschreiben ins
     * Rate Limit fuehrten.</p>
     *
     * <p>Verdoppelnd statt fest, und bei einer Stunde beginnend: Ein einzelner
     * Fehlschlag kann auch ein Zufall sein und soll bald nachgeholt werden.
     * Was seit Tagen scheitert, braucht keinen halbstuendlichen Beweis
     * mehr.</p>
     *
     * <p>Drei Dinge halten die Daempfung davon ab, eine behobene Rangfolge zu
     * verschlucken: Der Deckel von einem Tag, der Anstoss von Hand, der sie
     * ueberspringt, und die Pruefung, die den Ist-Zustand unabhaengig liest
     * und die Rolle weiter als fehlend meldet.</p>
     *
     * @return ab wann der Zeitplan es erneut versucht
     */
    private Instant vermerkeAblehnung(String schluessel) {
        Ruhevermerk neuer = abgelehnteRollen.compute(schluessel, (k, bisher) -> {
            int versuche = bisher == null ? 1 : bisher.fehlversuche() + 1;
            Duration ruhe = ERSTE_RUHE.multipliedBy(1L << Math.min(versuche - 1, 5));
            if (ruhe.compareTo(LAENGSTE_RUHE) > 0) {
                ruhe = LAENGSTE_RUHE;
            }
            return new Ruhevermerk(versuche, jetzt().plus(ruhe));
        });
        return neuer.nichtVor();
    }

    // 3. User auf den Server einladen
    public void addMemberToServer(String discordUserId, String userAccessToken, List<String> discordRoleIds) {
        Map<String, Object> body = Map.of(
                "access_token", userAccessToken,
                "roles", discordRoleIds
        );

        botClient.put()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // 4. Rollen synchronisieren
    public void syncMemberRoles(String discordUserId, List<String> discordRoleIds) {
        Map<String, Object> body = Map.of("roles", discordRoleIds);

        botClient.patch()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /** Der Kanal einer Direktnachricht - Discord legt ihn bei Bedarf an. */
    private record DmChannel(String id) {}

    /**
     * Schickt einem Nutzer eine Direktnachricht.
     *
     * <p>Zwei Schritte, weil Discord es so verlangt: erst einen DM-Kanal
     * oeffnen, dann hineinschreiben. Der erste Aufruf ist billig und
     * idempotent - Discord liefert bei einem bestehenden Kanal denselben
     * zurueck.</p>
     *
     * <p>Scheitert es, wird es geloggt und nicht geworfen. Ein Nutzer kann
     * Direktnachrichten von Servermitgliedern abgeschaltet haben; das ist sein
     * gutes Recht und darf keinen Zeitplan abbrechen, der noch andere
     * benachrichtigen will.</p>
     *
     * @return ob die Nachricht abgesetzt wurde
     */
    public boolean sendDirectMessage(String discordUserId, String content) {
        if (discordUserId == null || discordUserId.isBlank() || content == null) {
            return false;
        }
        try {
            DmChannel kanal = botClient.post()
                    .uri("/users/@me/channels")
                    .body(java.util.Map.of("recipient_id", discordUserId))
                    .retrieve()
                    .body(DmChannel.class);
            if (kanal == null || kanal.id() == null) {
                return false;
            }
            botClient.post()
                    .uri("/channels/{id}/messages", kanal.id())
                    .body(java.util.Map.of("content", content))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("Direktnachricht an Discord-Nutzer {} nicht zustellbar: {}",
                    discordUserId, e.getMessage());
            return false;
        }
    }

    // ==================================================================
    // Flotten-Pings: in einen festen Kanal posten, aendern, absagen
    // ==================================================================

    /** Die Antwort auf eine gepostete Nachricht - gebraucht wird nur die ID. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GesendeteNachricht(String id) {}

    /**
     * Ob ein Ping-Kanal konfiguriert ist.
     *
     * <p>Oeffentlich, damit die Fachschicht die Funktion <em>vorher</em>
     * abschalten kann statt hinterher einen Fehler zu erklaeren. Ein Knopf, der
     * da ist und immer scheitert, ist schlechter als einer, der gar nicht da
     * ist.</p>
     */
    public boolean istPingKanalKonfiguriert() {
        return !pingKanalId.isBlank();
    }

    /**
     * Postet eine Nachricht in den konfigurierten Flotten-Kanal.
     *
     * <p>Derselbe Weg wie {@link #sendDirectMessage} - {@code POST} auf
     * {@code /channels/{id}/messages} - nur ohne den vorgeschalteten Schritt,
     * der dort erst einen DM-Kanal oeffnet: Dieser Kanal existiert bereits.</p>
     *
     * <p><b>Der Unterschied zu {@link #sendDirectMessage}: hier wird nichts
     * geschluckt.</b> Dort ist ein Fehlschlag hinnehmbar, weil ein Nutzer
     * Direktnachrichten abgeschaltet haben darf und der Zeitplan weiterlaufen
     * soll. Hier haengt am Ergebnis, ob ein Ping als abgesetzt gilt. Wer einen
     * Fehlschlag in ein {@code false} einebnet, laedt den Aufrufer dazu ein, ihn
     * zu uebersehen - und dann steht in der Datenbank eine Flotte, von der der
     * Kanal nie erfahren hat.</p>
     *
     * <p>{@code allowed_mentions} ist ein Pflichtparameter und kein Feld mit
     * Vorgabe. Zur Begruendung siehe {@link DiscordErwaehnungen} - kurz: fehlt
     * das Feld, entscheidet der Fliesstext, wer geweckt wird.</p>
     *
     * @return die Discord-Nachrichten-ID; ohne sie liesse sich die Nachricht nie
     *     wieder aendern oder absagen
     * @throws IllegalStateException wenn kein Kanal konfiguriert ist. Ein
     *     Aufrufer, der {@link #istPingKanalKonfiguriert()} uebergeht, hat einen
     *     Programmierfehler und keinen Betriebszustand.
     * @throws org.springframework.web.client.RestClientException wenn Discord
     *     ablehnt - dem Bot fehlt ein Recht im Kanal, oder er bremst.
     */
    public String posteInKanal(String content, DiscordErwaehnungen erwaehnungen) {
        String id = pflichtKanal();
        Objects.requireNonNull(erwaehnungen, "allowed_mentions ist Pflicht - siehe DiscordErwaehnungen.");

        GesendeteNachricht antwort = mitGeduld(() -> botClient.post()
                .uri("/channels/{id}/messages", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(nachrichtenKoerper(content, erwaehnungen))
                .retrieve()
                .body(GesendeteNachricht.class));

        if (antwort == null || antwort.id() == null || antwort.id().isBlank()) {
            // Discord liefert die ID immer. Kaeme sie doch nicht, waere die
            // Nachricht zwar im Kanal, aber fuer immer unerreichbar - das muss
            // der Aufrufer als Fehlschlag sehen und nicht als halben Erfolg.
            throw new IllegalStateException(
                    "Discord hat die Nachricht angenommen, aber keine Nachrichten-ID geliefert.");
        }
        return antwort.id();
    }

    /**
     * Schreibt eine bereits gepostete Nachricht um.
     *
     * <p>{@code PATCH} auf dieselbe Nachricht und nicht eine zweite daneben: Die
     * Korrektur steht damit an genau der Stelle, an der jemand den Ping gelesen
     * hat. Zwei Nachrichten im Kanal waeren zwei Wahrheiten, und wer nur die
     * erste sieht, fliegt zu einer Flotte, die es nicht mehr gibt.</p>
     *
     * <p>Ein {@code PATCH} loest in Discord <b>keine</b> neue Benachrichtigung
     * aus, auch nicht mit erlaubten Erwaehnungen. Das ist der Grund, warum eine
     * Absage niemanden zweimal weckt - und zugleich die Grenze des Verfahrens:
     * Wer den Kanal nicht noch einmal ansieht, erfaehrt die Absage nicht. Der
     * Preis ist bewusst gezahlt; ein zweites {@code @here} fuer eine <em>nicht</em>
     * stattfindende Flotte waere die schlechtere Stoerung.</p>
     *
     * <p>{@code allowed_mentions} wird trotzdem mitgeschickt: Es entscheidet
     * auch beim Aendern darueber, welche Erwaehnungen Discord im neuen Text
     * ueberhaupt aufloest.</p>
     */
    public void aendereImKanal(String messageId, String content, DiscordErwaehnungen erwaehnungen) {
        String id = pflichtKanal();
        Objects.requireNonNull(messageId, "Ohne Nachrichten-ID gibt es nichts zu aendern.");
        Objects.requireNonNull(erwaehnungen, "allowed_mentions ist Pflicht - siehe DiscordErwaehnungen.");

        mitGeduld(() -> botClient.patch()
                .uri("/channels/{kanal}/messages/{nachricht}", id, messageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(nachrichtenKoerper(content, erwaehnungen))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Der Rumpf jeder ausgehenden Kanalnachricht.
     *
     * <p>Eine Stelle, an der {@code allowed_mentions} gesetzt wird, und keine
     * zweite. Zwei Baustellen fuer denselben Rumpf hiessen: eine davon vergisst
     * das Feld irgendwann, und zwar die, die spaeter dazukommt.</p>
     */
    private Map<String, Object> nachrichtenKoerper(String content, DiscordErwaehnungen erwaehnungen) {
        Map<String, Object> koerper = new HashMap<>();
        koerper.put("content", content == null ? "" : content);
        koerper.put("allowed_mentions", erwaehnungen.alsKoerperFeld());
        return koerper;
    }

    private String pflichtKanal() {
        if (pingKanalId.isBlank()) {
            throw new IllegalStateException(
                    "Es ist kein Flotten-Ping-Kanal konfiguriert (discord.fleet-ping-channel-id).");
        }
        return pingKanalId;
    }
}
package com.eve.own.auth.backend.domain.discord.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

@Slf4j
@Service
public class DiscordBotService {
    private final RestClient botClient;
    private final String guildId;
    private final String clientId;
    private final String clientSecret;

    public DiscordBotService(RestClient.Builder builder,
                             @Value("${discord.bot-token}") String botToken,
                             @Value("${discord.server-id}") String guildId,
                             @Value("${discord.client-id}") String clientId,
                             @Value("${discord.client-secret}") String clientSecret) {
        this.guildId = guildId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        // Client für Bot-Befehle (mit Bot-Token)
        this.botClient = builder.baseUrl("https://discord.com/api/v10")
                .defaultHeader("Authorization", "Bot " + botToken)
                .build();
    }

    /**
     * Ein Mitglied, wie Discord es liefert - gebraucht wird nur {@code roles}.
     *
     * <p>{@code ignoreUnknown}, weil die Antwort noch ein Dutzend weiterer
     * Felder traegt (user, nick, joined_at, flags ...) und Discord jederzeit
     * neue hinzufuegen darf. Ohne das wuerde ein Feld, das niemanden hier
     * interessiert, die Pruefung zum Absturz bringen.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildMember(List<String> roles) {}

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
        GuildMember mitglied = botClient.get()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .retrieve()
                .body(GuildMember.class);
        // Discord liefert das Feld immer; die Absicherung kostet nichts und
        // haelt einen leeren Koerper von der Auswertung fern.
        return mitglied == null || mitglied.roles() == null
                ? List.of()
                : List.copyOf(mitglied.roles());
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
        GuildRole[] rollen = botClient.get()
                .uri("/guilds/{guildId}/roles", guildId)
                .retrieve()
                .body(GuildRole[].class);
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
     * <p><b>Der Rueckgabewert ist neu, die Aufrufer sind es nicht.</b> Wer ihn
     * nicht braucht - Zeitplan und Trennen-Endpunkt - ruft die Methode
     * unveraendert auf und laesst ihn liegen. Wer den Abgleich von Hand
     * anstoesst, braucht ihn: ohne Rueckmeldung stuende auch danach nur im Log,
     * was passiert ist, und die Frage "hat es gewirkt?" bliebe unbeantwortet.</p>
     *
     * @param verwalteteRollen alle Discord-Rollen, fuer die es ein Mapping gibt.
     *                         Nur diese werden angefasst.
     * @param sollRollen       die Teilmenge davon, die das Mitglied haben soll
     * @return je angefasster Rolle, was versucht wurde und was daraus wurde -
     *         in der Reihenfolge der Aufrufe
     */
    public List<DiscordRollenErgebnis> syncManagedRoles(String discordUserId,
                                                        Collection<String> verwalteteRollen,
                                                        Collection<String> sollRollen,
                                                        String nickname) {
        Set<String> soll = new HashSet<>(sollRollen);
        List<DiscordRollenErgebnis> ergebnisse = new ArrayList<>();
        for (String rolle : new LinkedHashSet<>(verwalteteRollen)) {
            boolean gewuenscht = soll.contains(rolle);
            DiscordRollenErgebnis.Aktion aktion = gewuenscht
                    ? DiscordRollenErgebnis.Aktion.VERGEBEN
                    : DiscordRollenErgebnis.Aktion.ENTZOGEN;
            try {
                if (gewuenscht) {
                    botClient.put()
                            .uri("/guilds/{guildId}/members/{userId}/roles/{roleId}",
                                    guildId, discordUserId, rolle)
                            .retrieve()
                            .toBodilessEntity();
                } else {
                    botClient.delete()
                            .uri("/guilds/{guildId}/members/{userId}/roles/{roleId}",
                                    guildId, discordUserId, rolle)
                            .retrieve()
                            .toBodilessEntity();
                }
                ergebnisse.add(DiscordRollenErgebnis.gelungen(rolle, aktion));
            } catch (HttpClientErrorException.Forbidden e) {
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
                        + "ist Server-Owner; an dem kann kein Bot etwas aendern.",
                        rolle, discordUserId, gewuenscht ? "vergeben" : "entziehen");
                // Weitermachen: Eine gesperrte Rolle darf die uebrigen nicht
                // mitreissen. Genau das tat der alte Sammelaufruf.
                ergebnisse.add(DiscordRollenErgebnis.gescheitert(rolle, aktion,
                        "Discord verweigert diese Rolle (403). Entweder steht die Bot-Rolle "
                                + "darunter - dann in den Servereinstellungen hoeher ziehen - oder "
                                + "der Nutzer ist Server-Owner; an dem kann kein Bot etwas aendern."));
            }
        }
        if (nickname != null && !nickname.isBlank()) {
            try {
                botClient.patch()
                        .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("nick",
                                nickname.length() > 32 ? nickname.substring(0, 32) : nickname))
                        .retrieve()
                        .toBodilessEntity();
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
}
package com.eve.own.auth.backend.domain.discord.service;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Stellt fest, ob Discord das traegt, was das Auth vorsieht - und aendert nichts.
 *
 * <p>Der Abgleich schrieb bisher nur. Was aus einem PUT wurde, stand nirgends:
 * ein 403 je Rolle wurde protokolliert und vergessen, und ob die Rolle danach
 * sass, wusste niemand. Diese Pruefung liest den Ist-Zustand und haelt ihn
 * gegen das Soll.</p>
 *
 * <p><b>Sie sagt auch, warum.</b> "Cap Azubi fehlt" ist die Haelfte der Auskunft;
 * ob die Zuordnung fehlt, die Rollen-Id auf dem Server nicht mehr existiert, der
 * Bot nicht darf oder der Abgleich schlicht noch nicht gelaufen ist, verlangt
 * jeweils eine andere Handlung. Die Ursachen stehen in
 * {@link DiscordRollenBefund.Ursache} - abgelesen am Weg, den eine Rolle nimmt,
 * und dort, wo sich keine nachweisen laesst, bleibt es bei "unbekannt".</p>
 *
 * <p><b>Das Soll haengt am Main-Charakter, nicht am einzelnen Charakter.</b>
 * In den echten Daten zeigen zwei Charaktere auf dasselbe Discord-Konto. Der
 * Abgleich lief zweimal ueber dieselbe Person, jedes Mal mit den Rollen eines
 * anderen Charakters - der zweite Lauf ueberschrieb den ersten. Solange beide
 * dieselben Rollen trugen, fiel es nicht auf; sobald sie sich unterschieden,
 * entschied die Reihenfolge der Datenbankzeilen, wer seine Rollen behielt. Am
 * Main festgemacht ist es entschieden statt zufaellig.</p>
 *
 * <p><b>Sie repariert nichts.</b> Ein Werkzeug, das beim Pruefen eingreift,
 * kann man nicht gefahrlos laufen lassen - und schon gar nicht im Zeitplan.
 * Wer die Befunde umsetzen will, ruft den Abgleich; den Plan dafuer liefert
 * {@link #planFuer(Long)}, damit er auf derselben Rechnung steht wie der
 * Befund.</p>
 *
 * <p>Absichtlich ohne {@code @Transactional}: gelesen werden nur Charaktere
 * samt ihren Rollen, und die Rollen haengen als EAGER-Sammlung am Charakter.
 * Es gibt nichts nachzuladen. Eine Transaktion um die Discord-Aufrufe herum
 * wuerde sie nur ueber den ganzen langsamen Durchlauf offen halten.</p>
 */
@Slf4j
@Service
public class DiscordRoleAuditService {

    private final DiscordConnectionRepository connectionRepo;
    private final CharacterRepository characterRepo;
    private final DiscordRoleMappingRepository mappingRepo;
    private final DiscordBotService discordBotService;
    private final DiscordSyncStand syncStand;

    public DiscordRoleAuditService(DiscordConnectionRepository connectionRepo,
                                   CharacterRepository characterRepo,
                                   DiscordRoleMappingRepository mappingRepo,
                                   DiscordBotService discordBotService,
                                   DiscordSyncStand syncStand) {
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.discordBotService = discordBotService;
        this.syncStand = syncStand;
    }

    /**
     * Der Stand der Zuordnungstabelle, einmal gelesen statt je Konto erneut.
     *
     * <p>{@link #authRollenMitZeile} steht neben {@link #discordRolleJeAuthRolle},
     * weil sich sonst zwei Ursachen nicht trennen liessen: gar keine Zeile in
     * {@code discord_role_mappings} und eine Zeile mit leerem Feld sind
     * unterschiedliche Zustaende mit unterschiedlicher Abhilfe. Beide auf
     * "nicht gemappt" einzuebnen hiesse, dem Leser den Unterschied zu
     * verschweigen, den er zum Handeln braucht.</p>
     */
    private record Zuordnungen(Map<String, String> discordRolleJeAuthRolle,
                               Set<String> authRollenMitZeile,
                               Set<String> verwaltet,
                               Map<String, String> authRolleJeDiscordRolle) {
    }

    /**
     * Die Rollen des Servers - oder die Auskunft, dass wir sie nicht kennen.
     *
     * <p>{@link #bekannt} ist der Unterschied zwischen "die Rolle gibt es nicht
     * mehr" und "wir konnten nicht nachsehen". Ohne dieses Merkmal wuerde jeder
     * gescheiterte Abruf der Rollenliste saemtliche Zuordnungen als veraltet
     * melden - der lauteste denkbare Fehlalarm, ausgeloest von einem einzigen
     * Zeitablauf.</p>
     */
    private record Serverrollen(Map<String, String> nameJeId, boolean bekannt) {

        String name(String discordRoleId) {
            return nameJeId.get(discordRoleId);
        }

        boolean fehltAufDemServer(String discordRoleId) {
            return bekannt && !nameJeId.containsKey(discordRoleId);
        }
    }

    /**
     * Prueft jedes verknuepfte Discord-Konto genau einmal.
     *
     * <p>Gruppiert wird nach {@code discord_user_id}, nicht nach Charakter.
     * Das spart nicht nur Aufrufe an eine Schnittstelle mit Rate Limit - es
     * ist der einzige Weg, den Doppelverknuepfungsfall ueberhaupt zu sehen:
     * je Charakter gepruefte Konten liefern zwei Zeilen, die einander
     * widersprechen, und niemand erkennt, dass es dieselbe Person ist.</p>
     *
     * @return ein Ergebnis je Konto, auch die unauffaelligen
     */
    public List<DiscordRoleAudit> pruefeAlle() {
        Zuordnungen zuordnungen = zuordnungen();
        Supplier<Serverrollen> serverrollen = serverrollenAbruf();

        List<DiscordRoleAudit> ergebnisse = new ArrayList<>();
        for (Map.Entry<String, List<DiscordConnection>> eintrag : jeKonto().entrySet()) {
            DiscordRoleAudit befund =
                    pruefeKonto(eintrag.getKey(), eintrag.getValue(), zuordnungen, serverrollen);
            if (befund != null) {
                ergebnisse.add(befund);
            }
        }
        return ergebnisse;
    }

    /**
     * Dieselben Befunde, aufgeschluesselt je Charakter.
     *
     * <p>Kein zweiter Vergleich, sondern eine Sicht auf den ersten: Jeder
     * Charakter eines Kontos bekommt dieselben Zeilen. Wer hier neu rechnete,
     * haette zwei Wahrheiten ueber dieselbe Person - und wuerde der falschen
     * glauben, sobald sie sich unterscheiden.</p>
     *
     * <p>Enthalten sind nur Charaktere mit Verknuepfung. Ein Charakter ohne
     * Discord-Konto steht in keiner Liste, die aus {@code discord_connections}
     * kommt; nach ihm fragt man einzeln, siehe {@link #pruefeCharakter(Long)}.</p>
     */
    public List<DiscordCharacterAudit> pruefeCharaktere() {
        List<DiscordCharacterAudit> ergebnisse = new ArrayList<>();
        for (DiscordRoleAudit befund : pruefeAlle()) {
            for (DiscordRoleAudit.CharakterSoll charakter : befund.charaktere()) {
                ergebnisse.add(sichtAuf(befund, charakter.characterId(), charakter.name()));
            }
        }
        return ergebnisse;
    }

    /**
     * Die Gegenueberstellung fuer genau einen Charakter.
     *
     * <p>Kostet einen Aufruf an Discord statt einen je Konto - gedacht fuer die
     * Frage unmittelbar nach einer Aenderung ("hat es gewirkt?") und fuer die
     * Ruecksicht nach einem angestossenen Abgleich.</p>
     *
     * @return leer, wenn es den Charakter nicht gibt - das ist eine andere
     *         Auskunft als "nicht verknuepft" und darf nicht dieselbe Antwort
     *         bekommen
     */
    public Optional<DiscordCharacterAudit> pruefeCharakter(Long characterId) {
        Character charakter = characterId == null
                ? null
                : characterRepo.findById(characterId).orElse(null);
        if (charakter == null) {
            return Optional.empty();
        }

        Zuordnungen zuordnungen = zuordnungen();
        String konto = kontoFuer(charakter);
        if (konto == null) {
            return Optional.of(ohneVerknuepfung(charakter, zuordnungen));
        }

        List<DiscordConnection> verbindungen = verbindungenZu(konto);
        DiscordRoleAudit befund =
                pruefeKonto(konto, verbindungen, zuordnungen, serverrollenAbruf());
        if (befund == null) {
            // Kann nur passieren, wenn zu dem Konto kein Charakter mehr
            // auffindbar ist - dann ist der hier gefragte auch nicht daran
            // beteiligt, und "keine Verknuepfung" ist die richtige Auskunft.
            return Optional.of(ohneVerknuepfung(charakter, zuordnungen));
        }
        return Optional.of(sichtAuf(befund, charakter.getId(), charakter.getName()));
    }

    /**
     * Was der Abgleich fuer diesen Charakter tun wuerde.
     *
     * <p>Liest nichts aus Discord - der Plan steht in der eigenen Datenbank.
     * Er stammt aus derselben Rechnung wie der Befund, damit Anzeige und
     * Abgleich nicht auseinanderlaufen koennen.</p>
     *
     * @return leer, wenn es den Charakter nicht gibt
     */
    public Optional<DiscordRollenplan> planFuer(Long characterId) {
        Character charakter = characterId == null
                ? null
                : characterRepo.findById(characterId).orElse(null);
        if (charakter == null) {
            return Optional.empty();
        }
        Zuordnungen zuordnungen = zuordnungen();
        Character main = main(charakter);
        return Optional.of(new DiscordRollenplan(
                charakter.getId(),
                charakter.getName(),
                main.getId(),
                main.getName(),
                kontoFuer(charakter),
                main.getName(),
                List.copyOf(zuordnungen.verwaltet()),
                sollRollen(main, zuordnungen.discordRolleJeAuthRolle()),
                Map.copyOf(zuordnungen.authRolleJeDiscordRolle())));
    }

    /**
     * Meldet die Befunde ins Log - eigener Zeitplan, nicht im Abgleich.
     *
     * <p>Sechs Stunden statt der halben Stunde des Abgleichs: Ein Befund, der
     * sich achtundvierzig Mal am Tag wiederholt, wird gelesen wie eine
     * Hintergrundfarbe. Und jede Pruefung kostet einen zusaetzlichen Aufruf je
     * Konto an eine Schnittstelle mit Rate Limit, in der der Abgleich schon
     * schreibt.</p>
     *
     * <p>Der Anlauf verzoegert, damit ein Neustart nicht zuerst Discord
     * befragt, waehrend die Charakterdaten noch nachgeladen werden - der
     * Zwischenstand ergaebe Befunde, die zehn Minuten spaeter keine mehr
     * sind.</p>
     *
     * <p>Die Meldung "nicht pruefbar" geht auf INFO, der Rollenbefund auf WARN.
     * Am Server-Owner scheitert das Lesen dauerhaft und ist durch nichts zu
     * beheben; stuende das auf WARN, gewoehnte man sich an eine Warnung, die
     * nie verschwindet, und uebersaehe die daneben, die man abstellen kann.</p>
     */
    @Scheduled(initialDelay = 600_000, fixedRate = 21_600_000)
    public void meldeAbweichungen() {
        for (DiscordRoleAudit befund : pruefeAlle()) {
            if (!befund.pruefbar()) {
                log.info("Discord-Konto {} ({}) nicht pruefbar: {}",
                        befund.discordUserId(), befund.mainCharacterName(), befund.hinweis());
                continue;
            }
            if (!befund.hatBefund()) {
                continue;
            }
            if (befund.sollUneinig()) {
                // Der stille Fall: zwei Charaktere, ein Konto, verschiedene
                // Soll-Rollen. Der Abgleich laeuft beide Male und der letzte
                // gewinnt - ohne dass jemand den Verlust mit dem Auth in
                // Verbindung bringt.
                log.warn("Discord-Konto {} haengt an mehreren Charakteren mit "
                                + "unterschiedlichen Soll-Rollen: {}. Es gilt der Main {} ({}).",
                        befund.discordUserId(), befund.charaktere(),
                        befund.mainCharacterName(), befund.mainCharacterId());
            }
            if (!befund.fehlendeRollen().isEmpty() || !befund.ueberzaehligeRollen().isEmpty()) {
                log.warn("Discord-Konto {} ({}) weicht ab - fehlend: {}, ueberzaehlig: {}",
                        befund.discordUserId(), befund.mainCharacterName(),
                        befund.fehlendeRollen(), befund.ueberzaehligeRollen());
                // Die Ursachen daneben, sonst steht im Log dieselbe halbe
                // Auskunft wie frueher in der Anzeige.
                befund.rollen().stream()
                        .filter(zeile -> zeile.zustand() == DiscordRollenBefund.Zustand.FEHLT)
                        .forEach(zeile -> log.warn("  {} -> {}: {}",
                                zeile.authRolle(), zeile.ursache(), zeile.grund()));
            }
        }
    }

    // ---- Der Vergleich ---------------------------------------------------

    private DiscordRoleAudit pruefeKonto(String discordUserId,
                                         List<DiscordConnection> verbindungen,
                                         Zuordnungen zuordnungen,
                                         Supplier<Serverrollen> serverrollen) {
        List<Character> charaktere = verbindungen.stream()
                .map(v -> characterRepo.findById(v.getCharacterId()).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Character::getId))
                .toList();
        // Eine Verknuepfung ohne Charakter ist eine Karteileiche - zu ihr laesst
        // sich kein Soll bilden, also auch kein Befund. Sie zu melden hiesse,
        // eine Zeile ohne Handlungsmoeglichkeit in die Liste zu setzen.
        if (charaktere.isEmpty()) {
            return null;
        }

        List<DiscordRoleAudit.CharakterSoll> jeCharakter = charaktere.stream()
                .map(c -> new DiscordRoleAudit.CharakterSoll(
                        c.getId(), c.getName(), sollRollen(c, zuordnungen.discordRolleJeAuthRolle())))
                .toList();

        // Verschiedene Soll-Rollen an einem Konto: der Fall, der still Rollen
        // kostet. Verglichen wird das Soll je Charakter - genau das, was der
        // Abgleich heute nacheinander hinausschickt.
        boolean sollUneinig = jeCharakter.stream()
                .map(DiscordRoleAudit.CharakterSoll::sollRollen)
                .distinct()
                .count() > 1;

        Character main = bestimmeMain(charaktere);
        List<String> authRollen = authRollen(main);

        List<String> ist;
        try {
            ist = discordBotService.getMemberRoles(discordUserId);
        } catch (HttpClientErrorException.Forbidden e) {
            return nichtPruefbar(discordUserId, main, jeCharakter, sollUneinig, authRollen,
                    zuordnungen, serverrollen, DiscordRollenBefund.Ursache.ZUGRIFF_VERWEIGERT,
                    "Discord verweigert die Auskunft (403). Entweder ist der Nutzer "
                            + "Server-Owner, oder die Bot-Rolle steht zu tief.");
        } catch (HttpClientErrorException.NotFound e) {
            return nichtPruefbar(discordUserId, main, jeCharakter, sollUneinig, authRollen,
                    zuordnungen, serverrollen, DiscordRollenBefund.Ursache.KONTO_NICHT_AUF_SERVER,
                    "Das Konto ist kein Mitglied des Servers mehr (404).");
        } catch (RuntimeException e) {
            // Auch ein Zeitablauf oder ein Rate Limit ist keine Aussage ueber
            // Rollen. Ohne diesen Zweig risse ein einziges stolperndes Konto
            // die Pruefung aller uebrigen mit.
            return nichtPruefbar(discordUserId, main, jeCharakter, sollUneinig, authRollen,
                    zuordnungen, serverrollen, DiscordRollenBefund.Ursache.DISCORD_NICHT_ERREICHBAR,
                    "Discord antwortet nicht: " + e.getMessage());
        }

        Set<String> istMenge = new HashSet<>(ist);
        List<DiscordRollenBefund> rollen = authRollen.stream()
                .map(authRolle -> bewerte(authRolle, discordUserId, zuordnungen,
                        serverrollen.get(), istMenge))
                .toList();

        // Abgeleitet, nicht ein zweites Mal gerechnet: Die kurzen Listen sind
        // dieselbe Aussage wie die Zeilen darueber. Wer sie getrennt ermittelte,
        // koennte eine Zeile "vorhanden" nennen, die daneben als fehlend steht.
        Set<String> sollIds = rollen.stream()
                .map(DiscordRollenBefund::discordRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<String> fehlend = rollen.stream()
                .filter(zeile -> zeile.zustand() == DiscordRollenBefund.Zustand.FEHLT)
                .map(DiscordRollenBefund::discordRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<DiscordRoleAudit.VorhandeneRolle> weitere = istMenge.stream()
                .filter(rolle -> !sollIds.contains(rolle))
                .sorted()
                // DIE wichtigste Stelle dieser Klasse. Was das Auth nicht
                // verwaltet, ist KEIN Befund: eine Farb-, Ping- oder
                // Standardrolle steht hier, damit der Leser sieht, was das Konto
                // hat - nicht, damit sie jemand abraeumt. Genau diese
                // Verwechslung hat den Abgleich schon einmal dazu gebracht,
                // handvergebene Rollen zu entfernen, und ein Fehlalarm verleitet
                // dazu, es von Hand zu wiederholen.
                .map(rolle -> new DiscordRoleAudit.VorhandeneRolle(
                        rolle, serverrollen.get().name(rolle),
                        zuordnungen.verwaltet().contains(rolle)))
                .toList();

        List<String> ueberzaehlig = weitere.stream()
                .filter(DiscordRoleAudit.VorhandeneRolle::verwaltet)
                .map(DiscordRoleAudit.VorhandeneRolle::discordRoleId)
                .toList();

        return new DiscordRoleAudit(discordUserId, main.getId(), main.getName(),
                jeCharakter, rollen, weitere, fehlend, ueberzaehlig, true, null, sollUneinig);
    }

    /**
     * Eine Zeile der Gegenueberstellung - und die Ursache, falls sie fehlt.
     *
     * <p>Die Reihenfolge der Fragen ist die Reihenfolge des Weges, den eine
     * Rolle nimmt: erst die eigene Datenbank, dann der Server, dann der
     * Zeitplan. Wer sie umdrehte, meldete "Rolle auf dem Server unbekannt" fuer
     * eine Zuordnung, die gar keine Id enthaelt.</p>
     */
    private DiscordRollenBefund bewerte(String authRolle,
                                        String discordUserId,
                                        Zuordnungen zuordnungen,
                                        Serverrollen serverrollen,
                                        Set<String> ist) {
        if (!zuordnungen.authRollenMitZeile().contains(authRolle)) {
            return DiscordRollenBefund.fehlt(authRolle, null, null,
                    DiscordRollenBefund.Ursache.KEIN_MAPPING);
        }
        String id = zuordnungen.discordRolleJeAuthRolle().get(authRolle);
        if (id == null) {
            return DiscordRollenBefund.fehlt(authRolle, null, null,
                    DiscordRollenBefund.Ursache.MAPPING_OHNE_ROLLEN_ID);
        }
        String name = serverrollen.name(id);
        if (ist.contains(id)) {
            return DiscordRollenBefund.vorhanden(authRolle, id, name);
        }
        if (serverrollen.fehltAufDemServer(id)) {
            return DiscordRollenBefund.fehlt(authRolle, id, null,
                    DiscordRollenBefund.Ursache.ROLLE_AUF_SERVER_UNBEKANNT);
        }
        Optional<Instant> letzterLauf = syncStand.letzterLauf(discordUserId);
        if (letzterLauf.isEmpty()) {
            return DiscordRollenBefund.fehlt(authRolle, id, name,
                    DiscordRollenBefund.Ursache.ABGLEICH_STEHT_AUS);
        }
        // Alles geprueft, nichts nachweisbar: Hier wird nicht geraten. Der
        // Zeitpunkt steht dabei, weil er die einzige Angabe ist, die dem Leser
        // beim Weitersuchen hilft.
        return DiscordRollenBefund.fehlt(authRolle, id, name,
                DiscordRollenBefund.Ursache.UNBEKANNT,
                DiscordRollenBefund.Ursache.UNBEKANNT.erklaerung()
                        + " Zuordnung, Rolle und Zugriff sind in Ordnung, der Abgleich lief zuletzt um "
                        + letzterLauf.get() + " - und die Rolle sitzt trotzdem nicht.");
    }

    /**
     * Ein Konto, ueber dessen Rollen sich nichts sagen laesst.
     *
     * <p>Beide Rollenlisten bleiben leer und jede Zeile sagt "nicht
     * feststellbar" - fuer <b>alle</b> Rollen des Kontos, auch fuer die, deren
     * Zuordnung fehlt. Der Ist-Zustand ist unbekannt, und "unbekannt" ist nicht
     * "leer": Wer hier das Soll als fehlend eintruege, meldete am Server-Owner
     * jedes Mal saemtliche Rollen als verloren. Beim diesem Nutzer ist das der
     * Regelfall, nicht die Ausnahme.</p>
     *
     * <p>{@code sollUneinig} bleibt dagegen stehen: dass zwei Charaktere an
     * einem Konto verschiedene Rollen fordern, steht in der eigenen Datenbank
     * und haengt nicht daran, ob Discord antwortet.</p>
     */
    private DiscordRoleAudit nichtPruefbar(String discordUserId,
                                           Character main,
                                           List<DiscordRoleAudit.CharakterSoll> jeCharakter,
                                           boolean sollUneinig,
                                           List<String> authRollen,
                                           Zuordnungen zuordnungen,
                                           Supplier<Serverrollen> serverrollen,
                                           DiscordRollenBefund.Ursache ursache,
                                           String hinweis) {
        List<DiscordRollenBefund> rollen = authRollen.stream()
                .map(authRolle -> {
                    String id = zuordnungen.discordRolleJeAuthRolle().get(authRolle);
                    return DiscordRollenBefund.nichtFeststellbar(authRolle, id,
                            id == null ? null : serverrollen.get().name(id), ursache, hinweis);
                })
                .toList();
        return new DiscordRoleAudit(discordUserId, main.getId(), main.getName(),
                jeCharakter, rollen, List.of(), List.of(), List.of(), false, hinweis, sollUneinig);
    }

    /**
     * Ein Charakter ohne jede Discord-Verknuepfung.
     *
     * <p>Hier steht "fehlt" und nicht "nicht feststellbar", obwohl der
     * Ist-Zustand ungelesen bleibt - der Unterschied ist wesentlich: Beim 403
     * gibt es ein Konto, dessen Rollen wir nicht sehen duerfen. Hier gibt es
     * ueberhaupt kein Konto, also traegt auch niemand eine Rolle. Das ist eine
     * Tatsache aus der eigenen Datenbank, keine unbeantwortete Frage an
     * Discord.</p>
     */
    private DiscordCharacterAudit ohneVerknuepfung(Character charakter, Zuordnungen zuordnungen) {
        Character main = main(charakter);
        String hinweis = DiscordRollenBefund.Ursache.KEINE_VERKNUEPFUNG.erklaerung();
        List<DiscordRollenBefund> rollen = authRollen(main).stream()
                .map(authRolle -> DiscordRollenBefund.fehlt(authRolle,
                        zuordnungen.discordRolleJeAuthRolle().get(authRolle), null,
                        DiscordRollenBefund.Ursache.KEINE_VERKNUEPFUNG))
                .toList();
        return new DiscordCharacterAudit(charakter.getId(), charakter.getName(),
                main.getId(), main.getName(), null, false, false, hinweis,
                rollen, List.of(), false);
    }

    /** Dieselben Zeilen, nur aus der Sicht eines der Charaktere des Kontos. */
    private DiscordCharacterAudit sichtAuf(DiscordRoleAudit befund, Long characterId, String name) {
        return new DiscordCharacterAudit(characterId, name,
                befund.mainCharacterId(), befund.mainCharacterName(),
                befund.discordUserId(), true, befund.pruefbar(), befund.hinweis(),
                befund.rollen(), befund.weitereDiscordRollen(), befund.sollUneinig());
    }

    // ---- Datenbeschaffung ------------------------------------------------

    private Zuordnungen zuordnungen() {
        Map<String, String> discordRolleJeAuthRolle = new HashMap<>();
        Map<String, String> authRolleJeDiscordRolle = new HashMap<>();
        Set<String> mitZeile = new HashSet<>();
        Set<String> verwaltet = new HashSet<>();
        for (DiscordRoleMapping mapping : mappingRepo.findAll()) {
            mitZeile.add(mapping.getAuthRole());
            String discordRolle = mapping.getDiscordRoleId();
            // Ein leeres Feld heisst "nicht gemappt" - die Verwaltung speichert
            // so das Loeschen eines Mappings. Solche Zeilen duerfen den Kreis
            // der verwalteten Rollen nicht aufblaehen.
            if (discordRolle == null || discordRolle.isBlank()) {
                continue;
            }
            discordRolleJeAuthRolle.put(mapping.getAuthRole(), discordRolle);
            authRolleJeDiscordRolle.putIfAbsent(discordRolle, mapping.getAuthRole());
            verwaltet.add(discordRolle);
        }
        return new Zuordnungen(discordRolleJeAuthRolle, mitZeile, verwaltet, authRolleJeDiscordRolle);
    }

    /**
     * Holt die Rollenliste des Servers hoechstens einmal je Durchlauf - und nur,
     * wenn sie ueberhaupt gebraucht wird.
     *
     * <p>Sind gar keine Konten zu pruefen, faellt der Aufruf weg. Und ein
     * Fehlschlag bleibt folgenlos: dann heisst es "wir kennen die Rollen des
     * Servers nicht", nicht "es gibt sie nicht".</p>
     */
    private Supplier<Serverrollen> serverrollenAbruf() {
        return new Supplier<>() {
            private Serverrollen geladen;

            @Override
            public Serverrollen get() {
                if (geladen == null) {
                    geladen = ladeServerrollen();
                }
                return geladen;
            }
        };
    }

    private Serverrollen ladeServerrollen() {
        try {
            List<DiscordBotService.GuildRole> rollen = discordBotService.getGuildRoles();
            if (rollen == null || rollen.isEmpty()) {
                // Eine leere Liste heisst nicht "der Server hat keine Rollen" -
                // @everyone gibt es immer. Sie heisst, dass wir die Liste nicht
                // haben. Daraus "die hinterlegte Rolle gibt es nicht mehr" zu
                // folgern, waere geraten - und zwar fuer jede Zuordnung
                // gleichzeitig.
                return new Serverrollen(Map.of(), false);
            }
            Map<String, String> nameJeId = new HashMap<>();
            rollen.forEach(rolle -> nameJeId.put(rolle.id(), rolle.name()));
            return new Serverrollen(nameJeId, true);
        } catch (RuntimeException e) {
            log.info("Rollenliste des Servers nicht lesbar ({}). Ohne sie bleibt offen, "
                    + "ob eine hinterlegte Rollen-Id noch existiert.", e.getMessage());
            return new Serverrollen(Map.of(), false);
        }
    }

    /** Alle Verknuepfungen, nach Discord-Konto gruppiert. */
    private Map<String, List<DiscordConnection>> jeKonto() {
        Map<String, List<DiscordConnection>> jeKonto = new LinkedHashMap<>();
        for (DiscordConnection verbindung : connectionRepo.findAll()) {
            String konto = verbindung.getDiscordUserId();
            if (konto == null || konto.isBlank()) {
                continue;
            }
            jeKonto.computeIfAbsent(konto, k -> new ArrayList<>()).add(verbindung);
        }
        return jeKonto;
    }

    private List<DiscordConnection> verbindungenZu(String discordUserId) {
        return connectionRepo.findAll().stream()
                .filter(v -> discordUserId.equals(v.getDiscordUserId()))
                .toList();
    }

    /**
     * Das Discord-Konto, an dem dieser Charakter haengt.
     *
     * <p>Zuerst die eigene Verknuepfung, dann die eines Geschwistercharakters:
     * Das Konto gehoert dem Account, nicht dem einzelnen Charakter. Ist der Alt
     * verknuepft und der Main nicht, sitzen die Rollen des Accounts trotzdem an
     * diesem einen Konto - suchte man nur nach der eigenen Zeile, meldete die
     * Uebersicht dem Main "nicht verknuepft", waehrend seine Rollen in Discord
     * sichtbar sind.</p>
     *
     * <p>Nach Charakter-Id sortiert, damit bei mehreren Geschwistern immer
     * dieselbe gewinnt und nicht die Reihenfolge der Datenbankzeilen.</p>
     */
    private String kontoFuer(Character charakter) {
        // Die eigene Verknuepfung zuerst - sie ist die genauere Auskunft.
        String eigenes = connectionRepo.findById(charakter.getId())
                .map(DiscordConnection::getDiscordUserId)
                .orElse(null);
        if (eigenes != null && !eigenes.isBlank()) {
            return eigenes;
        }
        return connectionRepo.findAll().stream()
                .filter(v -> v.getDiscordUserId() != null && !v.getDiscordUserId().isBlank())
                .filter(v -> gehoertZumSelbenAccount(v.getCharacterId(), charakter))
                .sorted(Comparator.comparing(DiscordConnection::getCharacterId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(DiscordConnection::getDiscordUserId)
                .findFirst()
                .orElse(null);
    }

    private boolean gehoertZumSelbenAccount(Long characterId, Character charakter) {
        if (characterId == null) {
            return false;
        }
        return characterRepo.findById(characterId)
                .map(andere -> andere.getAccountId().equals(charakter.getAccountId()))
                .orElse(false);
    }

    /**
     * Der Charakter, an dem das Soll haengt.
     *
     * <p>Nutzt {@link Character#getAccountId()} - den Weg, den das Projekt
     * bereits ueberall geht. Ein Main traegt seine eigene Kennung in
     * {@code main_character_id} oder gar keine; die Fallunterscheidung
     * beantwortet der Charakter selbst, statt sie hier ein weiteres Mal
     * nachzubauen.</p>
     *
     * <p>Haengen mehrere Charaktere am Konto, gibt ein verknuepfter Main den
     * Ausschlag, sonst der Charakter mit der kleinsten Kennung. Wichtig ist
     * weniger, welcher gewinnt, als dass immer derselbe gewinnt: die
     * Reihenfolge der Datenbankzeilen darf das Ergebnis nicht bestimmen - sie
     * tut es im heutigen Abgleich, und das ist der Fehler.</p>
     */
    private Character bestimmeMain(List<Character> charaktere) {
        Character leitend = charaktere.stream()
                .filter(Character::isMain)
                .findFirst()
                .orElse(charaktere.getFirst());
        return main(leitend);
    }

    private Character main(Character charakter) {
        return characterRepo.findById(charakter.getAccountId()).orElse(charakter);
    }

    /** Die Auth-Rollen eines Charakters, in stabiler Reihenfolge. */
    private List<String> authRollen(Character charakter) {
        if (charakter.getRoles() == null) {
            return List.of();
        }
        // Sortiert, weil die Rollen aus einem Set kommen: ohne das stuenden die
        // Zeilen der Uebersicht bei jedem Aufruf in einer anderen Reihenfolge.
        return charakter.getRoles().stream().sorted().toList();
    }

    /** Die Discord-Rollen, die sich aus den Auth-Rollen eines Charakters ergeben. */
    private List<String> sollRollen(Character charakter, Map<String, String> discordRolleJeAuthRolle) {
        return authRollen(charakter).stream()
                .map(discordRolleJeAuthRolle::get)
                .filter(Objects::nonNull)
                .distinct()
                // Sortiert, damit der Vergleich zweier Charaktere auf die
                // Reihenfolge nicht hereinfaellt: die Auth-Rollen kommen aus
                // einem Set und haetten sonst je Charakter eine andere.
                .sorted()
                .toList();
    }
}

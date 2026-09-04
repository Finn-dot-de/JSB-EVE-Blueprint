package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import com.eve.own.auth.backend.domain.discord.service.DiscordErwaehnungen;
import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.PingZustand;
import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import com.eve.own.auth.backend.domain.fleet.repository.FleetPingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flottenankuendigungen: absetzen, korrigieren, absagen.
 *
 * <h2>Warum die Rechtepruefung hier steht und nicht nur am Endpunkt</h2>
 * <p>Am Controller haengt {@code @PreAuthorize(AccessRules.FLEET_STAFF)}, und das
 * bleibt. Die Annotation gehoert aber zu <em>einem</em> Einstiegspunkt: sie faellt
 * bei einem Umbau lautlos weg und greift gar nicht, wenn ein anderer Dienst diese
 * Methoden direkt ruft. Dieselbe Ueberlegung wie in {@code MiningAdminGuard} -
 * dort haengt Geld dran, hier haengt dran, dass um drei Uhr nachts das Telefon
 * jedes Corp-Mitglieds klingelt.</p>
 *
 * <p>Als private Methode und nicht als eigene Guard-Komponente wie im Mining:
 * dort brauchen <em>zwei</em> Dienste dieselbe Regel, und zweimal ausgeschrieben
 * waere sie zweimal zu aendern. Hier gibt es genau einen Aufrufer. Eine
 * Komponente fuer einen Nutzer waere eine Datei mehr ohne einen Fehler weniger.</p>
 *
 * <p>Geprueft wird am Rollensatz der Entitaet und nicht am Sicherheitskontext -
 * dasselbe Vorgehen wie in {@code RoleAssignmentService}, {@code AuthGroupService}
 * und {@code MiningAdminGuard}. Die drei Namen sind dieselben wie in
 * {@link AccessRules#FLEET_STAFF}; wer sie dort aendert, muss sie hier
 * mitaendern.</p>
 */
@Slf4j
@Service
public class FleetPingService {

    /**
     * Wer pingen darf - dieselben drei Namen wie in {@link AccessRules#FLEET_STAFF}.
     *
     * <p>{@code ROLE_1337} und {@code ROLE_A38} stehen als Zeichenkette da und
     * nicht als Konstante aus {@link SystemRoles}: die beiden entstehen aus
     * Ingame-Titeln, und {@link SystemRoles} fuehrt ausschliesslich die Rollen,
     * die die Anwendung selbst vergibt. Dasselbe Vorgehen wie in
     * {@code AuthGroupService} und {@code AcademyService}.</p>
     *
     * <p>Wer den Kreis in {@link AccessRules#FLEET_STAFF} aendert, muss ihn hier
     * mitaendern - sonst kommt jemand am Endpunkt vorbei und im Dienst nicht,
     * oder schlimmer, umgekehrt.</p>
     */
    private static final Set<String> FC_ROLLEN = Set.of(SystemRoles.DIRECTOR, "ROLE_1337", "ROLE_A38");

    /** Laengenbegrenzungen der Eingabefelder - siehe {@link #begrenzt}. */
    private static final int KURZ = 200;
    private static final int LANG = 1000;

    /**
     * Wie eine Discord-Rollenkennung aussehen darf: nur Ziffern.
     *
     * <p>Discord-Snowflakes sind vorzeichenlose 64-Bit-Zahlen und damit
     * hoechstens zwanzigstellig. Die Pruefung steht <em>vor</em> dem Abgleich
     * mit den Zuordnungen und ist deshalb nicht ueberfluessig: Die Kennung
     * landet in {@code <@&...>} im Nachrichtentext, und dieser Text wird bewusst
     * <b>nicht</b> entschaerft - er ist ja die gewollte Erwaehnung. Eine
     * Kennung wie {@code 1> <@2} wuerde daraus zwei Erwaehnungen machen, die
     * zweite davon eine <em>Person</em>. Genau der Fall, den {@code <@} in
     * {@link DiscordErwaehnungen#entschaerfe} sonst abfaengt.</p>
     *
     * <p>Die Zuordnungen selbst schuetzen davor nicht zuverlaessig: ihr Feld ist
     * ein freies Textfeld unter {@code /admin/discord}, in das jemand genauso
     * gut {@code <@&123>} hineinkopieren kann.</p>
     */
    private static final Pattern ROLLEN_ID = Pattern.compile("\\d{1,20}");

    private final FleetPingRepository pingRepo;
    private final CharacterRepository characterRepo;
    private final DiscordBotService discord;

    /**
     * Die im Auth gepflegten Discord-Zuordnungen - die Liste der Rollen, die
     * dieses Werkzeug ueberhaupt anleuchten darf.
     *
     * <p>Sie ist ab jetzt die Vertrauensgrenze. Welche Rolle ein Ping trifft,
     * kommt aus der Anfrage, und eine Anfrage ist nichts weiter als eine
     * Behauptung; erst der Abgleich mit dieser Tabelle macht daraus eine
     * Erlaubnis. Siehe {@link #gepruefteRolleId}.</p>
     */
    private final DiscordRoleMappingRepository rollenRepo;

    /**
     * Die vorbelegte Rolle aus {@code DISCORD_FLEET_PING_ROLE_ID}.
     *
     * <p>Frueher war das die <em>einzige</em> Rolle, die je angeleuchtet werden
     * konnte, und der Grund dafuer war richtig: die Anfrage darf nicht
     * bestimmen, wessen Telefon klingelt. Nur war die Antwort zu grob - ein FC
     * konnte damit ueberhaupt keine Gruppe gezielt rufen.</p>
     *
     * <p>Geblieben ist sie als <b>Vorbelegung</b>, nicht als Rueckfallebene mit
     * eigenem Recht: Sie wird beim Absetzen genauso gegen die Zuordnungen
     * geprueft wie jede Kennung aus der Anfrage. Damit gibt es <em>eine</em>
     * Liste pingbarer Rollen und nicht eine Liste plus einen stillen Sonderweg
     * an ihr vorbei. Steht hier eine Kennung, die in den Zuordnungen nicht
     * vorkommt, dann ist sie wirkungslos - und ein Rollen-Ping ohne eigene
     * Angabe scheitert mit einer Meldung, die genau das sagt, statt still auf
     * eine Rolle auszuweichen, die niemand mehr kennt.</p>
     */
    private final String vorbelegteRolleId;

    /**
     * Wie lange ein Charakter nach einem Ping warten muss.
     *
     * <p>Der Anlass ist kein boeser Wille, sondern ein Doppelklick: Ein Knopf,
     * der ein paar hundert Millisekunden ueber Discord nachdenkt, wird zweimal
     * gedrueckt - und dann gehen zwei {@code @here} raus. Eine Minute ist lang
     * genug, dass niemand versehentlich hineinlaeuft, und kurz genug, dass ein
     * FC bei einem echten zweiten Anlass nicht warten muss.</p>
     *
     * <p>Auf Null setzbar, damit sie sich fuer Tests und den Notfall abstellen
     * laesst. Der Weg dorthin fuehrt ueber die Konfiguration und nicht ueber die
     * Anfrage - sonst waere die Bremse eine, die der Bremsende selbst loesen
     * kann.</p>
     */
    private final Duration wartezeit;

    public FleetPingService(FleetPingRepository pingRepo,
                            CharacterRepository characterRepo,
                            DiscordBotService discord,
                            DiscordRoleMappingRepository rollenRepo,
                            @Value("${discord.fleet-ping-role-id:}") String vorbelegteRolleId,
                            @Value("${fleet.ping.cooldown:60s}") Duration wartezeit) {
        this.pingRepo = pingRepo;
        this.characterRepo = characterRepo;
        this.discord = discord;
        this.rollenRepo = rollenRepo;
        this.vorbelegteRolleId = vorbelegteRolleId == null ? "" : vorbelegteRolleId.trim();
        this.wartezeit = wartezeit == null ? Duration.ZERO : wartezeit;
    }

    /**
     * Die Angaben eines Pings, wie sie vom Aufrufer kommen.
     *
     * <p>Ein eigener Typ zwischen DTO und Entitaet, damit {@code erwaehnung}
     * bereits als Aufzaehlung ankommt: Die Umwandlung aus der Zeichenkette ist
     * die Stelle, an der aus einem unbekannten Wert {@link PingErwaehnung#STILL}
     * wird - und die gehoert genau einmal an den Rand und nicht in den Dienst.</p>
     *
     * @param rolleId welche Rolle bei {@link PingErwaehnung#ROLLE} gerufen wird.
     *     Ausdruecklich <b>ungeprueft</b>, so wie sie vom Aufrufer kommt - die
     *     Pruefung steht in {@link #gepruefteRolleId} und damit an genau einer
     *     Stelle. Bei jeder anderen Lautstaerke wird das Feld nicht gelesen.
     */
    public record PingBefehl(
            String fleetType,
            String doctrine,
            String formupLocation,
            Instant formupTime,
            String comms,
            Boolean srpCovered,
            String notes,
            PingErwaehnung erwaehnung,
            String rolleId) {}

    /**
     * Eine Rolle, die dieses Werkzeug anleuchten darf.
     *
     * @param discordRoleId die Kennung - das einzige, was Discord interessiert
     * @param authRole der Name der Auth-Rolle, an der die Zuordnung haengt.
     *     Zugleich die Rueckfallebene der Anzeige, wenn Discord schweigt.
     * @param name der Rollenname aus Discord, wenn er zu holen war. Eine nackte
     *     {@code 1539289011737329796} kann niemand zuordnen; der FC waehlt nach
     *     dem Namen, den er im Discord sieht.
     * @param vorbelegt ob das die Rolle aus {@code DISCORD_FLEET_PING_ROLE_ID}
     *     ist - dann steht sie im Formular vorne. Hoechstens eine.
     */
    public record PingRolle(String discordRoleId, String authRole, String name, boolean vorbelegt) {}

    /** Ob die Funktion ueberhaupt eingerichtet ist - fuer die Statusanzeige im Frontend. */
    public boolean istVerfuegbar() {
        return discord.istPingKanalKonfiguriert();
    }

    /**
     * Ob die Auswahl "eine Rolle" ueberhaupt etwas bewirken kann.
     *
     * <p>Fragt die Zuordnungen und nicht mehr die Konfiguration: Seit die Rolle
     * je Ping gewaehlt wird, lautet die Frage nicht "steht eine ID in der .env",
     * sondern "gibt es ueberhaupt etwas zur Auswahl". Beantwortete sie noch das
     * Erste, boete das Formular die Auswahl an, ohne dass eine Rolle darin
     * stuende - und der FC klickte auf ein leeres Feld.</p>
     *
     * <p>Ohne Discord: Der Kreis der Leser ist hier weiter als der der Pinger
     * ({@code FLEET_VIEWERS} statt {@code FLEET_STAFF}), und diese Auskunft darf
     * nicht daran haengen, ob der Bot gerade erreichbar ist.</p>
     */
    public boolean istRolleKonfiguriert() {
        return !bekannteRollenIds().isEmpty();
    }

    /**
     * Die Rollen, die ein FC anpingen kann - mit dem Namen, unter dem er sie
     * kennt.
     *
     * <h2>Wer das sehen darf</h2>
     * <p>Derselbe Kreis, der auch pingen darf, und geprueft im Dienst. Die Liste
     * ist keine Geheimnis-, aber eine Handlungsauskunft: Sie sagt, welche
     * Gruppen dieses Werkzeug aus dem Bett holen kann. Wer damit nichts anfangen
     * darf, braucht sie nicht.</p>
     *
     * <h2>Was <em>nicht</em> darin steht</h2>
     * <ul>
     *   <li>Zuordnungen ohne Discord-Kennung. Das Feld ist optional, und eine
     *       Zuordnung ohne Kennung ist eine Auth-Rolle, der noch keine
     *       Discord-Rolle gegenuebersteht. Sie waehlbar zu machen hiesse, einen
     *       Ping anzubieten, der nirgendwo ankommt.</li>
     *   <li>Kennungen, die keine sind - siehe {@link #ROLLEN_ID}.</li>
     *   <li>Rollen, die es auf dem Server nicht mehr gibt. Discord vergibt beim
     *       Neuanlegen eine neue Kennung; eine geloeschte Rolle bleibt in der
     *       Zuordnung stehen und sieht dort weiter gueltig aus. Erst der
     *       Abgleich mit {@link DiscordBotService#getGuildRoles()} findet das.
     *       Gefiltert wird aber <b>nur</b>, wenn die Liste tatsaechlich kam:
     *       Hiesse ein leeres Ergebnis auch "keine Rolle existiert", dann leerte
     *       ein Discord-Ausfall die Auswahl vollstaendig.</li>
     * </ul>
     *
     * <p>Faellt Discord aus, bleibt die Liste also stehen und zeigt statt der
     * Discord-Namen die Auth-Rollennamen. Ein leeres Auswahlfeld waere die
     * schlechtere Antwort: Der FC koennte dann nicht pingen, obwohl das Pingen
     * selbst noch geht - der Bot postet ueber denselben Kanal, ueber den auch
     * diese Liste kaeme.</p>
     *
     * <p>Bewusst <b>ohne</b> {@code @Transactional}, aus demselben Grund wie
     * {@link #senden}: Der Discord-Aufruf laege sonst in einer offenen
     * Transaktion und hielte eine Datenbankverbindung ueber die gesamte
     * HTTP-Laufzeit. {@code findAll()} bringt seine eigene mit, und etwas
     * Zusammenhaengendes ueber mehrere Lesevorgaenge gibt es hier nicht.</p>
     */
    public List<PingRolle> pingbareRollen(Long actorId) {
        requireFleetStaff(actorId);

        Map<String, String> discordNamen = discordRollenNamen();
        // Doppelte Kennungen sind moeglich: Zwei Auth-Rollen duerfen auf
        // dieselbe Discord-Rolle zeigen. In der Auswahl waeren sie zwei
        // Eintraege mit demselben Namen und derselben Wirkung - der FC muesste
        // raten, welchen er nimmt.
        Map<String, PingRolle> jeKennung = new LinkedHashMap<>();
        for (DiscordRoleMapping zuordnung : rollenRepo.findAll()) {
            String id = normiert(zuordnung.getDiscordRoleId());
            if (!istRollenId(id)) {
                continue;
            }
            if (!discordNamen.isEmpty() && !discordNamen.containsKey(id)) {
                continue;
            }
            String authRolle = normiert(zuordnung.getAuthRole());
            jeKennung.putIfAbsent(id, new PingRolle(
                    id,
                    authRolle,
                    discordNamen.getOrDefault(id, authRolle),
                    id.equals(vorbelegteRolleId)));
        }

        // Nach dem angezeigten Namen sortiert und nicht nach der Kennung: Der FC
        // sucht in dieser Liste nach einem Wort, nicht nach einer Zahl.
        return jeKennung.values().stream()
                .sorted(Comparator.comparing(PingRolle::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ==================================================================
    // Absetzen
    // ==================================================================

    /**
     * Setzt einen Ping ab.
     *
     * <h2>Die Reihenfolge, und warum sie genau so ist</h2>
     * <p>Zwei Speicher muessen zusammenpassen, die nicht gemeinsam committen:
     * Discord und die eigene Datenbank. Es gibt keine Reihenfolge ohne Risiko,
     * also wird die mit dem <em>ertraeglicheren</em> Risiko gewaehlt.</p>
     * <ol>
     *   <li><b>Alles Pruefbare zuerst</b> - Rechte, Wartezeit, Eingaben. Kein
     *       Aufruf nach draussen, solange noch etwas scheitern kann, das man
     *       hier schon weiss. Was Discord einmal gesehen hat, nimmt niemand
     *       zurueck.</li>
     *   <li><b>Der Datensatz entsteht im Speicher, nicht in der Datenbank.</b>
     *       Er wird nur gebraucht, um den Text zu bauen.</li>
     *   <li><b>Discord.</b> Wirft der Aufruf, endet die Methode hier - und es
     *       gibt keinen Ping-Datensatz. Genau das ist die Forderung "schlaegt
     *       Discord fehl, darf kein Ping als gesendet gelten": ein
     *       {@code save()} vor dem Aufruf haette eine Ankuendigung in der
     *       Liste, von der der Kanal nie erfahren hat. Nebenbei bleibt so auch
     *       die Wartezeit unverbraucht, und der FC kann es sofort erneut
     *       versuchen.</li>
     *   <li><b>Speichern, mit der Nachrichten-ID.</b> Sie ist Pflichtfeld der
     *       Entitaet; ein Datensatz ohne sie kann gar nicht entstehen. Damit
     *       gilt auch die Umkehrung: Was gespeichert ist, laesst sich
     *       korrigieren und absagen.</li>
     * </ol>
     * <p>Bleibt <b>ein</b> Fenster: Discord hat gepostet, und das Speichern
     * scheitert. Dann steht eine Nachricht im Kanal, die dieses Werkzeug nie
     * wieder anfassen kann. Das ist der Preis, und er ist der kleinere - die
     * Alternative waere ein Datensatz, der eine Flotte behauptet, die nie
     * angekuendigt wurde. Fuer diesen Fall geht die Nachrichten-ID als ERROR
     * ins Protokoll, damit ein Mensch sie in Discord von Hand loeschen kann.</p>
     *
     * <p>Bewusst <b>ohne</b> {@code @Transactional}: Der Discord-Aufruf laege
     * sonst mitten in einer offenen Transaktion und hielte eine
     * Datenbankverbindung ueber die gesamte HTTP-Laufzeit - dieselbe Falle, die
     * {@code TransactionBoundaryTest} fuer die Token-Erneuerung beschreibt. Die
     * einzelnen Repository-Aufrufe bringen ihre eigene Transaktion mit; etwas
     * Zusammenhaengendes ueber mehrere Schreibvorgaenge gibt es hier nicht.</p>
     *
     * @throws AccessDeniedException wenn der Handelnde nicht zur Flottenfuehrung gehoert
     * @throws FleetPingWartezeitException wenn die Wartezeit noch laeuft
     * @throws FleetPingAbgeschaltetException wenn kein Kanal konfiguriert ist
     */
    public FleetPing senden(Long actorId, PingBefehl befehl) {
        Character actor = requireFleetStaff(actorId);
        requireKanal();
        requireWartezeitAbgelaufen(actorId);

        Instant jetzt = Instant.now();
        FleetPing ping = new FleetPing();
        ping.setFcCharacterId(actor.getId());
        ping.setFcCharacterName(actor.getName());
        uebernehmen(ping, befehl);
        ping.setZustand(PingZustand.GEPOSTET);
        ping.setCreatedAt(jetzt);
        ping.setUpdatedAt(jetzt);

        DiscordErwaehnungen erwaehnungen = erwaehnungenFuer(ping);
        String text = FleetPingNachricht.aufbauen(ping, prefix(ping), false);

        String nachrichtenId = discord.posteInKanal(text, erwaehnungen);
        ping.setDiscordMessageId(nachrichtenId);

        try {
            FleetPing gespeichert = pingRepo.save(ping);
            // Das Protokoll der Rechenschaft: wer, wann, welche Lautstaerke.
            // Der Datensatz sagt dasselbe - aber wenn Datenbank und Discord
            // einmal auseinanderlaufen, ist das hier die Zeile, an der sich
            // nachvollziehen laesst, was tatsaechlich hinausging.
            log.info("Flotten-Ping {} von {} ({}) abgesetzt: Typ '{}', Erwaehnung {}, "
                            + "Discord-Nachricht {}.",
                    gespeichert.getId(), actor.getName(), actor.getId(),
                    gespeichert.getFleetType(), gespeichert.getErwaehnung(), nachrichtenId);
            return gespeichert;
        } catch (RuntimeException e) {
            // Die Nachricht steht bereits im Kanal und ist ab hier verwaist.
            // Die ID gehoert deshalb ins Protokoll und nicht nur in den
            // Stacktrace - ohne sie findet niemand sie zum Loeschen wieder.
            log.error("Flotten-Ping wurde in Discord gepostet (Nachricht {}), liess sich aber "
                            + "nicht speichern. Er ist damit weder aenderbar noch absagbar und "
                            + "muss in Discord von Hand entfernt werden.",
                    nachrichtenId, e);
            throw e;
        }
    }

    // ==================================================================
    // Bearbeiten und Absagen
    // ==================================================================

    /**
     * Aendert einen bereits abgesetzten Ping.
     *
     * <p>Die Wartezeit greift hier <b>nicht</b>: Sie soll den doppelten Ping
     * verhindern, und eine Aenderung ist kein Ping - Discord benachrichtigt bei
     * einem {@code PATCH} niemanden. Sie hier mitzuziehen hiesse, einen FC eine
     * Minute lang mit einem falschen Treffpunkt im Kanal stehen zu lassen.</p>
     */
    public FleetPing bearbeiten(Long actorId, Long pingId, PingBefehl befehl) {
        Character actor = requireFleetStaff(actorId);
        requireKanal();
        FleetPing ping = laden(pingId);
        requireEigenerPingOderDirektor(actor, ping, "aendern");

        if (ping.getZustand() == PingZustand.ABGESAGT) {
            // Ein abgesagter Ping ist ein Endzustand. Ihn zurueckzuholen hiesse,
            // eine Absage im Kanal wieder in eine Ankuendigung zu verwandeln -
            // und zwar lautlos, weil ein PATCH nicht benachrichtigt. Wer doch
            // fliegt, pingt neu.
            throw new IllegalArgumentException(
                    "Ein abgesagter Ping laesst sich nicht mehr aendern. Setze einen neuen ab.");
        }

        uebernehmen(ping, befehl);
        ping.setZustand(PingZustand.GEAENDERT);
        ping.setUpdatedAt(Instant.now());

        String text = FleetPingNachricht.aufbauen(ping, prefix(ping), true);
        // Erst Discord, dann speichern - aus demselben Grund wie beim Absetzen:
        // Was im Kanal steht, ist die Wahrheit, die Leute lesen. Scheitert das
        // Aendern, bleibt der alte Datensatz zum alten Text, und beide passen
        // weiter zusammen.
        discord.aendereImKanal(ping.getDiscordMessageId(), text, erwaehnungenFuer(ping));

        FleetPing gespeichert = pingRepo.save(ping);
        log.info("Flotten-Ping {} von {} ({}) geaendert, Erwaehnung {}.",
                gespeichert.getId(), actor.getName(), actor.getId(), gespeichert.getErwaehnung());
        return gespeichert;
    }

    /**
     * Sagt einen Ping ab: Die Nachricht im Kanal wird zur Absage umgeschrieben.
     *
     * <p>Kein Loeschen der Discord-Nachricht. Eine verschwundene Ankuendigung
     * beantwortet niemandem die Frage, ob er noch andocken soll - sie laesst ihn
     * nur zweifeln, ob er sie sich eingebildet hat. Die Absage steht deshalb an
     * genau der Stelle, an der die Ankuendigung stand.</p>
     */
    public FleetPing absagen(Long actorId, Long pingId, String grund) {
        Character actor = requireFleetStaff(actorId);
        requireKanal();
        FleetPing ping = laden(pingId);
        requireEigenerPingOderDirektor(actor, ping, "absagen");

        if (ping.getZustand() == PingZustand.ABGESAGT) {
            // Ohne diese Zeile schriebe ein zweiter Klick die Absage ueber die
            // Absage - der urspruengliche Text waere dann weg, und im Kanal
            // stuende eine Absage ohne die Flotte, die sie absagt.
            throw new IllegalArgumentException("Dieser Ping ist bereits abgesagt.");
        }

        Instant jetzt = Instant.now();
        ping.setZustand(PingZustand.ABGESAGT);
        ping.setCancelledAt(jetzt);
        ping.setUpdatedAt(jetzt);
        ping.setCancelReason(begrenzt(grund, LANG, "Grund"));

        // Die Absage ist immer still: Ein PATCH benachrichtigt in Discord
        // ohnehin niemanden, und ein zweites @here fuer eine NICHT
        // stattfindende Flotte waere die schlechtere Stoerung von beiden.
        discord.aendereImKanal(ping.getDiscordMessageId(),
                FleetPingNachricht.absage(ping, actor.getName()),
                DiscordErwaehnungen.keine());

        FleetPing gespeichert = pingRepo.save(ping);
        log.info("Flotten-Ping {} von {} ({}) abgesagt.",
                gespeichert.getId(), actor.getName(), actor.getId());
        return gespeichert;
    }

    /**
     * Die letzten Pings - die Rechenschaft.
     *
     * <p>Lesend und deshalb ohne Discord: Wer wissen will, wer gepingt hat, soll
     * die Antwort auch dann bekommen, wenn Discord gerade nicht erreichbar oder
     * gar nicht eingerichtet ist. Ein Rechenschaftsbericht, der mit der Funktion
     * ausfaellt, ueber die er Rechenschaft ablegt, ist keiner.</p>
     */
    @Transactional(readOnly = true)
    public List<FleetPing> letzte() {
        return pingRepo.findTop50ByOrderByCreatedAtDesc();
    }

    // ==================================================================
    // Pruefungen
    // ==================================================================

    /**
     * Stellt sicher, dass der Handelnde zur Flottenfuehrung gehoert.
     *
     * @return den Handelnden - jeder Aufrufer braucht ihn ohnehin fuer den Namen
     *     in der Nachricht und die Protokollzeile
     */
    private Character requireFleetStaff(Long actorId) {
        Character actor = characterRepo.findById(actorId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + actorId + " ist unbekannt."));

        if (FC_ROLLEN.stream().noneMatch(actor::hasRole)) {
            // Dieselbe Ausnahme wie bei einer abgewiesenen @PreAuthorize, damit
            // ApiExceptionHandler daraus ein 403 macht und kein 500.
            throw new AccessDeniedException("Flotten-Pings setzen nur FCs und Direktoren ab.");
        }
        return actor;
    }

    /**
     * Aendern und absagen darf nur, wer den Ping selbst abgesetzt hat - oder ein
     * Direktor.
     *
     * <h2>Warum genau diese zwei</h2>
     * <p>Ein Ping ist eine Aussage <em>im Namen des FC</em>; sein Name steht
     * darunter. Ein zweiter FC, der ihn umschreibt, aendert damit, was ein
     * Kollege gesagt hat, und der erfaehrt es nicht einmal. Das ist der Grund
     * fuer die Grundregel.</p>
     * <p>Die Ausnahme hat einen genauso praktischen Grund: Der haeufigste Fall
     * eines falschen Pings ist der, bei dem der FC danach ausgeloggt ist. Ohne
     * jemanden, der eine tote Flotte absagen kann, steht sie bis zum naechsten
     * Login im Kanal, und Leute fliegen hin. Direktor und nicht "jeder FC",
     * weil das die Rolle ist, die ohnehin fuer die Corporation einsteht - und
     * weil {@code ROLE_1337} und {@code ROLE_A38} aus Ingame-Titeln stammen und
     * damit einen weiteren und beweglicheren Kreis bilden.</p>
     */
    private void requireEigenerPingOderDirektor(Character actor, FleetPing ping, String was) {
        if (actor.getId().equals(ping.getFcCharacterId()) || actor.hasRole(SystemRoles.DIRECTOR)) {
            return;
        }
        throw new AccessDeniedException("Nur " + ping.getFcCharacterName()
                + " selbst oder ein Direktor kann diesen Ping " + was + ".");
    }

    /**
     * Weist einen zweiten Ping desselben Charakters innerhalb der Wartezeit ab.
     *
     * <p>Gezaehlt wird ab dem <em>Absetzen</em> des letzten Pings, unabhaengig
     * davon, ob der noch steht: Ein sofort wieder abgesagter Ping hat trotzdem
     * geklingelt, und genau darum geht es. Der FC, der sich vertan hat, aendert
     * seinen Ping - dafuer gibt es {@link #bearbeiten}, und das ist ohne
     * Wartezeit.</p>
     */
    private void requireWartezeitAbgelaufen(Long actorId) {
        if (wartezeit.isZero() || wartezeit.isNegative()) {
            return;
        }
        pingRepo.findTopByFcCharacterIdOrderByCreatedAtDesc(actorId).ifPresent(letzter -> {
            Instant frei = letzter.getCreatedAt().plus(wartezeit);
            if (frei.isAfter(Instant.now())) {
                long sekunden = Duration.between(Instant.now(), frei).toSeconds() + 1;
                throw new FleetPingWartezeitException(
                        "Zu schnell hintereinander: Der naechste Ping ist in " + sekunden
                                + " Sekunden moeglich. Aendere stattdessen den letzten Ping.");
            }
        });
    }

    private void requireKanal() {
        if (!discord.istPingKanalKonfiguriert()) {
            throw new FleetPingAbgeschaltetException(
                    "Die Flotten-Pings sind nicht eingerichtet: Es fehlt der Discord-Kanal "
                            + "(DISCORD_FLEET_PING_CHANNEL_ID).");
        }
    }

    private FleetPing laden(Long pingId) {
        return pingRepo.findById(pingId).orElseThrow(
                () -> new IllegalArgumentException("Ping " + pingId + " ist unbekannt."));
    }

    // ==================================================================
    // Umsetzung
    // ==================================================================

    /**
     * Uebernimmt die Angaben aus dem Befehl in den Datensatz.
     *
     * <p>{@code erwaehnung} faellt bei fehlender Angabe auf
     * {@link PingErwaehnung#STILL} zurueck. Die Richtung ist entscheidend: Ein
     * vergessenes Feld muss leise ausfallen, nie laut.</p>
     */
    private void uebernehmen(FleetPing ping, PingBefehl befehl) {
        if (befehl == null) {
            throw new IllegalArgumentException("Ein Ping ohne Angaben ist kein Ping.");
        }
        ping.setFleetType(pflicht(befehl.fleetType(), "Flottenart"));
        ping.setFormupLocation(pflicht(befehl.formupLocation(), "Treffpunkt"));
        ping.setDoctrine(begrenzt(befehl.doctrine(), KURZ, "Doktrin"));
        ping.setFormupTime(befehl.formupTime());
        ping.setComms(begrenzt(befehl.comms(), KURZ, "Comms"));
        ping.setSrpCovered(befehl.srpCovered());
        ping.setNotes(begrenzt(befehl.notes(), LANG, "Hinweis"));
        PingErwaehnung erwaehnung =
                befehl.erwaehnung() == null ? PingErwaehnung.DEFAULT : befehl.erwaehnung();
        ping.setErwaehnung(erwaehnung);
        // Immer setzen, auch auf null: Wird ein Ping von ROLLE auf STILL
        // geaendert, bliebe die alte Kennung sonst im Datensatz stehen und die
        // Rechenschaftsliste behauptete eine Rolle, die dieser Ping nicht mehr
        // erwaehnt.
        ping.setErwaehnungRolleId(gepruefteRolleId(erwaehnung, befehl.rolleId()));
    }

    /**
     * *** Die sicherheitskritische Stelle. ***
     *
     * <p>Bis hierher stand die Rollenkennung in der Konfiguration und war damit
     * unbestreitbar - ein Aufrufer konnte sie gar nicht beeinflussen. Jetzt
     * kommt sie aus der Anfrage, und eine Anfrage ist eine Behauptung. Ohne
     * diese Methode duerfte ein FC eine <b>beliebige</b> Kennung schicken: die
     * Rolle der Serverleitung, die einer fremden Corporation im selben Discord -
     * oder, ueber ein hineingeschmuggeltes {@code <@...>}, eine einzelne Person,
     * die dieses Werkzeug nie anklingeln sollte. Der Kreis derer, die pingen
     * duerfen, bliebe dabei unveraendert; nur waere aus "eine hinterlegte Gruppe
     * rufen" ein Werkzeug geworden, mit dem man auf jeden zielen kann.</p>
     *
     * <p>Drei Pruefungen, in dieser Reihenfolge:</p>
     * <ol>
     *   <li><b>Ueberhaupt eine Rolle</b> - fehlt sie in der Anfrage, greift die
     *       Vorbelegung aus {@link #vorbelegteRolleId}. Fehlt auch die, wird
     *       abgewiesen. Nicht stillschweigend auf {@link PingErwaehnung#STILL}
     *       ausweichen: Der FC hat "Rolle" gewaehlt, weil er jemanden erreichen
     *       will. Ein Ping, der aussieht wie abgesetzt und niemanden erreicht,
     *       ist schlimmer als eine Fehlermeldung - genau daran ist hier schon
     *       einmal ein {@code @everyone} lautlos verpufft.</li>
     *   <li><b>Gestalt</b> - siehe {@link #ROLLEN_ID}. Faengt die Kennung ab,
     *       die in Wahrheit ein Stueck Discord-Markup ist.</li>
     *   <li><b>Bekanntheit</b> - die Kennung muss in {@code discord_role_mappings}
     *       stehen. Das ist die eigentliche Grenze: Pingbar ist, was ein Admin
     *       unter {@code /admin/discord} bewusst verknuepft hat, und sonst
     *       nichts.</li>
     * </ol>
     *
     * <p>Gegen die <em>Zuordnungen</em> geprueft und nicht gegen die Rollen des
     * Discord-Servers, obwohl {@link #pingbareRollen} beide kennt: Der Abgleich
     * mit Discord waere ein Aufruf nach draussen mitten im Sendeweg, und ein
     * Ausfall dort machte aus einem erlaubten Ping eine Fehlermeldung. Die
     * Zuordnungen sind die engere Menge und liegen in der eigenen Datenbank -
     * eine dort verzeichnete, in Discord aber geloeschte Rolle laesst den Ping
     * hoechstens ins Leere gehen; sie richtet keinen Schaden an. Aus der
     * <em>Auswahl</em> faellt sie ohnehin heraus.</p>
     *
     * @return die gepruefte Kennung, oder {@code null} bei jeder anderen
     *     Lautstaerke
     */
    private String gepruefteRolleId(PingErwaehnung erwaehnung, String gewuenscht) {
        if (erwaehnung != PingErwaehnung.ROLLE) {
            return null;
        }

        String id = normiert(gewuenscht);
        boolean ausVorbelegung = id.isEmpty();
        if (ausVorbelegung) {
            id = vorbelegteRolleId;
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException(
                    "Zu einem Rollen-Ping gehoert eine Rolle. Waehle eine der im Auth "
                            + "hinterlegten Discord-Rollen aus.");
        }
        if (!istRollenId(id) || !bekannteRollenIds().contains(id)) {
            // Die Meldung nennt die Ursache, weil die beiden Faelle ganz
            // verschiedene Menschen betreffen: Die Vorbelegung repariert der
            // Administrator in der .env oder unter /admin/discord, eine falsche
            // Kennung in der Anfrage betrifft den FC vor dem Formular.
            String meldung = ausVorbelegung
                    ? "Die vorbelegte Ping-Rolle (DISCORD_FLEET_PING_ROLE_ID) ist im Auth nicht "
                            + "als Discord-Rolle hinterlegt und laesst sich deshalb nicht pingen. "
                            + "Waehle eine Rolle aus der Liste."
                    : "Diese Rolle ist im Auth nicht als Discord-Rolle hinterlegt und laesst sich "
                            + "deshalb nicht pingen. Waehlbar ist nur, was unter /admin/discord "
                            + "verknuepft ist.";
            // WARN und nicht INFO: Eine Kennung, die es hier nicht gibt, kommt
            // entweder aus einem veralteten Frontend - oder aus einem Versuch,
            // an der Auswahl vorbei jemanden anzuklingeln. Beides will man
            // sehen, und im zweiten Fall will man wissen, wer es war.
            log.warn("Abgewiesene Ping-Rolle '{}' (Quelle: {}).",
                    id, ausVorbelegung ? "Vorbelegung" : "Anfrage");
            throw new IllegalArgumentException(meldung);
        }
        return id;
    }

    /**
     * Die Kennungen aller im Auth verknuepften Discord-Rollen.
     *
     * <p>Bei jedem Rollen-Ping frisch gelesen und nicht zwischengespeichert: Ein
     * Administrator, der eine Rolle unter {@code /admin/discord} <em>entfernt</em>,
     * erwartet, dass sie ab sofort nicht mehr pingbar ist - und nicht erst nach
     * einem Neustart. Die Tabelle hat eine Handvoll Zeilen.</p>
     */
    private Set<String> bekannteRollenIds() {
        return rollenRepo.findAll().stream()
                .map(zuordnung -> normiert(zuordnung.getDiscordRoleId()))
                .filter(this::istRollenId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Die Rollennamen des Discord-Servers, oder eine leere Karte.
     *
     * <p>Der Fehlerfall wird verschluckt und nicht weitergereicht. Diese Namen
     * sind Beiwerk: Ohne sie steht in der Auswahl der Auth-Rollenname statt des
     * Discord-Namens, und der FC kann trotzdem pingen. Mit einer durchgereichten
     * Ausnahme haette er eine leere Liste und gar keinen Ping - der Ausfall
     * einer Bequemlichkeit haette die Funktion mitgenommen.</p>
     */
    private Map<String, String> discordRollenNamen() {
        try {
            Map<String, String> namen = new LinkedHashMap<>();
            for (DiscordBotService.GuildRole rolle : discord.getGuildRoles()) {
                if (rolle.id() != null && rolle.name() != null) {
                    namen.putIfAbsent(rolle.id(), rolle.name());
                }
            }
            return namen;
        } catch (RuntimeException e) {
            log.warn("Die Rollen des Discord-Servers waren nicht abrufbar; die Auswahl zeigt "
                    + "deshalb die Auth-Rollennamen. Grund: {}", e.toString());
            return Map.of();
        }
    }

    private boolean istRollenId(String id) {
        return id != null && ROLLEN_ID.matcher(id).matches();
    }

    private static String normiert(String wert) {
        return wert == null ? "" : wert.trim();
    }

    /** Die Erwaehnung, wie Discord sie versteht - die einzige Uebersetzungsstelle. */
    private DiscordErwaehnungen erwaehnungenFuer(FleetPing ping) {
        return switch (ping.getErwaehnung()) {
            case STILL -> DiscordErwaehnungen.keine();
            // HIER und JEDER bekommen dasselbe Schloss: Discord kennt in
            // allowed_mentions nur die Gattung "everyone" und keinen
            // Unterschied zwischen @here und @everyone. Welche der beiden
            // tatsaechlich klingelt, entscheidet allein das Praefix in
            // FleetPingNachricht - und dass der Freitext entschaerft ist,
            // verhindert, dass sich ein @here-Ping selbst hochstuft.
            case HIER, JEDER -> DiscordErwaehnungen.alle();
            // Genau die eine gepruefte Kennung und weiterhin NICHT die Gattung
            // "roles": Stuende die in parse, duerfte Discord jede im Freitext
            // genannte Rolle aufloesen, und die Pruefung oben waere Zierde. Was
            // DiscordErwaehnungen.rolle(...) leistet, leistet es hier
            // unveraendert - neu ist nur, dass die Kennung geprueft ist, statt
            // aus der Konfiguration zu stammen.
            case ROLLE -> DiscordErwaehnungen.rolle(ping.getErwaehnungRolleId());
        };
    }

    private String prefix(FleetPing ping) {
        return FleetPingNachricht.erwaehnungsPrefix(ping.getErwaehnung(), ping.getErwaehnungRolleId());
    }

    private String pflicht(String eingabe, String feld) {
        if (eingabe == null || eingabe.isBlank()) {
            // Flottenart und Treffpunkt sind die beiden Angaben, ohne die
            // niemand entscheiden kann, ob er andockt. Ein Ping ohne sie waere
            // Laerm ohne Auskunft.
            throw new IllegalArgumentException(feld + " fehlt.");
        }
        return begrenzt(eingabe, KURZ, feld);
    }

    /**
     * Kuerzt nicht, sondern weist ab.
     *
     * <p>Discord lehnt eine Nachricht ueber 2000 Zeichen komplett ab - der Ping
     * ginge also gar nicht raus, und der FC saehe nur einen Fehler von Discord.
     * Die Grenze hier ist damit keine Schikane, sondern die Stelle, an der er
     * eine Meldung bekommt, mit der er etwas anfangen kann. Abweisen und nicht
     * stillschweigend kuerzen, weil ein halber Treffpunkt schlimmer ist als
     * gar keiner.</p>
     */
    private String begrenzt(String eingabe, int hoechstlaenge, String feld) {
        if (eingabe == null) {
            return null;
        }
        String getrimmt = eingabe.trim();
        if (getrimmt.isEmpty()) {
            return null;
        }
        if (getrimmt.length() > hoechstlaenge) {
            throw new IllegalArgumentException(
                    feld + " ist zu lang (hoechstens " + hoechstlaenge + " Zeichen).");
        }
        return getrimmt;
    }
}

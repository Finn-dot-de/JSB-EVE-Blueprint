package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.entity.AltLinkProposal;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.repository.AltLinkProposalRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schlaegt vor, welcher nicht registrierte Corp-Charakter zu welchem bekannten
 * Konto gehoeren koennte - und welche nicht registrierten Charaktere
 * untereinander vermutlich ein Mensch sind.
 *
 * <h2>Die Ausgangslage, die alles bestimmt</h2>
 * <p>Fuer einen nicht registrierten Charakter existiert in diesem Projekt
 * <b>nichts</b> ausser seiner Charakter-ID und dem ueber ESI nachladbaren Namen.
 * Das ist kein Zufall der Datenlage, sondern die Bauart: jeder Sync-Pfad holt
 * sich zuerst das Token <em>des Charakters selbst</em>. Wer nicht registriert
 * ist, hat kein Token - und deshalb keine Zeile in {@code character_mining},
 * {@code character_assets}, {@code character_skills}, {@code industry_jobs} und
 * allen uebrigen. Nachgezaehlt: null Zeilen in jeder einzelnen dieser Tabellen.</p>
 *
 * <p>Eine Korrelation braucht zwei Seiten. Hier ist eine Seite strukturell leer,
 * und daran aendert die Wahl der Heuristik nichts. Die einzige Ausnahme ist die
 * <em>Mitgliederverfolgung</em> von ESI: sie kommt mit dem Token eines Directors
 * und deckt die ganze Corporation ab, registriert oder nicht. Sie ist deshalb
 * das einzige Signal, das fuer die Zielgruppe ueberhaupt Daten hat.</p>
 *
 * <h2>Die Regel, die dieses Projekt teuer gelernt hat</h2>
 * <p><b>Ein fehlendes Signal ist nicht dasselbe wie ein Signal mit Wert 0.</b>
 * Der Score ist ein gewichteter Mittelwert ueber die <em>tatsaechlich
 * verfuegbaren</em> Signale; ein Signal ohne Datengrundlage wird weggelassen und
 * nicht mit null verrechnet. Sonst kaeme bei fehlenden Daten eine niedrige
 * Wahrscheinlichkeit heraus, die aussieht wie geprueft und verworfen, obwohl
 * nichts geprueft wurde. Welche Signale getragen haben, sagt das DTO Zeile fuer
 * Zeile.</p>
 *
 * <h2>Drei Ansichten, drei verschiedene Dinge</h2>
 * <ul>
 *   <li>{@link #findProbableAlts()} - Vorschlaege gegen <em>bekannte Konten</em>.
 *       Nur diese Liste hat einen Knopf daneben, und der legt eine Vormerkung an.</li>
 *   <li>{@link #findUnregisteredGroups(Long)} - Gruppen unregistrierter
 *       Charaktere <em>untereinander</em>. Eine <b>Beobachtung</b>: es gibt kein
 *       Konto, dem man sie zuordnen koennte, also gibt es hier auch nichts zu
 *       bestaetigen.</li>
 *   <li>{@link #calibrationSample(Long, Integer)} - dieselbe Rechnung ohne
 *       Schwelle, damit die Fuehrung sieht, was der Scorer denkt, statt vor einer
 *       leeren Liste zu raten. Bestaetigt nichts und schreibt nichts.</li>
 * </ul>
 *
 * <h2>Was diese Klasse ausdruecklich NICHT tut</h2>
 * <p>Sie schreibt nichts nach {@code characters.main_character_id}. Warum, steht
 * ausfuehrlich in {@link AltLinkProposal}. Kurz: fuer einen nicht registrierten
 * Charakter gibt es dort keine Zeile, die sich aendern liesse, und die einzige
 * Zuordnung mit Eigentumsnachweis entsteht beim EVE-SSO-Login des Charakters
 * selbst.</p>
 */
@Slf4j
@Service
public class AltDetectionService {

    /** Der Scope, den die Mitgliederverfolgung verlangt - bereits in {@code EVE_SCOPES}. */
    static final String TRACK_MEMBERS_SCOPE = "esi-corporations.track_members.v1";

    static final String SIGNAL_NAME = "NAME";
    static final String SIGNAL_JOIN = "JOIN";
    static final String SIGNAL_MINING = "MINING";

    /** Wieviele Signale es insgesamt gibt - fuer die Anzeige "x von y". */
    static final int SIGNAL_COUNT = 3;

    private final CorporationStatsService corporationStatsService;
    private final CharacterRepository characterRepo;
    private final CharacterMiningRepository miningRepo;
    private final AltLinkProposalRepository proposalRepo;
    private final DirectorTokenProvider directorTokenProvider;
    private final EsiService esiService;
    private final AltDetectionProperties props;
    private final NameSimilarity names;

    public AltDetectionService(CorporationStatsService corporationStatsService,
                               CharacterRepository characterRepo,
                               CharacterMiningRepository miningRepo,
                               AltLinkProposalRepository proposalRepo,
                               DirectorTokenProvider directorTokenProvider,
                               EsiService esiService,
                               AltDetectionProperties props) {
        this.corporationStatsService = corporationStatsService;
        this.characterRepo = characterRepo;
        this.miningRepo = miningRepo;
        this.proposalRepo = proposalRepo;
        this.directorTokenProvider = directorTokenProvider;
        this.esiService = esiService;
        this.props = props;
        // Hier und nicht als Bean: die Namensaehnlichkeit hat keinen eigenen
        // Lebenszyklus, sie ist die Rechenvorschrift dieses Dienstes mit dessen
        // Konfiguration darin.
        this.names = new NameSimilarity(props);
    }

    // ==================================================================
    // Signalwert
    // ==================================================================

    /**
     * Das Ergebnis eines einzelnen Signals.
     *
     * <p>{@code available} und {@code score} sind bewusst getrennt: ein
     * gemessener Wert 0 ("die beiden sind drei Wochen auseinander beigetreten")
     * ist eine Aussage, ein fehlendes Signal ist keine. Zusammengelegt zu einer
     * Zahl waeren beide nicht mehr auseinanderzuhalten - und genau das ist der
     * Fehler, den diese Klasse vermeiden soll.</p>
     */
    record SignalValue(boolean available, int score, String detail) {

        static SignalValue missing(String detail) {
            return new SignalValue(false, 0, detail);
        }

        static SignalValue of(int score, String detail) {
            return new SignalValue(true, Math.clamp(score, 0, 100), detail);
        }
    }

    /**
     * Ein fertig bewertetes Paar, bevor entschieden ist, ob es die Schwelle
     * reisst.
     *
     * <p>Eigener Typ, weil drei Ansichten dasselbe Rechenergebnis
     * <em>verschieden</em> weiterverarbeiten: die Vorschlagsliste filtert es an
     * der Schwelle, die Gruppierung macht daraus eine Kante, die Kalibrierung
     * zeigt es genau dann, wenn es die Schwelle <em>nicht</em> reisst.</p>
     */
    private record ScoredPair(int probability, int signalsUsed,
                              List<CharacterDtos.AltSignalDto> signals) {}

    // ==================================================================
    // Der Vorschlagslauf
    // ==================================================================

    /**
     * Die Vorschlaege ueber alle betreuten Corporations, bester zuerst.
     *
     * <p><b>Rechenaufwand.</b> Je Corporation ist das ein Kreuzprodukt aus nicht
     * registrierten Mitgliedern und bekannten Konten. Echte Zahlen aus dem
     * Bestand: 399 nicht registrierte ueber vier Corporations gegen 11 Konten,
     * groesste Corporation 273 x 11 = rund 3.000 Paare, insgesamt rund 4.400.
     * Ein Namensvergleich ueber zwei Namen von je 20 Zeichen sind rund 400
     * Rechenschritte - das ganze Kreuzprodukt kostet damit Millisekunden. Die
     * Laufzeit steckt vollstaendig in den ESI-Aufrufen: je Corporation eine
     * Mitgliederliste, ein Namensabruf (500 IDs pro Aufruf) und eine
     * Mitgliederverfolgung. Alle drei sind ETag-gecacht bzw. einmalig, und
     * {@link AltDetectionProperties#getMaxPairsPerCorporation()} ist die
     * Reissleine, falls eine Corporation um Groessenordnungen waechst.</p>
     *
     * <p>Je nicht registriertem Charakter bleibt nur der <em>beste</em>
     * Vorschlag stehen. Drei konkurrierende Konten fuer denselben Charakter
     * waeren keine Hilfe, sondern eine Einladung zum Raten.</p>
     */
    @Transactional(readOnly = true)
    public List<CharacterDtos.AltSuggestionDto> findProbableAlts() {
        List<CharacterDtos.AltSuggestionDto> suggestions = new ArrayList<>();
        for (CharacterDtos.CorpStatsDto stats : corporationStatsService.statsForAllCorporations()) {
            suggestions.addAll(suggestionsFor(stats));
        }
        return suggestions.stream()
                .sorted(Comparator.comparingInt(CharacterDtos.AltSuggestionDto::probability).reversed()
                        .thenComparing(CharacterDtos.AltSuggestionDto::unauthedCharName,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<CharacterDtos.AltSuggestionDto> suggestionsFor(CharacterDtos.CorpStatsDto stats) {
        List<CharacterDtos.AltSuggestionDto> alle = allAccountPairs(stats, new HashMap<>());

        Map<Long, CharacterDtos.AltSuggestionDto> beste = new LinkedHashMap<>();
        for (CharacterDtos.AltSuggestionDto pair : alle) {
            beste.merge(pair.unauthedCharId(), pair,
                    (a, b) -> b.probability() > a.probability() ? b : a);
        }

        // Siehe schwelleFuer: ein Vorschlag aus einem einzigen Signal muss hoeher
        // springen als einer, den mehrere Quellen tragen.
        return beste.values().stream()
                .filter(vorschlag -> vorschlag.probability() >= schwelleFuer(vorschlag.signalsUsed()))
                .toList();
    }

    /**
     * Alle bewerteten Paare aus "nicht registrierter Charakter gegen bekanntes
     * Konto" dieser Corporation - <b>ohne</b> Schwelle und ohne Auswahl des
     * besten je Charakter.
     *
     * <p>Ein eigener Schritt, weil drei Ansichten dieselbe Rechnung brauchen und
     * jede etwas anderes damit tut. Waere die Schwelle hier schon eingebaut,
     * koennte die Kalibrieransicht gar nicht zeigen, was knapp darunter liegt -
     * und genau das ist ihr Zweck.</p>
     *
     * @param joinCache Beitrittsdaten je Corporation, ueber einen Aufruf hinweg
     *     gemerkt. Die Kalibrierung rechnet beide Paartabellen derselben
     *     Corporation nacheinander; ohne diese Karte holte sie deren
     *     Mitgliederverfolgung zweimal von ESI - eine Anfrage, deren Ergebnis
     *     sich zwischen den beiden Zeilen unmoeglich geaendert haben kann.
     */
    private List<CharacterDtos.AltSuggestionDto> allAccountPairs(
            CharacterDtos.CorpStatsDto stats, Map<Long, Map<Long, Instant>> joinCache) {
        List<CharacterDtos.UnauthedCharDto> unauthed = openCandidates(stats);
        if (unauthed.isEmpty()) {
            return List.of();
        }

        List<Character> registered = characterRepo.findByCorporationId(stats.corpId());
        Map<Long, List<Character>> byAccount = new LinkedHashMap<>();
        for (Character character : registered) {
            byAccount.computeIfAbsent(character.getAccountId(), id -> new ArrayList<>()).add(character);
        }
        if (byAccount.isEmpty()) {
            return List.of();
        }

        // Der Main sitzt nicht zwingend in derselben Corporation. Sein Name wird
        // deshalb einzeln geladen und NICHT aus der Corp-Statistik uebernommen -
        // die haengt einem auswaertigen Main einen Zusatz an den Namen, und der
        // wuerde den Namensvergleich verfaelschen.
        Map<Long, Character> mains = new LinkedHashMap<>();
        characterRepo.findAllById(byAccount.keySet()).forEach(main -> mains.put(main.getId(), main));

        if (exceedsBudget(stats, (long) unauthed.size() * byAccount.size())) {
            return List.of();
        }

        Map<Long, Instant> joinDates = joinDates(stats.corpId(), joinCache);
        MiningIndex mining = miningIndex(unauthed, byAccount);

        List<CharacterDtos.AltSuggestionDto> result = new ArrayList<>();
        for (CharacterDtos.UnauthedCharDto candidate : unauthed) {
            for (Map.Entry<Long, List<Character>> account : byAccount.entrySet()) {
                Character main = mains.get(account.getKey());
                if (main == null) {
                    continue;
                }
                List<Long> kontoIds = account.getValue().stream().map(Character::getId).toList();
                ScoredPair scored = scorePair(candidate.name(), candidate.id(), main.getName(),
                        kontoIds, joinDates, mining, "Konto \"%s\"".formatted(main.getName()));
                if (scored == null) {
                    continue;
                }
                result.add(new CharacterDtos.AltSuggestionDto(
                        candidate.id(), candidate.name(), main.getId(), main.getName(),
                        scored.probability(), scored.signalsUsed(), SIGNAL_COUNT,
                        scored.signals(), stats.corpId()));
            }
        }
        return result;
    }

    /**
     * Die Schwelle, die ein Ergebnis mit dieser Zahl tragender Signale
     * ueberspringen muss.
     *
     * <p>Nicht fuer alle dieselbe, und der Grund ist gemessen: Traegt nur ein
     * Signal, dann <em>ist</em> der Gesamtwert dieses eine Signal. Bei fehlendem
     * Director-Token heisst das, die Zahl ist woertlich der Namenswert - und dort
     * liegen ein gemeinsamer Nachname (85, in EVE voellig gewoehnlich) und ein
     * durchnummerierter Zwilling (95, ein ernstzunehmender Hinweis) auf derselben
     * Skala nur zehn Punkte auseinander. Eine gemeinsame Schwelle von 80 laesst
     * beide durch, und bei mehreren hundert unregistrierten Mitgliedern besteht
     * die Liste dann ueberwiegend aus Namensvettern - mit einem Knopf daneben,
     * der einen fremden Menschen einem fremden Konto zuschlaegt.</p>
     *
     * <p>Sobald ein zweites Signal traegt, gilt wieder die normale Schwelle: dann
     * stuetzt sich die Zahl nicht mehr auf eine einzige Beobachtung.</p>
     */
    private int schwelleFuer(int signalsUsed) {
        return signalsUsed <= 1
                ? props.getMinProbabilitySingleSignal()
                : props.getMinProbability();
    }

    /**
     * Die nicht registrierten Mitglieder, zu denen es noch keine Vormerkung gibt.
     *
     * <p>Damit verschwindet ein bestaetigter Charakter aus der Liste - sonst
     * bekaeme der Director denselben Vorschlag beim naechsten Aufruf erneut und
     * wuesste nicht, ob sein Klick angekommen ist. Registrierte Charaktere
     * stehen ohnehin nicht in dieser Liste: "nicht registriert" ist genau als
     * "keine Zeile in {@code characters}" definiert.</p>
     */
    private List<CharacterDtos.UnauthedCharDto> openCandidates(CharacterDtos.CorpStatsDto stats) {
        List<CharacterDtos.UnauthedCharDto> unauthed = stats.unauthedMembers();
        if (unauthed == null || unauthed.isEmpty()) {
            return List.of();
        }
        List<Long> ids = unauthed.stream().map(CharacterDtos.UnauthedCharDto::id).toList();
        Set<Long> alreadyProposed = proposalRepo.findByUnauthedCharacterIdIn(ids).stream()
                .map(AltLinkProposal::getUnauthedCharacterId)
                .collect(java.util.stream.Collectors.toSet());

        return unauthed.stream()
                .filter(candidate -> !alreadyProposed.contains(candidate.id()))
                .toList();
    }

    private boolean exceedsBudget(CharacterDtos.CorpStatsDto stats, long pairs) {
        if (pairs <= props.getMaxPairsPerCorporation()) {
            return false;
        }
        // Lieber eine ehrlich fehlende Corporation als eine Antwort, auf die
        // niemand wartet: der Endpunkt haengt an einem Seitenaufruf.
        log.warn("Alt-Erkennung fuer Corp {} uebersprungen: {} Paare ueberschreiten die Grenze {}.",
                stats.corpId(), pairs, props.getMaxPairsPerCorporation());
        return true;
    }

    // ==================================================================
    // Bewertung eines Paares
    // ==================================================================

    /**
     * Bewertet ein Paar und normiert ueber die verfuegbaren Signale.
     *
     * <p>Die Gegenseite ist absichtlich eine <em>Liste von IDs</em> und kein
     * Konto: derselbe Rechenweg traegt "unregistrierter Charakter gegen Konto"
     * (dann sind es alle Charaktere des Kontos) und "unregistrierter Charakter
     * gegen unregistrierten Charakter" (dann ist es genau einer). Zwei getrennte
     * Rechenwege wuerden frueher oder spaeter auseinanderlaufen, und dann
     * bedeutete dieselbe Zahl in zwei Listen zweierlei.</p>
     *
     * @return {@code null}, wenn zu wenige Signale Daten hatten - dann gibt es
     *     keine Aussage, und eine niedrige Zahl waere eine erfundene
     */
    private ScoredPair scorePair(String linkerName, Long linkeId,
                                 String rechterName, Collection<Long> rechteIds,
                                 Map<Long, Instant> joinDates,
                                 MiningIndex mining,
                                 String gegenseite) {
        Map<String, SignalValue> values = new LinkedHashMap<>();
        values.put(SIGNAL_NAME, nameSignal(linkerName, rechterName));
        values.put(SIGNAL_JOIN, joinSignal(linkeId, rechteIds, joinDates, gegenseite));
        values.put(SIGNAL_MINING, miningSignal(linkeId, rechteIds, mining, gegenseite));

        int weightSum = 0;
        int weighted = 0;
        int used = 0;
        for (Map.Entry<String, SignalValue> entry : values.entrySet()) {
            if (!entry.getValue().available()) {
                continue;
            }
            int weight = weightOf(entry.getKey());
            weightSum += weight;
            weighted += weight * entry.getValue().score();
            used++;
        }

        // Genau hier steht die entscheidende Regel: geteilt wird durch die Summe
        // der TATSAECHLICH verfuegbaren Gewichte. Stuende hier die Summe ALLER
        // Gewichte, wuerde jedes fehlende Signal den Score druecken - und aus
        // "unbekannt" wuerde stillschweigend "geprueft und unauffaellig".
        if (used < props.getMinAvailableSignals() || weightSum == 0) {
            return null;
        }
        int probability = Math.clamp(Math.round((float) weighted / weightSum), 0, 100);
        return new ScoredPair(probability, used, toSignalDtos(values));
    }

    private int weightOf(String signal) {
        return switch (signal) {
            case SIGNAL_NAME -> props.getWeightName();
            case SIGNAL_JOIN -> props.getWeightJoin();
            case SIGNAL_MINING -> props.getWeightMining();
            default -> 0;
        };
    }

    private static String labelOf(String signal) {
        return switch (signal) {
            case SIGNAL_NAME -> "Namensaehnlichkeit";
            case SIGNAL_JOIN -> "Beitritts-Cluster";
            case SIGNAL_MINING -> "Mining-Aktivitaet";
            default -> signal;
        };
    }

    private List<CharacterDtos.AltSignalDto> toSignalDtos(Map<String, SignalValue> values) {
        return values.entrySet().stream()
                .map(entry -> new CharacterDtos.AltSignalDto(
                        entry.getKey(), labelOf(entry.getKey()),
                        entry.getValue().available(),
                        entry.getValue().available() ? entry.getValue().score() : null,
                        weightOf(entry.getKey()),
                        entry.getValue().detail()))
                .toList();
    }

    // ==================================================================
    // Signal 1: Name
    // ==================================================================

    /**
     * Namensaehnlichkeit - das einzige Signal, das immer Daten hat, weil beide
     * Namen ohnehin vorliegen.
     *
     * <p>Deshalb ist es auch das einzige, das praktisch nie fehlt - und genau
     * deshalb darf man aus einem Vorschlag mit nur diesem einen Signal nicht
     * mehr herauslesen, als dort steht. Der Vergleich selbst samt seiner
     * gemessenen Schwaechen ist in {@link NameSimilarity} beschrieben.</p>
     */
    private SignalValue nameSignal(String linkerName, String rechterName) {
        int score = names.score(linkerName, rechterName);
        return SignalValue.of(score, "Levenshtein und EVE-Namensmuster gegen \"%s\""
                .formatted(rechterName));
    }

    // ==================================================================
    // Signal 2: Beitritts-Cluster
    // ==================================================================

    /**
     * Die Beitrittsdaten der ganzen Corporation aus der Mitgliederverfolgung.
     *
     * <p><b>Sie deckt beide Seiten ab.</b> Die Mitgliederverfolgung kommt mit dem
     * Token eines Directors und listet <em>jedes</em> Corp-Mitglied, registriert
     * oder nicht. Genau deshalb kann die Gruppierung zweier <em>unregistrierter</em>
     * Charaktere untereinander ueberhaupt ein zweites Signal haben - beim Mining
     * waere die Antwort fuer beide Seiten strukturell leer.</p>
     *
     * @return eine leere Karte, wenn kein Director-Token traegt. Der Aufrufer
     *     macht daraus "Signal nicht verfuegbar" - und ausdruecklich nicht
     *     "Signal mit Wert 0". Der Unterschied entscheidet darueber, ob eine
     *     Corporation ohne angemeldeten Director gar keine Vorschlaege bekommt
     *     (richtig) oder lauter niedrige (falsch und irrefuehrend).
     */
    private Map<Long, Instant> joinDates(Long corporationId,
                                         Map<Long, Map<Long, Instant>> cache) {
        // Ueber einen Aufruf hinweg gemerkt und nicht darueber hinaus: ein
        // laenger lebender Cache muesste entscheiden, wann ein Beitrittsdatum
        // veraltet ist - und das ist die Frage, die der ETag-Cache von ESI
        // bereits beantwortet.
        return cache.computeIfAbsent(corporationId, this::joinDates);
    }

    private Map<Long, Instant> joinDates(Long corporationId) {
        try {
            var attempt = directorTokenProvider.attempt(corporationId, TRACK_MEMBERS_SCOPE,
                    token -> esiService.getCorporationMemberTracking(corporationId, token));

            EsiResponse<EsiService.EsiMemberTrackingResponse[]> response = attempt.value();
            if (!attempt.succeeded() || response == null || response.data() == null) {
                log.info("Beitrittsdaten fuer Corp {} nicht abrufbar - das Signal entfaellt, "
                        + "es wird NICHT als 0 gewertet.", corporationId);
                return Map.of();
            }

            Map<Long, Instant> dates = new HashMap<>();
            for (EsiService.EsiMemberTrackingResponse entry : response.data()) {
                if (entry != null && entry.character_id() != null && entry.start_date() != null) {
                    dates.put(entry.character_id(), entry.start_date());
                }
            }
            return dates;

        } catch (Exception e) {
            log.warn("Mitgliederverfolgung der Corp {} fehlgeschlagen: {}", corporationId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Wie nah beieinander der Verdaechtige und die Gegenseite beigetreten sind.
     *
     * <p>Verglichen wird gegen den <em>naechstliegenden</em> Charakter der
     * Gegenseite und nicht nur gegen deren Main: ein Spieler holt seine Alts oft
     * in einem Rutsch herein, lange nachdem sein Main schon drin war.</p>
     *
     * <p><b>Gegen die Rekrutierungswelle:</b> traten im selben engen Fenster
     * viele Mitglieder bei, wird der Wert an der Clustergroesse gedaempft. Ohne
     * diese Daempfung waere das Signal derselbe Fehler wie der rohe Mining-Tag -
     * ein Gruppenereignis, das man fuer einen Fingerabdruck haelt. Bei der
     * Gruppierung unregistrierter untereinander ist das die entscheidende
     * Bremse: dort ist der Beitritt das einzige zweite Signal, und ohne
     * Daempfung wuerde eine Rekrutierungswelle jede Namensaehnlichkeit ueber die
     * Schwelle heben.</p>
     */
    private SignalValue joinSignal(Long linkeId, Collection<Long> rechteIds,
                                   Map<Long, Instant> joinDates, String gegenseite) {
        if (joinDates.isEmpty()) {
            return SignalValue.missing("Kein Director-Token: Mitgliederverfolgung nicht abrufbar.");
        }
        Instant candidateJoin = joinDates.get(linkeId);
        if (candidateJoin == null) {
            return SignalValue.missing("ESI nennt fuer diesen Charakter kein Beitrittsdatum.");
        }

        Duration closest = null;
        for (Long other : rechteIds) {
            Instant memberJoin = joinDates.get(other);
            if (memberJoin == null) {
                continue;
            }
            Duration distance = Duration.between(candidateJoin, memberJoin).abs();
            if (closest == null || distance.compareTo(closest) < 0) {
                closest = distance;
            }
        }
        if (closest == null) {
            return SignalValue.missing(
                    "Fuer %s nennt ESI kein Beitrittsdatum.".formatted(gegenseite));
        }

        int base = distanceScore(closest);
        int cluster = clusterSize(candidateJoin, joinDates);
        int score = dilute(base, cluster);

        return SignalValue.of(score, "Beitritt %s auseinander; %d Mitglieder im selben Fenster."
                .formatted(humanize(closest), cluster));
    }

    /** Voller Wert im engen Fenster, danach linearer Abfall bis auf null. */
    private int distanceScore(Duration distance) {
        if (distance.compareTo(props.getJoinFullWindow()) <= 0) {
            return 100;
        }
        if (distance.compareTo(props.getJoinZeroWindow()) >= 0) {
            // Ein gemessener Wert 0: "die beiden sind weit auseinander beigetreten".
            // Das ist eine Aussage und kein fehlendes Signal - deshalb bleibt es
            // verfuegbar und faellt nicht aus der Normierung heraus.
            return 0;
        }
        double span = props.getJoinZeroWindow().toSeconds()
                - props.getJoinFullWindow().toSeconds();
        double over = distance.toSeconds() - props.getJoinFullWindow().toSeconds();
        return (int) Math.round(100.0 * (1.0 - over / span));
    }

    /** Wieviele Mitglieder im engen Fenster um diesen Beitritt herum beitraten. */
    private int clusterSize(Instant candidateJoin, Map<Long, Instant> joinDates) {
        int count = 0;
        for (Instant other : joinDates.values()) {
            if (Duration.between(candidateJoin, other).abs()
                    .compareTo(props.getJoinFullWindow()) <= 0) {
                count++;
            }
        }
        return count;
    }

    private int dilute(int score, int clusterSize) {
        if (!props.isJoinClusterDilution() || clusterSize < props.getJoinClusterMinSize()) {
            return score;
        }
        // Zwei gemeinsam Beigetretene sind genau der gesuchte Fall und duerfen
        // sich nicht selbst daempfen - deshalb der Zaehler 2.
        return (int) Math.round(score * Math.min(1.0, 2.0 / clusterSize));
    }

    private static String humanize(Duration distance) {
        if (distance.toHours() < 1) {
            return distance.toMinutes() + " Minuten";
        }
        if (distance.toDays() < 1) {
            return distance.toHours() + " Stunden";
        }
        return distance.toDays() + " Tage";
    }

    // ==================================================================
    // Signal 3: Mining
    // ==================================================================

    /**
     * Die Mining-Tage je Charakter und die Anzahl Miner je Tag.
     *
     * @param daysByCharacter Tage je Charakter, als Zeichenkette {@code YYYY-MM-DD}
     * @param minersPerDay    wieviele Charaktere an diesem Tag ueberhaupt abbauten
     */
    record MiningIndex(Map<Long, Set<String>> daysByCharacter, Map<String, Integer> minersPerDay) {}

    /**
     * Baut den Mining-Index fuer die Kandidaten und die Konten dieser Corporation.
     *
     * <p><b>Was die Daten nicht hergeben.</b> {@code character_mining} speichert
     * {@code character_id}, {@code mining_date}, {@code type_id} und
     * {@code quantity} - <em>kein</em> Sonnensystem und <em>keine</em> Uhrzeit.
     * Das System liefert ESI zwar mit, es wird aber nirgends gespeichert; eine
     * Uhrzeit gibt es in CCPs Mining-Ledger ueberhaupt nicht, es ist
     * tagesaggregiert. Die im Auftrag gewuenschte "Korrelation aus Zeit und
     * System" ist mit diesem Bestand also nicht bloss schwer, sondern
     * unmoeglich. Uebrig bleibt der Tag.</p>
     *
     * <p><b>Und der Tag allein taugt nicht.</b> Nachgemessen auf der einzigen
     * bekannten Wahrheit: die rohe Tagesueberschneidung (Jaccard) trennt
     * <em>invertiert</em> - fremde Paare lagen im Mittel bei 0,77, echte bei
     * 0,27. Der Grund ist erklaerbar und nicht wegzurechnen: in einer Corp minen
     * alle an denselben Tagen, der Tag ist ein Gruppenereignis. Deshalb zaehlt
     * hier nicht die Ueberschneidung selbst, sondern die <em>Seltenheit</em>
     * jedes gemeinsamen Tages - siehe
     * {@link AltDetectionProperties#getMiningRarityExponent()}.</p>
     *
     * <p><b>Und heute laeuft es trotzdem leer.</b> Fuer nicht registrierte
     * Charaktere gibt es null Mining-Zeilen, weil der Sync ihr eigenes Token
     * braucht. Das Signal meldet deshalb praktisch immer "nicht verfuegbar" -
     * und das ist die richtige Antwort, nicht "0". Daten bekaeme es erst ueber
     * die Corp-Mining-Beobachter, die auch fremde Miner an einer Corp-Struktur
     * auflisten. Fuer die Gruppierung zweier Unregistrierter wird der Index
     * trotzdem gebaut und nicht kurzgeschlossen: sobald diese Zeilen kommen,
     * traegt das Signal dort sofort mit, ohne dass jemand daran denken muss.</p>
     */
    private MiningIndex miningIndex(List<CharacterDtos.UnauthedCharDto> unauthed,
                                    Map<Long, List<Character>> byAccount) {
        List<Long> ids = new ArrayList<>(unauthed.stream()
                .map(CharacterDtos.UnauthedCharDto::id).toList());
        byAccount.values().forEach(members -> members.forEach(member -> ids.add(member.getId())));
        byAccount.keySet().forEach(ids::add);

        List<CharacterMining> rows = miningRepo.findByCharacterIdIn(ids.stream().distinct().toList());

        Map<Long, Set<String>> daysByCharacter = new HashMap<>();
        Map<String, Set<Long>> charactersPerDay = new HashMap<>();
        for (CharacterMining row : rows) {
            if (row.getDate() == null || row.getCharacterId() == null) {
                continue;
            }
            daysByCharacter.computeIfAbsent(row.getCharacterId(), id -> new HashSet<>())
                    .add(row.getDate());
            charactersPerDay.computeIfAbsent(row.getDate(), day -> new HashSet<>())
                    .add(row.getCharacterId());
        }

        Map<String, Integer> minersPerDay = new HashMap<>();
        charactersPerDay.forEach((day, miners) -> minersPerDay.put(day, miners.size()));
        return new MiningIndex(daysByCharacter, minersPerDay);
    }

    private SignalValue miningSignal(Long linkeId, Collection<Long> rechteIds,
                                     MiningIndex index, String gegenseite) {
        Set<String> candidateDays = index.daysByCharacter().getOrDefault(linkeId, Set.of());
        if (candidateDays.isEmpty()) {
            return SignalValue.missing(
                    "Keine Mining-Zeilen fuer diesen Charakter - nicht registriert, also kein "
                    + "eigenes Token und damit kein Ledger. Nicht gemessen, nicht null.");
        }

        Set<String> accountDays = new LinkedHashSet<>();
        for (Long member : rechteIds) {
            accountDays.addAll(index.daysByCharacter().getOrDefault(member, Set.of()));
        }
        if (accountDays.isEmpty()) {
            return SignalValue.missing(
                    "Fuer %s liegen keine Mining-Zeilen vor.".formatted(gegenseite));
        }

        Set<String> shared = new LinkedHashSet<>(candidateDays);
        shared.retainAll(accountDays);
        if (shared.size() < props.getMiningMinSharedDays()) {
            return SignalValue.missing(
                    "Nur %d gemeinsame Mining-Tage - zu wenig fuer eine Aussage (mindestens %d)."
                            .formatted(shared.size(), props.getMiningMinSharedDays()));
        }

        Set<String> union = new LinkedHashSet<>(candidateDays);
        union.addAll(accountDays);

        double sharedWeight = shared.stream().mapToDouble(day -> rarity(day, index)).sum();
        double unionWeight = union.stream().mapToDouble(day -> rarity(day, index)).sum();
        if (unionWeight <= 0) {
            return SignalValue.missing("Mining-Tage ohne auswertbare Seltenheit.");
        }

        int score = (int) Math.round(100.0 * sharedWeight / unionWeight);
        return SignalValue.of(score, ("%d von %d Mining-Tagen gemeinsam, nach Seltenheit des Tages "
                + "gewichtet. Ohne Uhrzeit und ohne System - beides liefert der Bestand nicht.")
                .formatted(shared.size(), union.size()));
    }

    /** Ein Tag, an dem viele abbauten, sagt ueber ein einzelnes Paar wenig. */
    private double rarity(String day, MiningIndex index) {
        int miners = Math.max(1, index.minersPerDay().getOrDefault(day, 1));
        return Math.pow(1.0 / miners, props.getMiningRarityExponent());
    }

    // ==================================================================
    // Gruppen unregistrierter Charaktere untereinander
    // ==================================================================

    /**
     * Gruppen nicht registrierter Charaktere, die vermutlich <em>ein</em> Mensch
     * sind - ohne dass ein bekanntes Konto dazugehoert.
     *
     * <h2>Warum es das gibt</h2>
     * <p>Die Vorschlagsliste vergleicht ausschliesslich unregistriert gegen
     * registriert. In einer Corp mit 273 unregistrierten Mitgliedern und 11
     * bekannten Konten ist das der kleinere Teil der Wirklichkeit: die meisten
     * Alt-Verbuende bestehen aus lauter Charakteren, von denen sich keiner je
     * hier angemeldet hat. Fuer die faellt heute nichts an - und deshalb ist die
     * Liste in der Praxis fast immer leer.</p>
     *
     * <h2>Was hier NICHT passiert</h2>
     * <p>Eine Gruppe ohne registrierten Main ist eine <b>Beobachtung</b>. Es gibt
     * kein Konto, dem man sie zuordnen koennte, also gibt es hier auch keine
     * Vormerkung, keinen Bestaetigungsknopf und keinen Schreibzugriff. Was ein
     * Director damit sinnvoll tut, passiert ausserhalb dieses Programms: er
     * fragt die Leute an oder bittet sie, sich anzumelden. Ein Knopf, der nichts
     * bewirkt, waere schlimmer als keiner - er suggeriert, dass die Software die
     * Sache erledigt hat.</p>
     *
     * <h2>Die Datenlage dieser Paare</h2>
     * <p>Beide Seiten sind unregistriert. Das Mining-Signal ist damit auf
     * <em>beiden</em> Seiten leer und meldet sich als nicht verfuegbar (es wird
     * trotzdem gerechnet, damit es mittraegt, sobald die Corp-Mining-Beobachter
     * Zeilen liefern). Der Beitritt dagegen liegt fuer beide Seiten vor: die
     * Mitgliederverfolgung kommt mit dem Director-Token und deckt die ganze
     * Corporation ab. Eine Gruppe kann hier also durchaus zwei Signale tragen -
     * mehr als der uebliche Vorschlag gegen ein Konto, bei dem der Kandidat
     * regelmaessig nur seinen Namen mitbringt.</p>
     *
     * @throws AccessDeniedException wenn der Handelnde nicht zur Fuehrung gehoert -
     *     das sind zusammengetragene Daten ueber Menschen, auch wenn sie nur
     *     angezeigt werden
     */
    @Transactional(readOnly = true)
    public List<CharacterDtos.AltGroupDto> findUnregisteredGroups(Long actorId) {
        requireLeadership(actorId);
        if (!props.isGroupUnregistered()) {
            return List.of();
        }
        List<CharacterDtos.AltGroupDto> groups = new ArrayList<>();
        for (CharacterDtos.CorpStatsDto stats : corporationStatsService.statsForAllCorporations()) {
            groups.addAll(groupsFor(stats));
        }
        return groups.stream()
                .sorted(Comparator.comparingInt(CharacterDtos.AltGroupDto::probability).reversed()
                        .thenComparing(group -> group.members().getFirst().name(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Von paarweiser Aehnlichkeit zu Gruppen - und warum die transitive Huelle
     * allein falsch waere.
     *
     * <h2>Der Fehler, den union-find allein macht</h2>
     * <p>Der naheliegende Weg ist: jede Kante ueber der Schwelle vereinigt zwei
     * Charaktere, und die Zusammenhangskomponente ist die Gruppe. Das ist
     * <b>falsch</b>, und zwar aus einem Grund, der bei EVE-Namen der Regelfall
     * ist: Aehnlichkeit ist nicht transitiv. "A Video" ist "B Video" aehnlich,
     * "B Video" ist "C Video" aehnlich - aber "A Video" und "C Video" sind es
     * ueber den blossen Nachnamen genauso wenig wie ueber sonst etwas. Die
     * Huelle verschmilzt trotzdem alle drei zu <em>einem Menschen</em>. Und weil
     * jede weitere Kante die Komponente nur vergroessert, endet ein haeufiger
     * Nachname in einer Gruppe, die halbe Corp umfasst.</p>
     *
     * <h2>Die Loesung: erst Komponente, dann Clique</h2>
     * <p>Union-find bleibt drin, aber nur als <em>Vorsortierung</em>: es
     * beantwortet billig, welche Charaktere ueberhaupt zusammen betrachtet
     * werden muessen. Die Gruppe selbst entsteht danach durch
     * <b>vollstaendige Verkettung</b> - ein Charakter kommt nur hinzu, wenn er
     * zu <em>jedem</em> bereits aufgenommenen Mitglied eine Kante hat, nicht
     * bloss zu einem. Aus der Kette A-B-C ohne Kante A-C wird damit die Gruppe
     * {A, B}, und C bleibt draussen, statt einen dritten Fremden in dieselbe
     * Person zu verwandeln.</p>
     *
     * <h2>Die drei Bremsen darueber</h2>
     * <ol>
     *   <li>Eine Kante entsteht ueberhaupt erst ueber
     *       {@link #schwelleFuer(int)} - traegt nur der Name, gilt die hoehere
     *       Einzelsignal-Schwelle. Ein blosser gemeinsamer Nachname (85) bleibt
     *       damit unter der Grenze (90) und ist <b>keine</b> Kante; ein
     *       durchnummerierter Zwilling (95) ist eine.</li>
     *   <li>Traegt zusaetzlich der Beitritt, greift die Cluster-Daempfung: drei
     *       Namensvettern, die in derselben Rekrutierungswelle aufgenommen
     *       wurden, verlieren genau dadurch ihre Kanten wieder.</li>
     *   <li>{@link AltDetectionProperties#getGroupMaxMembers()} verwirft eine
     *       Gruppe, die zu gross wird, ganz - dann ist der geteilte Name eine
     *       Konvention und kein Mensch.</li>
     * </ol>
     *
     * <p>Der Wert der Gruppe ist der <em>kleinste</em> Paarwert darin, nicht der
     * Mittelwert. Eine Gruppe ist so belastbar wie ihre schwaechste Verbindung;
     * ein Mittelwert wuerde ein starkes Paar ein schwaches drittes Mitglied
     * mittragen lassen.</p>
     */
    private List<CharacterDtos.AltGroupDto> groupsFor(CharacterDtos.CorpStatsDto stats) {
        List<CharacterDtos.UnauthedCharDto> unauthed = openCandidates(stats);
        int n = unauthed.size();
        if (n < Math.max(2, props.getGroupMinMembers())) {
            return List.of();
        }
        // Das Kreuzprodukt mit sich selbst, ohne Diagonale und ohne Spiegelung.
        if (exceedsBudget(stats, (long) n * (n - 1) / 2)) {
            return List.of();
        }

        Map<Long, Instant> joinDates = joinDates(stats.corpId(), new HashMap<>());
        MiningIndex mining = miningIndex(unauthed, Map.of());

        // Alle Kanten oberhalb der Schwelle, Schluessel ist das Indexpaar.
        Map<Long, ScoredPair> edges = new HashMap<>();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                CharacterDtos.UnauthedCharDto links = unauthed.get(i);
                CharacterDtos.UnauthedCharDto rechts = unauthed.get(j);
                ScoredPair scored = scorePair(links.name(), links.id(), rechts.name(),
                        List.of(rechts.id()), joinDates, mining,
                        "\"%s\"".formatted(rechts.name()));
                if (scored == null || scored.probability() < schwelleFuer(scored.signalsUsed())) {
                    continue;
                }
                edges.put(edgeKey(i, j), scored);
                union(parent, i, j);
            }
        }
        if (edges.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (hasAnyEdge(edges, i, n)) {
                components.computeIfAbsent(find(parent, i), key -> new ArrayList<>()).add(i);
            }
        }

        List<CharacterDtos.AltGroupDto> result = new ArrayList<>();
        for (List<Integer> component : components.values()) {
            for (List<Integer> clique : cliques(component, edges)) {
                CharacterDtos.AltGroupDto group = toGroup(stats, unauthed, clique, edges);
                if (group != null) {
                    result.add(group);
                }
            }
        }
        return result;
    }

    /**
     * Zerlegt eine Zusammenhangskomponente in vollstaendig verbundene Gruppen.
     *
     * <p>Gierig und nicht optimal: das exakte Problem (alle maximalen Cliquen)
     * ist NP-schwer, und der Gewinn waere hier keiner - es geht um eine Liste
     * zum Nachschauen, nicht um eine Optimierung. Startpunkt ist jeweils der
     * Charakter mit den meisten Kanten; das ist in der Praxis der Mittelpunkt
     * eines echten Verbunds.</p>
     */
    private List<List<Integer>> cliques(List<Integer> component, Map<Long, ScoredPair> edges) {
        List<Integer> remaining = new ArrayList<>(component);
        remaining.sort(Comparator
                .comparingInt((Integer index) -> degree(edges, index, component)).reversed()
                .thenComparingInt(index -> index));

        List<List<Integer>> result = new ArrayList<>();
        while (remaining.size() >= 2) {
            List<Integer> group = new ArrayList<>();
            group.add(remaining.removeFirst());

            var iterator = remaining.iterator();
            while (iterator.hasNext()) {
                Integer candidate = iterator.next();
                // Die vollstaendige Verkettung: zu JEDEM Mitglied eine Kante,
                // nicht bloss zu einem. Stuende hier "irgendeinem", waere das
                // wieder die transitive Huelle - und "A Video ~ B Video ~
                // C Video" waere ein einziger Mensch.
                boolean toAll = group.stream()
                        .allMatch(member -> edges.containsKey(edgeKey(member, candidate)));
                if (toAll) {
                    group.add(candidate);
                    iterator.remove();
                }
            }
            if (group.size() >= Math.max(2, props.getGroupMinMembers())) {
                result.add(group);
            }
        }
        return result;
    }

    private CharacterDtos.AltGroupDto toGroup(CharacterDtos.CorpStatsDto stats,
                                              List<CharacterDtos.UnauthedCharDto> unauthed,
                                              List<Integer> clique,
                                              Map<Long, ScoredPair> edges) {
        if (clique.size() > props.getGroupMaxMembers()) {
            // Ein Namensschema, das so viele Menschen umfasst, ist eine
            // Konvention und kein Mensch. Lieber gar nichts melden als eine
            // Gruppe, in der die echten Faelle untergehen.
            log.info("Gruppe aus {} unregistrierten Charakteren in Corp {} verworfen: "
                            + "ueber der Obergrenze von {}.",
                    clique.size(), stats.corpId(), props.getGroupMaxMembers());
            return null;
        }

        ScoredPair weakest = null;
        for (int a = 0; a < clique.size(); a++) {
            for (int b = a + 1; b < clique.size(); b++) {
                ScoredPair edge = edges.get(edgeKey(clique.get(a), clique.get(b)));
                if (edge != null && (weakest == null || edge.probability() < weakest.probability())) {
                    weakest = edge;
                }
            }
        }
        if (weakest == null) {
            return null;
        }

        List<CharacterDtos.UnauthedCharDto> members = clique.stream()
                .map(unauthed::get)
                .sorted(Comparator.comparing(CharacterDtos.UnauthedCharDto::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new CharacterDtos.AltGroupDto(
                stats.corpId(), members, weakest.probability(),
                weakest.signalsUsed(), SIGNAL_COUNT, weakest.signals(),
                ("Beobachtung ohne Konto: zu dieser Gruppe gehoert kein hier registrierter "
                 + "Charakter, es gibt also niemanden, dem sie zugeordnet werden koennte. "
                 + "Der Wert ist der der SCHWAECHSTEN Verbindung in der Gruppe - eine Gruppe "
                 + "ist nur so belastbar wie ihr duennstes Paar. Der naechste Schritt ist eine "
                 + "Nachfrage im Spiel, keine Schaltflaeche."));
    }

    /** Kantenschluessel fuer ein ungeordnetes Indexpaar. */
    private static long edgeKey(int left, int right) {
        int a = Math.min(left, right);
        int b = Math.max(left, right);
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    private static boolean hasAnyEdge(Map<Long, ScoredPair> edges, int index, int count) {
        for (int other = 0; other < count; other++) {
            if (other != index && edges.containsKey(edgeKey(index, other))) {
                return true;
            }
        }
        return false;
    }

    private static int degree(Map<Long, ScoredPair> edges, int index, List<Integer> component) {
        int count = 0;
        for (Integer other : component) {
            if (!other.equals(index) && edges.containsKey(edgeKey(index, other))) {
                count++;
            }
        }
        return count;
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int left, int right) {
        int a = find(parent, left);
        int b = find(parent, right);
        if (a != b) {
            parent[b] = a;
        }
    }

    // ==================================================================
    // Kalibrieransicht
    // ==================================================================

    /**
     * Die besten Paare <b>unabhaengig von der Schwelle</b>, jeweils mit voller
     * Aufschluesselung.
     *
     * <h2>Wozu</h2>
     * <p>Eine leere Vorschlagsliste hat zwei ununterscheidbare Ursachen: der
     * Scorer findet nichts, oder der Scorer laeuft nicht. Solange man nur die
     * gefilterte Liste sieht, ist beides derselbe Anblick - und die Schwelle
     * laesst sich nur raten. Diese Ansicht zeigt, was <em>knapp darunter</em>
     * liegt, samt jedem Einzelwert und jedem fehlenden Signal. Erst daran laesst
     * sich eine Schwelle setzen, statt sie zu setzen und zu hoffen.</p>
     *
     * <h2>Was sie ausdruecklich nicht tut</h2>
     * <p>Sie bestaetigt nichts. Keine Zuordnung, keine Vormerkung, kein
     * Schreibzugriff - der Handlungsweg bleibt allein
     * {@link #confirmAltSuggestion(Long, Long, Long)}, und der prueft weiterhin
     * gegen die Schwelle. Waeren beide Wege einer, koennte man ueber die
     * Kalibrierung genau die Vorsicht aushebeln, die die Schwelle darstellt.</p>
     *
     * <p>{@code limit} ist nach oben hart begrenzt
     * ({@link AltDetectionProperties#getCalibrationMaxLimit()}). Ohne diese
     * Grenze waere die Ansicht ein Vollabzug der Namens-Kreuztabelle ueber
     * mehrere hundert Menschen - sie soll zeigen, <em>wie</em> gerechnet wird,
     * und nicht alles ausliefern, <em>was</em> gerechnet wurde.</p>
     *
     * @param limit gewuenschte Zeilenzahl je Liste, {@code null} nimmt die Vorgabe
     * @throws AccessDeniedException wenn der Handelnde nicht zur Fuehrung gehoert.
     *     Die Pruefung steht hier im Dienst und nicht nur am Controller: es sind
     *     zusammengetragene Daten ueber Menschen, auch wenn sie nur angezeigt
     *     werden.
     */
    @Transactional(readOnly = true)
    public CharacterDtos.AltCalibrationDto calibrationSample(Long actorId, Integer limit) {
        requireLeadership(actorId);
        int effektiv = Math.clamp(
                limit == null ? props.getCalibrationDefaultLimit() : limit,
                1, props.getCalibrationMaxLimit());

        List<CharacterDtos.AltSuggestionDto> kontoPaare = new ArrayList<>();
        List<CharacterDtos.AltPairDto> unregistriertePaare = new ArrayList<>();
        Map<Long, Map<Long, Instant>> joinCache = new HashMap<>();
        for (CharacterDtos.CorpStatsDto stats : corporationStatsService.statsForAllCorporations()) {
            kontoPaare.addAll(allAccountPairs(stats, joinCache));
            unregistriertePaare.addAll(allUnregisteredPairs(stats, joinCache));
        }

        List<CharacterDtos.AltCalibrationEntryDto> konto = kontoPaare.stream()
                .sorted(Comparator.comparingInt(CharacterDtos.AltSuggestionDto::probability).reversed()
                        .thenComparing(CharacterDtos.AltSuggestionDto::unauthedCharName,
                                String.CASE_INSENSITIVE_ORDER))
                .limit(effektiv)
                .map(pair -> new CharacterDtos.AltCalibrationEntryDto(
                        pair, schwelleFuer(pair.signalsUsed()),
                        pair.probability() >= schwelleFuer(pair.signalsUsed())))
                .toList();

        List<CharacterDtos.AltPairDto> unregistriert = unregistriertePaare.stream()
                .sorted(Comparator.comparingInt(CharacterDtos.AltPairDto::probability).reversed()
                        .thenComparing(CharacterDtos.AltPairDto::leftName,
                                String.CASE_INSENSITIVE_ORDER))
                .limit(effektiv)
                .toList();

        log.info("Kalibrieransicht der Alt-Erkennung von {} abgerufen: {} Kontopaare und {} "
                        + "unregistrierte Paare gerechnet, je hoechstens {} geliefert. "
                        + "Es wurde nichts bestaetigt.",
                actorId, kontoPaare.size(), unregistriertePaare.size(), effektiv);

        return new CharacterDtos.AltCalibrationDto(
                effektiv, props.getCalibrationMaxLimit(),
                kontoPaare.size(), unregistriertePaare.size(),
                props.getMinProbability(), props.getMinProbabilitySingleSignal(),
                props.getMinAvailableSignals(),
                konto, unregistriert);
    }

    /**
     * Alle bewerteten Paare zweier unregistrierter Charaktere dieser
     * Corporation, ohne Schwelle.
     *
     * <p>Nur fuer die Kalibrierung. Die Gruppenansicht braucht davon lediglich
     * die Kanten oberhalb der Schwelle und rechnet sie deshalb selbst - sie
     * braucht zusaetzlich das Indexpaar, um daraus Gruppen zu bauen.</p>
     */
    private List<CharacterDtos.AltPairDto> allUnregisteredPairs(
            CharacterDtos.CorpStatsDto stats, Map<Long, Map<Long, Instant>> joinCache) {
        List<CharacterDtos.UnauthedCharDto> unauthed = openCandidates(stats);
        int n = unauthed.size();
        if (n < 2 || exceedsBudget(stats, (long) n * (n - 1) / 2)) {
            return List.of();
        }

        Map<Long, Instant> joinDates = joinDates(stats.corpId(), joinCache);
        MiningIndex mining = miningIndex(unauthed, Map.of());

        List<CharacterDtos.AltPairDto> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                CharacterDtos.UnauthedCharDto links = unauthed.get(i);
                CharacterDtos.UnauthedCharDto rechts = unauthed.get(j);
                ScoredPair scored = scorePair(links.name(), links.id(), rechts.name(),
                        List.of(rechts.id()), joinDates, mining,
                        "\"%s\"".formatted(rechts.name()));
                if (scored == null) {
                    continue;
                }
                int schwelle = schwelleFuer(scored.signalsUsed());
                result.add(new CharacterDtos.AltPairDto(
                        links.id(), links.name(), rechts.id(), rechts.name(), stats.corpId(),
                        scored.probability(), scored.signalsUsed(), SIGNAL_COUNT,
                        scored.signals(), schwelle, scored.probability() >= schwelle));
            }
        }
        return result;
    }

    // ==================================================================
    // Bestaetigung
    // ==================================================================

    /**
     * Haelt fest, dass die Fuehrung den Verdacht fuer richtig haelt.
     *
     * <p><b>Diese Methode schreibt nichts nach {@code characters}.</b> Sie legt
     * eine {@link AltLinkProposal} an - eine Vormerkung samt Nachweis. Die
     * eigentliche Zuordnung entsteht weiterhin nur auf dem bestehenden Weg: der
     * Main meldet sich an, waehlt "Alt hinzufuegen" und der Charakter loggt sich
     * per EVE SSO selbst ein. Damit bleibt der Eigentumsnachweis dort, wo CCP
     * ihn fuehrt, und es gibt keinen zweiten, ungeprueft schreibenden Pfad in
     * dieselbe Spalte.</p>
     *
     * <p>Die Reihenfolge der Pruefungen ist Absicht: erst die Berechtigung, dann
     * die beiden Faelle "das gibt es schon", zuletzt der teure Neuabgleich.</p>
     *
     * @throws AccessDeniedException    wenn der Handelnde nicht zur Fuehrung gehoert
     * @throws IllegalArgumentException wenn Charakter oder Konto unbekannt sind
     * @throws IllegalStateException    wenn der Charakter bereits einem Konto
     *     zugeordnet ist oder bereits eine Vormerkung traegt - beides wird
     *     ausdruecklich NICHT stillschweigend ueberschrieben
     */
    @Transactional
    public CharacterDtos.AltLinkResultDto confirmAltSuggestion(Long actorId,
                                                               Long unauthedCharId,
                                                               Long mainId) {
        Character actor = requireLeadership(actorId);
        if (unauthedCharId == null || mainId == null) {
            throw new IllegalArgumentException("Charakter und Konto muessen angegeben sein.");
        }

        // Ein bereits registrierter Charakter haengt schon an einem Konto - und
        // dieses Konto zu ueberschreiben waere genau der Eingriff, den es hier
        // nicht geben darf. Er ist auch gar nicht "unregistriert".
        characterRepo.findById(unauthedCharId).ifPresent(existing -> {
            throw new IllegalStateException(
                    ("%s ist bereits registriert und gehoert zu Konto %d. Eine bestehende "
                     + "Zuordnung wird hier nicht ueberschrieben.")
                            .formatted(existing.getName(), existing.getAccountId()));
        });

        proposalRepo.findByUnauthedCharacterId(unauthedCharId).ifPresent(existing -> {
            throw new IllegalStateException(
                    ("Fuer diesen Charakter besteht bereits eine Vormerkung auf Konto %d, "
                     + "bestaetigt am %s. Sie wird nicht stillschweigend ersetzt.")
                            .formatted(existing.getMainCharacterId(), existing.getDecidedAt()));
        });

        Character main = characterRepo.findById(mainId).orElseThrow(
                () -> new IllegalArgumentException("Konto " + mainId + " ist unbekannt."));
        if (!main.isMain()) {
            throw new IllegalArgumentException(
                    "%s ist selbst ein Alt. Vorgemerkt wird auf den Main des Kontos (%d)."
                            .formatted(main.getName(), main.getAccountId()));
        }

        // Der Vorschlag wird NEU gerechnet und nicht aus der Anfrage geglaubt.
        // Sonst koennte ein Director ein beliebiges Paar bestaetigen, das der
        // Erkennung nie eingefallen ist - und der Nachweis truege eine
        // Wahrscheinlichkeit, die niemand je errechnet hat. Ausdruecklich gegen
        // findProbableAlts und NICHT gegen die Kalibrieransicht: die kennt keine
        // Schwelle, und ueber sie liesse sich genau diese Vorsicht aushebeln.
        CharacterDtos.AltSuggestionDto suggestion = findProbableAlts().stream()
                .filter(entry -> entry.unauthedCharId().equals(unauthedCharId)
                        && entry.mainId().equals(mainId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fuer dieses Paar gibt es derzeit keinen Vorschlag ueber der Schwelle von "
                        + props.getMinProbability() + "."));

        AltLinkProposal proposal = new AltLinkProposal();
        proposal.setUnauthedCharacterId(unauthedCharId);
        proposal.setUnauthedCharacterName(suggestion.unauthedCharName());
        proposal.setMainCharacterId(mainId);
        proposal.setCorporationId(suggestion.corpId());
        proposal.setProbability(suggestion.probability());
        proposal.setSignalSummary(summarize(suggestion));
        proposal.setActorCharacterId(actor.getId());
        proposal.setSelfAssigned(actor.getAccountId().equals(mainId));
        proposal.setDecidedAt(Instant.now());
        proposalRepo.save(proposal);

        // Auf WARN und nicht auf DEBUG: das ist der folgenreichste Klick dieser
        // Oberflaeche, und wer ihn spaeter sucht, sucht ihn im Log.
        log.warn("Alt-Vormerkung: {} ({}) ordnet {} ({}) dem Konto {} ({}) zu. "
                        + "Wahrscheinlichkeit {} aus {} von {} Signalen. Selbstzuordnung: {}.",
                actor.getName(), actor.getId(), suggestion.unauthedCharName(), unauthedCharId,
                main.getName(), mainId, suggestion.probability(),
                suggestion.signalsUsed(), suggestion.signalsTotal(), proposal.isSelfAssigned());

        return new CharacterDtos.AltLinkResultDto(
                unauthedCharId, suggestion.unauthedCharName(), mainId, main.getName(),
                suggestion.probability(), false,
                ("Vorgemerkt. %s ist damit noch NICHT dem Konto von %s zugeordnet: das geschieht "
                 + "erst, wenn %s sich unter \"Alt hinzufuegen\" selbst per EVE-Login anmeldet. "
                 + "Nur dieser Weg beweist, wem der Charakter gehoert.")
                        .formatted(suggestion.unauthedCharName(), main.getName(),
                                suggestion.unauthedCharName()));
    }

    /**
     * Die Rechtepruefung, in der Fachschicht statt nur am Controller.
     *
     * <p>Am Endpunkt steht zusaetzlich
     * {@code @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)}, und das bleibt auch
     * so. Die Annotation gehoert aber zu <em>einem</em> Einstiegspunkt: sie
     * faellt bei einem Umbau lautlos weg und schuetzt keinen zweiten Aufrufer.
     * Dieselbe Ueberlegung wie in {@code MiningAdminGuard} und
     * {@code RoleAssignmentService} - und die drei Rollennamen sind dieselben
     * wie in {@link com.eve.own.auth.backend.common.AccessRules#LEADERSHIP_OR_IT};
     * wer dort etwas aendert, muss es hier mitaendern.</p>
     *
     * <p>Sie steht auch vor den beiden reinen <em>Anzeige</em>-Wegen (Gruppen und
     * Kalibrierung). Dass dort nichts geschrieben wird, macht sie nicht
     * harmloser: es sind zusammengetragene Vermutungen darueber, welche Menschen
     * hinter welchen Charakteren stecken.</p>
     */
    private Character requireLeadership(Long actorId) {
        Character actor = characterRepo.findById(actorId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + actorId + " ist unbekannt."));
        boolean leadership = actor.hasRole(SystemRoles.DIRECTOR)
                || actor.hasRole(SystemRoles.CEO)
                || actor.hasRole(SystemRoles.IT_ADMIN);
        if (!leadership) {
            throw new AccessDeniedException(
                    "Charaktere einem fremden Konto vormerken darf nur die Fuehrung.");
        }
        return actor;
    }

    /** Die Aufschluesselung als eine Zeile fuer den Nachweis. */
    private static String summarize(CharacterDtos.AltSuggestionDto suggestion) {
        String detail = suggestion.signals().stream()
                .filter(Objects::nonNull)
                .map(signal -> signal.available()
                        ? "%s=%d (Gewicht %d)".formatted(signal.signal(), signal.score(),
                                signal.weightPercent())
                        : "%s=nicht verfuegbar".formatted(signal.signal()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("keine Signale");
        String summary = "%d aus %d von %d Signalen: %s".formatted(
                suggestion.probability(), suggestion.signalsUsed(), suggestion.signalsTotal(), detail);
        return summary.length() <= 500 ? summary : summary.substring(0, 500);
    }
}

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
 * Konto gehoeren koennte.
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

    public AltDetectionService(CorporationStatsService corporationStatsService,
                               CharacterRepository characterRepo,
                               CharacterMiningRepository miningRepo,
                               AltLinkProposalRepository proposalRepo,
                               DirectorTokenProvider directorTokenProvider,
                               EsiService esiService) {
        this.corporationStatsService = corporationStatsService;
        this.characterRepo = characterRepo;
        this.miningRepo = miningRepo;
        this.proposalRepo = proposalRepo;
        this.directorTokenProvider = directorTokenProvider;
        this.esiService = esiService;
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
     * {@link AltDetectionTuning#MAX_PAIRS_PER_CORPORATION} ist die Reissleine,
     * falls eine Corporation um Groessenordnungen waechst.</p>
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

        if (exceedsBudget(stats, unauthed.size(), byAccount.size())) {
            return List.of();
        }

        Map<Long, Instant> joinDates = joinDates(stats.corpId());
        MiningIndex mining = miningIndex(unauthed, byAccount);

        List<CharacterDtos.AltSuggestionDto> result = new ArrayList<>();
        // Siehe schwelleFuer: ein Vorschlag aus einem einzigen Signal muss hoeher
        // springen als einer, den mehrere Quellen tragen.
        for (CharacterDtos.UnauthedCharDto candidate : unauthed) {
            CharacterDtos.AltSuggestionDto best = null;
            for (Map.Entry<Long, List<Character>> account : byAccount.entrySet()) {
                Character main = mains.get(account.getKey());
                if (main == null) {
                    continue;
                }
                CharacterDtos.AltSuggestionDto suggestion = score(stats.corpId(), candidate, main,
                        account.getValue(), joinDates, mining);
                if (suggestion != null
                        && (best == null || suggestion.probability() > best.probability())) {
                    best = suggestion;
                }
            }
            if (best != null && best.probability() >= schwelleFuer(best)) {
                result.add(best);
            }
        }
        return result;
    }

    /**
     * Die Schwelle, die dieser Vorschlag ueberspringen muss.
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
    private static int schwelleFuer(CharacterDtos.AltSuggestionDto vorschlag) {
        return vorschlag.signalsUsed() <= 1
                ? AltDetectionTuning.MIN_PROBABILITY_SINGLE_SIGNAL
                : AltDetectionTuning.MIN_PROBABILITY;
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

    private boolean exceedsBudget(CharacterDtos.CorpStatsDto stats, int candidates, int accounts) {
        long pairs = (long) candidates * accounts;
        if (pairs <= AltDetectionTuning.MAX_PAIRS_PER_CORPORATION) {
            return false;
        }
        // Lieber eine ehrlich fehlende Corporation als eine Antwort, auf die
        // niemand wartet: der Endpunkt haengt an einem Seitenaufruf.
        log.warn("Alt-Erkennung fuer Corp {} uebersprungen: {} Paare ueberschreiten die Grenze {}.",
                stats.corpId(), pairs, AltDetectionTuning.MAX_PAIRS_PER_CORPORATION);
        return true;
    }

    // ==================================================================
    // Bewertung eines Paares
    // ==================================================================

    /**
     * Bewertet ein Paar und normiert ueber die verfuegbaren Signale.
     *
     * @return {@code null}, wenn zu wenige Signale Daten hatten - dann gibt es
     *     keine Aussage, und eine niedrige Zahl waere eine erfundene
     */
    private CharacterDtos.AltSuggestionDto score(Long corporationId,
                                                 CharacterDtos.UnauthedCharDto candidate,
                                                 Character main,
                                                 List<Character> accountCharacters,
                                                 Map<Long, Instant> joinDates,
                                                 MiningIndex mining) {
        Map<String, SignalValue> values = new LinkedHashMap<>();
        values.put(SIGNAL_NAME, nameSignal(candidate, main));
        values.put(SIGNAL_JOIN, joinSignal(candidate, accountCharacters, joinDates));
        values.put(SIGNAL_MINING, miningSignal(candidate, accountCharacters, mining));

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
        if (used < AltDetectionTuning.MIN_AVAILABLE_SIGNALS || weightSum == 0) {
            return null;
        }
        int probability = Math.clamp(Math.round((float) weighted / weightSum), 0, 100);

        return new CharacterDtos.AltSuggestionDto(
                candidate.id(), candidate.name(), main.getId(), main.getName(),
                probability, used, SIGNAL_COUNT, toSignalDtos(values), corporationId);
    }

    private static int weightOf(String signal) {
        return switch (signal) {
            case SIGNAL_NAME -> AltDetectionTuning.WEIGHT_NAME;
            case SIGNAL_JOIN -> AltDetectionTuning.WEIGHT_JOIN;
            case SIGNAL_MINING -> AltDetectionTuning.WEIGHT_MINING;
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

    private static List<CharacterDtos.AltSignalDto> toSignalDtos(Map<String, SignalValue> values) {
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
    private SignalValue nameSignal(CharacterDtos.UnauthedCharDto candidate, Character main) {
        int score = NameSimilarity.score(candidate.name(), main.getName());
        return SignalValue.of(score, "Levenshtein und EVE-Namensmuster gegen \"%s\""
                .formatted(main.getName()));
    }

    // ==================================================================
    // Signal 2: Beitritts-Cluster
    // ==================================================================

    /**
     * Die Beitrittsdaten der ganzen Corporation aus der Mitgliederverfolgung.
     *
     * @return eine leere Karte, wenn kein Director-Token traegt. Der Aufrufer
     *     macht daraus "Signal nicht verfuegbar" - und ausdruecklich nicht
     *     "Signal mit Wert 0". Der Unterschied entscheidet darueber, ob eine
     *     Corporation ohne angemeldeten Director gar keine Vorschlaege bekommt
     *     (richtig) oder lauter niedrige (falsch und irrefuehrend).
     */
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
     * Wie nah beieinander der Verdaechtige und das Konto beigetreten sind.
     *
     * <p>Verglichen wird gegen den <em>naechstliegenden</em> Charakter des
     * Kontos und nicht nur gegen den Main: ein Spieler holt seine Alts oft in
     * einem Rutsch herein, lange nachdem sein Main schon drin war.</p>
     *
     * <p><b>Gegen die Rekrutierungswelle:</b> traten im selben engen Fenster
     * viele Mitglieder bei, wird der Wert an der Clustergroesse gedaempft. Ohne
     * diese Daempfung waere das Signal derselbe Fehler wie der rohe Mining-Tag -
     * ein Gruppenereignis, das man fuer einen Fingerabdruck haelt.</p>
     */
    private SignalValue joinSignal(CharacterDtos.UnauthedCharDto candidate,
                                   List<Character> accountCharacters,
                                   Map<Long, Instant> joinDates) {
        if (joinDates.isEmpty()) {
            return SignalValue.missing("Kein Director-Token: Mitgliederverfolgung nicht abrufbar.");
        }
        Instant candidateJoin = joinDates.get(candidate.id());
        if (candidateJoin == null) {
            return SignalValue.missing("ESI nennt fuer diesen Charakter kein Beitrittsdatum.");
        }

        Duration closest = null;
        for (Character member : accountCharacters) {
            Instant memberJoin = joinDates.get(member.getId());
            if (memberJoin == null) {
                continue;
            }
            Duration distance = Duration.between(candidateJoin, memberJoin).abs();
            if (closest == null || distance.compareTo(closest) < 0) {
                closest = distance;
            }
        }
        if (closest == null) {
            return SignalValue.missing("Fuer dieses Konto nennt ESI kein Beitrittsdatum.");
        }

        int base = distanceScore(closest);
        int cluster = clusterSize(candidateJoin, joinDates);
        int score = dilute(base, cluster);

        return SignalValue.of(score, "Beitritt %s auseinander; %d Mitglieder im selben Fenster."
                .formatted(humanize(closest), cluster));
    }

    /** Voller Wert im engen Fenster, danach linearer Abfall bis auf null. */
    private static int distanceScore(Duration distance) {
        if (distance.compareTo(AltDetectionTuning.JOIN_FULL_WINDOW) <= 0) {
            return 100;
        }
        if (distance.compareTo(AltDetectionTuning.JOIN_ZERO_WINDOW) >= 0) {
            // Ein gemessener Wert 0: "die beiden sind weit auseinander beigetreten".
            // Das ist eine Aussage und kein fehlendes Signal - deshalb bleibt es
            // verfuegbar und faellt nicht aus der Normierung heraus.
            return 0;
        }
        double span = AltDetectionTuning.JOIN_ZERO_WINDOW.toSeconds()
                - AltDetectionTuning.JOIN_FULL_WINDOW.toSeconds();
        double over = distance.toSeconds() - AltDetectionTuning.JOIN_FULL_WINDOW.toSeconds();
        return (int) Math.round(100.0 * (1.0 - over / span));
    }

    /** Wieviele Mitglieder im engen Fenster um diesen Beitritt herum beitraten. */
    private static int clusterSize(Instant candidateJoin, Map<Long, Instant> joinDates) {
        int count = 0;
        for (Instant other : joinDates.values()) {
            if (Duration.between(candidateJoin, other).abs()
                    .compareTo(AltDetectionTuning.JOIN_FULL_WINDOW) <= 0) {
                count++;
            }
        }
        return count;
    }

    private static int dilute(int score, int clusterSize) {
        if (!AltDetectionTuning.JOIN_CLUSTER_DILUTION
                || clusterSize < AltDetectionTuning.JOIN_CLUSTER_MIN_SIZE) {
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
     * {@link AltDetectionTuning#MINING_RARITY_EXPONENT}.</p>
     *
     * <p><b>Und heute laeuft es trotzdem leer.</b> Fuer nicht registrierte
     * Charaktere gibt es null Mining-Zeilen, weil der Sync ihr eigenes Token
     * braucht. Das Signal meldet deshalb praktisch immer "nicht verfuegbar" -
     * und das ist die richtige Antwort, nicht "0". Daten bekaeme es erst ueber
     * die Corp-Mining-Beobachter, die auch fremde Miner an einer Corp-Struktur
     * auflisten.</p>
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

    private SignalValue miningSignal(CharacterDtos.UnauthedCharDto candidate,
                                     List<Character> accountCharacters,
                                     MiningIndex index) {
        Set<String> candidateDays = index.daysByCharacter()
                .getOrDefault(candidate.id(), Set.of());
        if (candidateDays.isEmpty()) {
            return SignalValue.missing(
                    "Keine Mining-Zeilen fuer diesen Charakter - nicht registriert, also kein "
                    + "eigenes Token und damit kein Ledger. Nicht gemessen, nicht null.");
        }

        Set<String> accountDays = new LinkedHashSet<>();
        for (Character member : accountCharacters) {
            accountDays.addAll(index.daysByCharacter().getOrDefault(member.getId(), Set.of()));
        }
        if (accountDays.isEmpty()) {
            return SignalValue.missing("Fuer dieses Konto liegen keine Mining-Zeilen vor.");
        }

        Set<String> shared = new LinkedHashSet<>(candidateDays);
        shared.retainAll(accountDays);
        if (shared.size() < AltDetectionTuning.MINING_MIN_SHARED_DAYS) {
            return SignalValue.missing(
                    "Nur %d gemeinsame Mining-Tage - zu wenig fuer eine Aussage (mindestens %d)."
                            .formatted(shared.size(), AltDetectionTuning.MINING_MIN_SHARED_DAYS));
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
    private static double rarity(String day, MiningIndex index) {
        int miners = Math.max(1, index.minersPerDay().getOrDefault(day, 1));
        return Math.pow(1.0 / miners, AltDetectionTuning.MINING_RARITY_EXPONENT);
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
        // Wahrscheinlichkeit, die niemand je errechnet hat.
        CharacterDtos.AltSuggestionDto suggestion = findProbableAlts().stream()
                .filter(entry -> entry.unauthedCharId().equals(unauthedCharId)
                        && entry.mainId().equals(mainId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fuer dieses Paar gibt es derzeit keinen Vorschlag ueber der Schwelle von "
                        + AltDetectionTuning.MIN_PROBABILITY + "."));

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

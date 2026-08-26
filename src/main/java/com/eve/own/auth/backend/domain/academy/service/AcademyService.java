package com.eve.own.auth.backend.domain.academy.service;

import com.eve.own.auth.backend.domain.academy.dto.AcademyDtos;
import com.eve.own.auth.backend.domain.academy.entity.AcademyInterest;
import com.eve.own.auth.backend.domain.academy.entity.AcademyTopic;
import com.eve.own.auth.backend.domain.academy.repository.AcademyInterestRepository;
import com.eve.own.auth.backend.domain.academy.repository.AcademyTopicRepository;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die Academy: Schulungsthemen mit Lehrplan und das Nachfragebild dazu.
 *
 * <p>Das Board sammelt ein Signal ein und macht es ablesbar - mehr nicht. Es
 * genehmigt nichts, es plant keine Termine, es benachrichtigt niemanden. Der
 * Ertrag steckt in einem Satz auf der Karte: "EWar Grundlagen: 7 Interessierte,
 * am besten Di oder Do, EU Prime". Angekuendigt wird die Schulung wie bisher in
 * Discord.</p>
 *
 * <p>Saemtliche Rechtepruefungen sitzen hier und nicht in der Oberflaeche. Die
 * Oberflaeche blendet Knoepfe aus; das ist Bequemlichkeit, kein Schutz. Und
 * nicht nur in der Oberflaeche: die {@code @PreAuthorize}-Annotationen am
 * {@code AcademyAdminController} gehoeren zu einem Einstiegspunkt, fallen bei
 * einem Umbau lautlos weg und schuetzen einen zweiten Aufrufer gar nicht. Den
 * datengetriebenen Teil des Sichtkreises ({@code teacherRoleNames} am geladenen
 * Thema) koennen sie ohnehin nicht ausdruecken.</p>
 *
 * <p>Die gefaehrlichste Stelle des Features wird nicht abgesichert, sondern
 * wegkonstruiert: es gibt <b>keinen</b> Einstiegspunkt, der eine fremde
 * Account-ID entgegennaehme - weder im Pfad noch im Rumpf. Der Handelnde kommt
 * immer aus {@code CurrentUser.characterId()}. Siehe
 * {@code AcademyDtos.SaveInterestDto}.</p>
 */
@Slf4j
@Service
public class AcademyService {

    /**
     * Der Autorenkreis: wer Themen anlegt, aendert, loescht - und wer die Namen
     * der Interessenten auch ohne hinterlegte Ausbilderrolle sieht.
     *
     * <p>Eine einzige benannte Menge, und das ist der Zweck: eine weitere Rolle
     * kommt mit genau einer Zeile hinzu und wirkt sofort auf alles, was daran
     * haengt - auf {@code canEdit}, auf {@code canViewInterest} und auf die
     * Namensliste selbst.</p>
     *
     * <p>Dieselben fuenf Namen wie in
     * {@link com.eve.own.auth.backend.common.AccessRules#ACADEMY_AUTHORS}, mit
     * dem der {@code AcademyAdminController} markiert ist - wer dort etwas
     * aendert, aendert es hier mit. {@code ROLE_A38} und {@code ROLE_69} stehen
     * als Zeichenketten da und nicht als Konstanten aus {@link SystemRoles}: sie
     * entstehen aus Ingame-Titeln, und {@link SystemRoles} fuehrt ausschliesslich
     * die Rollen, die die Anwendung selbst vergibt.</p>
     *
     * <p>Gelesen wird am Rollen-Set der Entitaet und nicht am Sicherheitskontext
     * - dasselbe Vorgehen wie {@code AuthGroupService.isAdmin}. Zwei Quellen
     * fuer denselben Kreis koennte ein Rollen-Sync auseinanderlaufen lassen.</p>
     */
    private static final Set<String> AUTHOR_ROLES = Set.of(
            SystemRoles.CEO, SystemRoles.DIRECTOR, SystemRoles.IT_ADMIN, "ROLE_A38", "ROLE_69");

    /**
     * Die einzigen Hosts, von denen ein Lehrplan Bilder holen darf.
     *
     * <p>Bei jedem Aufklappen holt <b>jeder Betrachterbrowser</b> das Bild direkt
     * beim fremden Host. Der loggt IP und User-Agent, und mangels
     * Referrer-Policy auch die Herkunfts-URL. Wer die Bild-URL setzt, hat damit
     * einen Anwesenheitsmelder ueber die Corp-Leitung - das ist kein
     * Angriffsszenario, das ist der Normalbetrieb eines Zaehlpixels. Genau
     * deshalb ist die Liste eng und nicht "alles https".</p>
     *
     * <p>Ausdruecklich <b>nicht</b> darauf: Imgur und das Discord-CDN. Letzteres
     * haengt Ablaufparameter an seine Adressen - die Bilder sterben dort
     * planmaessig, und ein Lehrplan mit toten Bildern ist schlechter als einer
     * ohne.</p>
     *
     * <p>Die Liste steht an zwei Stellen und wird an beiden durchgesetzt: hier
     * beim Speichern, damit der Autor es sofort erfaehrt, und im Browser beim
     * Rendern - zwingend, weil die Vorschau <em>ungespeicherten</em> Text zeigt
     * und weil Altbestand in der Datenbank sonst ungeprueft durchginge, sobald
     * sich die Liste einmal aendert.</p>
     */
    private static final Set<String> ALLOWED_IMAGE_HOSTS =
            Set.of("images.evetech.net", "wiki.eveuniversity.org");

    /** Das einzige erlaubte Schema fuer Bilder - siehe {@link #ALLOWED_IMAGE_HOSTS}. */
    private static final String IMAGE_SCHEME = "https";

    /**
     * Findet {@code ![alt](quelle)} im Lehrplan.
     *
     * <p>Absichtlich grob: die Gruppe faengt alles bis zum ersten Leerzeichen
     * oder zur schliessenden Klammer, damit auch der Titel-Zusatz
     * {@code ![a](url "Titel")} nicht mit in die Adresse rutscht. Ein Muster,
     * das zu wenig findet, waere hier der gefaehrliche Fehler - eines, das zu
     * viel findet, kostet nur eine Fehlermeldung an einen Autor.</p>
     */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]*)");

    /**
     * Ab wie vielen Bekundungen die Verteilung ueber Tage und Fenster
     * ausgeliefert wird.
     *
     * <p>Bei genau einer Bekundung sagt "nur Mittwoch, USTZ" in einer Corp, in
     * der sich alle kennen, faktisch den Namen - und der haengt am Sichtkreis,
     * die Verteilung nicht. Die Zahl selbst geht weiterhin an jeden.</p>
     *
     * <p>Gemeint sind <b>fremde</b> Bekundungen. Die eigene zaehlt nicht mit,
     * weil der Betrachter sie kennt und von der Verteilung abziehen kann -
     * sie wuerde die Schwelle also nur scheinbar erfuellen.</p>
     */
    private static final int MIN_INTERESTS_FOR_DISTRIBUTION = 2;

    /**
     * Was ein Unberechtigter zu hoeren bekommt, wenn er nach den Namen fragt.
     *
     * <p>Eine Konstante, weil derselbe Text auch bei einer <em>unbekannten</em>
     * Themen-ID faellt: sonst waere der Endpunkt ein Existenz-Orakel, an dem
     * sich "unbekannt" gegen "verboten" reihum abfragen liesse.</p>
     */
    private static final String INTEREST_VIEW_DENIED =
            "Wer Interesse bekundet hat, sehen nur die Ausbilder dieses Themas, "
                    + "die Fuehrung und die technische Administration.";

    private final AcademyTopicRepository topicRepo;
    private final AcademyInterestRepository interestRepo;
    private final CharacterRepository characterRepo;

    public AcademyService(AcademyTopicRepository topicRepo,
                          AcademyInterestRepository interestRepo,
                          CharacterRepository characterRepo) {
        this.topicRepo = topicRepo;
        this.interestRepo = interestRepo;
        this.characterRepo = characterRepo;
    }

    // ==================================================================
    // Was jedes Mitglied sieht
    // ==================================================================

    /**
     * Die aktiven Themen samt Nachfragebild - fuer jeden Angemeldeten.
     *
     * <p>Bewusst ohne Rechtekreis: ein Board, von dem niemand weiss, bekommt
     * auch keine Bekundungen. Sichtbar ist damit aber nur, DASS es das Thema
     * gibt und WIE VIELE Interesse haben - nicht, wer.</p>
     */
    @Transactional(readOnly = true)
    public List<AcademyDtos.TopicDto> topicsFor(Long accountId) {
        return topicDtos(requireCharacter(accountId), topicRepo.findAllByActiveTrueOrderByTitleAsc());
    }

    /**
     * Alle Themen inklusive der abgeschalteten - der Reiter "Verwaltung".
     *
     * <p>Nur fuer den Autorenkreis, und die Pruefung steht hier und nicht nur
     * als Annotation am Controller. Sonst laege offen, welche Themen jemand
     * abgeschaltet hat und warum - ein abgeschaltetes Thema ist oft eines, das
     * gerade neu geschrieben wird.</p>
     *
     * @throws AccessDeniedException wenn der Betrachter nicht zum Autorenkreis gehoert
     */
    @Transactional(readOnly = true)
    public List<AcademyDtos.TopicDto> allTopicsFor(Long accountId) {
        return topicDtos(requireAuthor(accountId), topicRepo.findAllByOrderByTitleAsc());
    }

    /**
     * Ein Thema samt Lehrplan - die Antwort auf das Aufklappen einer Karte.
     *
     * <p>Fuer jeden Angemeldeten, wie die Liste selbst - <b>solange das Thema
     * angeboten wird</b>. Ein abgeschaltetes bekommt nur der Autorenkreis, der
     * es im eigenen Reiter aufklappt. Ohne diese Grenze waere die Abschaltung
     * wirkungslos: die IDs laufen fortlaufend, und wer 1..n durchprobiert,
     * bekaeme jeden zurueckgezogenen Lehrplan im Volltext. Genau das soll
     * {@code allTopicsFor} verhindern - die Pruefung dort allein genuegt nicht,
     * wenn dieser Endpunkt daran vorbeifuehrt.</p>
     *
     * <p>Abgewiesen wird mit derselben Meldung wie ein unbekanntes Thema. Ein
     * eigener Text verriete, dass es das Thema gibt und dass jemand es
     * versteckt hat.</p>
     *
     * @throws IllegalArgumentException wenn das Thema unbekannt oder fuer
     *                                  diesen Betrachter abgeschaltet ist
     */
    @Transactional(readOnly = true)
    public AcademyDtos.TopicDetailDto topicDetail(Long accountId, Long topicId) {
        Character viewer = requireCharacter(accountId);
        AcademyTopic topic = requireTopic(topicId);
        if (!topic.isActive() && !isAuthor(viewer)) {
            throw unknownTopic(topicId);
        }
        List<AcademyDtos.TopicDto> dtos = topicDtos(viewer, List.of(topic));
        return new AcademyDtos.TopicDetailDto(dtos.getFirst(), topic.getDescription());
    }

    // ==================================================================
    // Die eigene Bekundung - immer nur die eigene
    // ==================================================================

    /**
     * Bekundet Interesse oder schreibt die bestehende Bekundung um.
     *
     * <p>Der Account kommt aus der Sitzung, nicht aus dem Aufruf: es gibt keinen
     * Weg, eine fremde Bekundung anzulegen oder zu aendern, weil es keinen
     * Parameter dafuer gibt. Das ist die gefaehrlichste Stelle des Features, und
     * sie ist deshalb nicht abgesichert, sondern nicht vorhanden.</p>
     *
     * <p>Idempotent: ein zweiter Aufruf desselben Accounts findet die bestehende
     * Zeile und schreibt sie um. Deshalb {@code PUT} und nicht {@code POST} -
     * und deshalb der Unique-Constraint an der Tabelle, der das Wettrennen
     * zweier gleichzeitiger Aufrufe abfaengt.</p>
     *
     * <p>Mindestens ein Tag <b>und</b> mindestens ein Fenster: eine Bekundung
     * ohne beides sagt niemandem, wann jemand kann, zaehlt aber im Zaehler mit
     * und verwaessert damit genau die Auskunft, um die es geht. Die Oberflaeche
     * prueft das ebenfalls - das ist Bequemlichkeit, diese Zeile ist der Riegel.</p>
     *
     * @return das Thema mit den frisch gerechneten Zaehlern, damit die
     *     Oberflaeche die Karte umschreiben kann statt die ganze Liste neu zu laden
     * @throws IllegalArgumentException bei unbekanntem oder abgeschaltetem Thema,
     *     leerer Auswahl, unbekanntem Wochentag, unbekanntem Fenster oder zu
     *     langer Notiz
     */
    @Transactional
    public AcademyDtos.TopicDto saveInterest(Long accountId, Long topicId,
                                             AcademyDtos.SaveInterestDto dto) {
        Character viewer = requireCharacter(accountId);
        AcademyTopic topic = requireTopic(topicId);

        // Ein abgeschaltetes Thema steht in keiner Liste, die ein Mitglied
        // sieht. Wer trotzdem darauf bekundet, hat eine veraltete Seite offen -
        // und die Nachfrage waechst dann an einem Thema, das niemand mehr
        // anbietet. Die bestehenden Bekundungen bleiben davon unberuehrt.
        // Ohne den Titel: die Meldung geht an jeden, der eine ID durchprobiert,
        // und benennte sonst ausgerechnet das Thema, dessen Abschaltung sie
        // durchsetzt. Wer die Seite wirklich veraltet offen hat, sieht den
        // Titel ohnehin vor sich.
        if (!topic.isActive()) {
            throw new IllegalArgumentException("Dieses Thema wird derzeit nicht angeboten.");
        }

        // Erst pruefen, dann schreiben: ein unbekannter Wochentag soll die
        // bestehende Bekundung gar nicht erst anfassen.
        Set<String> weekdays = normalizedWeekdays(dto.weekdays());
        Set<String> timeWindows = normalizedTimeWindows(dto.timeWindows());
        if (weekdays.isEmpty() || timeWindows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Waehle mindestens einen Tag und ein Zeitfenster - sonst weiss "
                            + "niemand, wann du kannst.");
        }
        String note = blankToNull(dto.note());
        if (note != null && note.length() > AcademyInterest.MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Die Notiz darf hoechstens "
                    + AcademyInterest.MAX_NOTE_LENGTH + " Zeichen lang sein.");
        }

        Instant now = Instant.now();
        AcademyInterest interest = interestRepo.findByTopicIdAndAccountId(topicId, accountId)
                .orElseGet(() -> {
                    AcademyInterest fresh = new AcademyInterest();
                    fresh.setTopicId(topicId);
                    fresh.setAccountId(accountId);
                    fresh.setCreatedAt(now);
                    return fresh;
                });
        interest.setNote(note);
        interest.setUpdatedAt(now);
        // Geleert und neu gefuellt statt ausgetauscht: an einer verwalteten
        // Entitaet ist die Sammlung eine Hibernate-Sammlung, und ein Austausch
        // des Behaelters kostet sie ihre Aenderungsverfolgung.
        interest.getWeekdays().clear();
        interest.getWeekdays().addAll(weekdays);
        interest.getTimeWindows().clear();
        interest.getTimeWindows().addAll(timeWindows);
        interestRepo.save(interest);

        return topicDtos(viewer, List.of(topic)).getFirst();
    }

    /**
     * Zieht die eigene Bekundung zurueck - die Zeile verschwindet.
     *
     * <p>Es gibt kein "abgelehnt" und kein "zurueckgezogen" als Zustand: es
     * wurde nie etwas beantragt. Wie beim Anlegen kommt der Account aus der
     * Sitzung; eine fremde Bekundung ist ueber diesen Weg nicht erreichbar.</p>
     *
     * <p>Das Thema wird nicht nachgeschlagen. Zu einem unbekannten Thema gibt es
     * ohnehin keine Zeile, und die Meldung ist in beiden Faellen dieselbe -
     * damit ist auch dieser Endpunkt kein Existenz-Orakel.</p>
     *
     * @throws IllegalArgumentException wenn keine eigene Bekundung vorliegt. Ein
     *     stilles "erledigt" verdeckte eine veraltete Anzeige oder einen falsch
     *     verdrahteten Knopf - der Aufrufer glaubte dann, etwas bewirkt zu haben.
     */
    @Transactional
    public void withdrawInterest(Long accountId, Long topicId) {
        AcademyInterest interest = interestRepo.findByTopicIdAndAccountId(topicId, accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Zu diesem Thema liegt von dir keine Bekundung vor."));
        interestRepo.delete(interest);
    }

    // ==================================================================
    // Die Namen der Interessenten - der enge Kreis
    // ==================================================================

    /**
     * Wer Interesse bekundet hat, mit Namen, Zeiten und Notiz.
     *
     * <p>Sichtkreis: der {@link #AUTHOR_ROLES}-Kreis <b>oder</b> eine der am
     * Thema hinterlegten {@code teacherRoleNames}. Die leere Menge oeffnet
     * nichts - {@code anyMatch} liefert dann {@code false}, und uebrig bleibt
     * der feste Kreis.</p>
     *
     * <p><b>Erst der Rechtekreis, dann die Sache.</b> Fuer die Ausbilderrolle
     * muss das Thema zwar geladen werden - anders ist die Frage nicht zu
     * beantworten -, aber ein fehlendes Recht wirft denselben Text wie ein
     * unbekanntes Thema. Andernfalls beantwortete der Endpunkt auch einem
     * Unberechtigten, welche Themen-IDs es gibt: "unbekannt" gegen "verboten"
     * ist ein Unterschied, den man reihum abfragen kann.</p>
     *
     * <p>Ein Unberechtigter bekommt eine Ausnahme und <b>keine leere Liste</b>.
     * Eine leere Liste behauptete, niemand habe Interesse - und liesse sich von
     * "Thema existiert, hat aber keine Bekundungen" nicht unterscheiden.</p>
     *
     * @throws AccessDeniedException wenn der Betrachter nicht zum Sichtkreis
     *     gehoert - auch dann, wenn es das Thema gar nicht gibt
     */
    @Transactional(readOnly = true)
    public List<AcademyDtos.InterestDto> interestedIn(Long viewerId, Long topicId) {
        Character viewer = requireCharacter(viewerId);

        Optional<AcademyTopic> found = topicRepo.findById(topicId);
        boolean mayView = isAuthor(viewer)
                || found.map(topic -> isTeacherOf(topic, viewer)).orElse(false);
        if (!mayView) {
            throw new AccessDeniedException(INTEREST_VIEW_DENIED);
        }
        // Hierher kommt nur, wer sehen darf; jetzt darf "unbekannt" auch
        // unbekannt heissen.
        AcademyTopic topic = found.orElseThrow(
                () -> new IllegalArgumentException("Thema " + topicId + " ist unbekannt."));

        List<AcademyInterest> interests = interestRepo.findByTopicId(topic.getId());
        Map<Long, Character> members = membersOf(interests);
        return interests.stream()
                .filter(interest -> members.containsKey(interest.getAccountId()))
                .map(interest -> new AcademyDtos.InterestDto(
                        interest.getAccountId(),
                        members.get(interest.getAccountId()).getName(),
                        sortedWeekdays(interest.getWeekdays()),
                        sortedTimeWindows(interest.getTimeWindows()),
                        interest.getNote(),
                        interest.getUpdatedAt()))
                // Nach Namen und ohne Ruecksicht auf Gross- und Kleinschreibung:
                // EVE-Namen beginnen mal so, mal so, und eine Liste, in der
                // "alpha" hinter "Zulu" steht, sieht nach einem Fehler aus.
                .sorted(Comparator.comparing(AcademyDtos.InterestDto::characterName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    // ==================================================================
    // Pflege der Themen - dem Autorenkreis vorbehalten
    // ==================================================================

    /**
     * Legt ein Thema an ({@code id == null}) oder aendert es.
     *
     * @throws AccessDeniedException wenn der Bearbeiter nicht zum Autorenkreis gehoert
     * @throws IllegalArgumentException bei leerem oder zu langem Titel, leerer oder
     *     zu langer Kurzzeile, zu langem Lehrplan, bereits vergebenem Titel,
     *     untauglicher Ausbilderrolle oder einer Bildquelle ausserhalb der Allowlist
     */
    @Transactional
    public AcademyDtos.TopicDto saveTopic(Long editorId, AcademyDtos.SaveTopicDto dto) {
        Character editor = requireAuthor(editorId);

        String title = trimmed(dto.title());
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Das Thema braucht einen Titel.");
        }
        if (title.length() > AcademyTopic.MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Der Titel darf hoechstens "
                    + AcademyTopic.MAX_TITLE_LENGTH + " Zeichen lang sein.");
        }
        String summary = trimmed(dto.summary());
        if (summary.isEmpty()) {
            throw new IllegalArgumentException(
                    "Die Kurzzeile ist Pflicht - ohne sie steht in der Liste nur der Titel.");
        }
        if (summary.length() > AcademyTopic.MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("Die Kurzzeile darf hoechstens "
                    + AcademyTopic.MAX_SUMMARY_LENGTH + " Zeichen lang sein.");
        }
        String description = blankToNull(dto.description());
        if (description != null && description.length() > AcademyTopic.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Der Lehrplan darf hoechstens "
                    + AcademyTopic.MAX_DESCRIPTION_LENGTH + " Zeichen lang sein.");
        }
        // Erst pruefen, dann schreiben: eine abgewiesene Bildquelle soll das
        // Thema gar nicht erst veraendern.
        verifyImageHosts(description);
        Set<String> teacherRoleNames = normalizedTeacherRoles(dto.teacherRoleNames());

        // Die Pruefung fuer die lesbare Meldung, der Unique-Constraint gegen das
        // Wettrennen. Und ohne Ruecksicht auf die Schreibweise: "EWar
        // Grundlagen" und "ewar grundlagen" waeren zwei Zeilen, die im Board
        // niemand auseinanderhaelt - die Bekundungen verteilten sich auf beide.
        Optional<AcademyTopic> sameTitle = topicRepo.findByTitleIgnoreCase(title);
        if (sameTitle.isPresent() && !sameTitle.get().getId().equals(dto.id())) {
            throw new IllegalArgumentException(
                    "Ein Thema mit dem Titel \"" + title + "\" gibt es bereits.");
        }

        boolean isNew = dto.id() == null;
        Instant now = Instant.now();
        AcademyTopic topic = isNew
                ? new AcademyTopic()
                : topicRepo.findById(dto.id()).orElseThrow(() -> new IllegalArgumentException(
                        "Thema " + dto.id() + " ist unbekannt."));
        if (isNew) {
            topic.setCreatedAt(now);
        }
        topic.setTitle(title);
        topic.setSummary(summary);
        topic.setDescription(description);
        topic.setActive(dto.active());
        topic.setUpdatedAt(now);
        topic.setUpdatedByAccountId(editorId);
        topic.getTeacherRoleNames().clear();
        topic.getTeacherRoleNames().addAll(teacherRoleNames);
        topicRepo.save(topic);

        // Ohne diese Zeile waere spaeter nicht mehr zu sagen, wer einen Lehrplan
        // umgeschrieben hat - am Thema steht nur der neue Text.
        log.info("{} ({}) hat das Academy-Thema \"{}\" {}.",
                editor.getName(), editorId, title, isNew ? "angelegt" : "geaendert");

        return topicDtos(editor, List.of(topic)).getFirst();
    }

    /**
     * Loescht ein Thema samt seiner Bekundungen.
     *
     * <p>Die Bekundungen zeigen mit blanker ID auf das Thema; es gibt keinen
     * Fremdschluessel, der sie mitnaehme. Bleiben sie stehen, zaehlen sie fuer
     * immer auf ein Thema, das es nicht mehr gibt.</p>
     *
     * @throws AccessDeniedException wenn der Bearbeiter nicht zum Autorenkreis gehoert
     * @throws IllegalArgumentException wenn das Thema unbekannt ist
     */
    @Transactional
    public void deleteTopic(Long editorId, Long topicId) {
        Character editor = requireAuthor(editorId);
        AcademyTopic topic = requireTopic(topicId);

        interestRepo.deleteByTopicId(topicId);
        topicRepo.deleteById(topicId);

        // Ein geloeschter Lehrplan waere sonst spurlos weg - samt der Nachfrage,
        // die sich ueber Monate an ihm gesammelt hat.
        log.info("{} ({}) hat das Academy-Thema \"{}\" samt seiner Bekundungen geloescht.",
                editor.getName(), editorId, topic.getTitle());
    }

    // ==================================================================
    // Innereien
    // ==================================================================

    /** Ob dieser Charakter Themen pflegen und die Namen aller Interessenten sehen darf. */
    private static boolean isAuthor(Character character) {
        return AUTHOR_ROLES.stream().anyMatch(character::hasRole);
    }

    /**
     * Ob dieser Charakter eine der am Thema hinterlegten Ausbilderrollen traegt.
     *
     * <p>Eine Ueberschneidung, kein Vergleich: es genuegt <b>eine</b> passende
     * Rolle. Die leere Menge ist der tragende Fall und keine Randerscheinung -
     * {@code anyMatch} beantwortet sie von selbst mit {@code false}, und uebrig
     * bleibt der Autorenkreis. Ein "keine Ausbilderrolle eingetragen, also darf
     * jeder" darf hier nie entstehen.</p>
     */
    private static boolean isTeacherOf(AcademyTopic topic, Character character) {
        Set<String> teacherRoles = topic.getTeacherRoleNames();
        return teacherRoles != null && teacherRoles.stream().anyMatch(character::hasRole);
    }

    /**
     * Der Riegel vor jeder Pflege eines Themas.
     *
     * <p>Der {@code AcademyAdminController} traegt zwar ein klassenweites
     * {@code @PreAuthorize}, doch das ist eine Eigenschaft des einen
     * Einstiegspunkts und nicht der Sache. Faellt die Annotation bei einem Umbau
     * weg, koennte jeder Angemeldete Lehrplaene umschreiben - und ein Lehrplan
     * ist genau der Ort, an dem ein Director in Ruhe hineinschaut.</p>
     *
     * @return der Bearbeiter, weil der Aufrufer ihn ohnehin fuer die Antwort braucht
     */
    private Character requireAuthor(Long editorId) {
        Character editor = requireCharacter(editorId);
        if (!isAuthor(editor)) {
            throw new AccessDeniedException(
                    "Themen der Academy pflegen die Ausbilder, die Fuehrung und die "
                            + "technische Administration.");
        }
        return editor;
    }

    /**
     * Baut die Datensaetze fuer eine Menge Themen - <b>der eine</b> Ort, an dem
     * Zaehler, Verteilung, eigene Bekundung und Sichtkreis entstehen.
     *
     * <p>Eine Stelle und nicht mehrere, damit Zaehler und Namensliste nie
     * auseinanderlaufen. Eine Karte, die "5 Interessierte" sagt und beim
     * Aufklappen 3 Namen zeigt, ist ein Fehlerbericht.</p>
     *
     * <p>Ein Ladevorgang fuer alle Bekundungen und einer fuer alle Charaktere -
     * nicht je Thema einer. Muster: {@code AuthGroupService.memberCounts}.</p>
     */
    private List<AcademyDtos.TopicDto> topicDtos(Character viewer, List<AcademyTopic> topics) {
        if (topics.isEmpty()) {
            return List.of();
        }
        List<Long> topicIds = topics.stream().map(AcademyTopic::getId).toList();
        List<AcademyInterest> interests = interestRepo.findByTopicIdIn(topicIds);
        Map<Long, Character> members = membersOf(interests);

        // Karteileichen fallen an genau dieser Stelle heraus, vor jeder
        // Auswertung. Wer die Corp verlaesst, wird nicht geloescht - die Rollen
        // werden auf ROLE_GUEST zurueckgesetzt und die Bekundung bleibt stehen.
        // Ungefiltert blaehte sie die Nachfrage mit Leuten auf, die seit Monaten
        // nicht mehr da sind.
        Map<Long, List<AcademyInterest>> byTopic = interests.stream()
                .filter(interest -> members.containsKey(interest.getAccountId()))
                .collect(Collectors.groupingBy(AcademyInterest::getTopicId));

        // Die EIGENE Bekundung dagegen aus der ungefilterten Menge: auch wer
        // inzwischen Gast ist, muss seine Zeile noch sehen und zuruecknehmen
        // koennen. Sie zaehlt nur nirgends mit.
        Map<Long, AcademyInterest> mine = interests.stream()
                .filter(interest -> interest.getAccountId().equals(viewer.getId()))
                .collect(Collectors.toMap(AcademyInterest::getTopicId, Function.identity(),
                        (first, second) -> first));

        boolean canEdit = isAuthor(viewer);
        return topics.stream()
                .map(topic -> toTopicDto(topic, viewer, canEdit,
                        byTopic.getOrDefault(topic.getId(), List.of()),
                        mine.get(topic.getId())))
                .toList();
    }

    private AcademyDtos.TopicDto toTopicDto(AcademyTopic topic,
                                            Character viewer,
                                            boolean canEdit,
                                            List<AcademyInterest> interests,
                                            AcademyInterest myInterest) {
        int count = interests.size();
        // Gezaehlt wird OHNE die eigene Bekundung. Sonst ist die Schwelle bei
        // zwei Interessenten wirkungslos, wenn der Betrachter einer davon ist:
        // er kennt seine eigenen Tage, zieht sie von der Verteilung ab und hat
        // das genaue Profil des anderen - Tage und Fenster - vor sich, obwohl
        // ihm der Name verwehrt bleibt. Die Verteilung darf also erst dann
        // stehen, wenn mindestens zwei ANDERE sie tragen.
        int fremde = count - (myInterest == null ? 0 : 1);
        boolean showDistribution = fremde >= MIN_INTERESTS_FOR_DISTRIBUTION;
        return new AcademyDtos.TopicDto(
                topic.getId(),
                topic.getTitle(),
                topic.getSummary(),
                topic.isActive(),
                // Sortiert, weil die Sammlung eine Streuung ist: ohne feste
                // Ordnung wechselten die Etiketten bei jedem Laden die Plaetze.
                topic.getTeacherRoleNames().stream().sorted().toList(),
                count,
                showDistribution ? weekdayCounts(interests) : Map.of(),
                showDistribution ? windowCounts(interests) : Map.of(),
                myInterest == null ? List.of() : sortedWeekdays(myInterest.getWeekdays()),
                myInterest == null ? List.of() : sortedTimeWindows(myInterest.getTimeWindows()),
                myInterest == null ? null : myInterest.getNote(),
                myInterest != null,
                canEdit,
                canEdit || isTeacherOf(topic, viewer));
    }

    /**
     * Die Charaktere hinter den Bekundungen - <b>ohne</b> die Gaeste.
     *
     * <p>Ein Ladevorgang fuer alle IDs statt einer Abfrage je Zeile. Wer nicht
     * aufloesbar ist, faellt ebenso heraus wie ein Gast: beide sind fuer das
     * Nachfragebild dasselbe, naemlich niemand, der noch erreichbar waere.</p>
     *
     * <p>Geprueft wird auf {@code ROLE_GUEST} und nicht auf "der Rollensatz
     * besteht genau aus ROLE_GUEST": kaeme spaeter eine zweite Rolle neben den
     * Gaststatus, holte der genauere Vergleich die Karteileichen still zurueck
     * ins Nachfragebild - und niemand suchte den Fehler hier.</p>
     */
    private Map<Long, Character> membersOf(Collection<AcademyInterest> interests) {
        Set<Long> accountIds = interests.stream()
                .map(AcademyInterest::getAccountId)
                .collect(Collectors.toSet());
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return characterRepo.findAllById(accountIds).stream()
                .filter(character -> !character.hasRole(SystemRoles.GUEST))
                .collect(Collectors.toMap(Character::getId, Function.identity()));
    }

    /**
     * Wie oft jeder Wochentag genannt wurde - alle sieben, auch die mit null.
     *
     * <p>Alle sieben, damit die Oberflaeche einen vergleichbaren Streifen
     * zeichnen kann: nur die belegten Tage zu liefern machte Karten
     * untereinander unvergleichbar, und das Auge soll Muster ueber Karten hinweg
     * erkennen.</p>
     *
     * <p>{@code LinkedHashMap} in {@code DayOfWeek}-Ordnung. Alphabetisch waere
     * es {@code FRIDAY, MONDAY, SATURDAY, SUNDAY, THURSDAY...} - absurd.</p>
     */
    private static Map<String, Integer> weekdayCounts(List<AcademyInterest> interests) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            counts.put(day.name(), 0);
        }
        for (AcademyInterest interest : interests) {
            for (String weekday : interest.getWeekdays()) {
                counts.merge(weekday, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Wie oft jedes Zeitfenster genannt wurde - alle fuenf, in Tagesreihenfolge. */
    private static Map<String, Integer> windowCounts(List<AcademyInterest> interests) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String window : AcademyInterest.TIME_WINDOWS) {
            counts.put(window, 0);
        }
        for (AcademyInterest interest : interests) {
            for (String window : interest.getTimeWindows()) {
                counts.merge(window, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Bringt die Wochentage auf gueltige {@link DayOfWeek}-Namen.
     *
     * <p>Nach dem Vorbild von {@code AuthGroupService.normalizedLeaderRoles}:
     * Leeres faellt still heraus - die Oberflaeche schickt fuer eine noch nicht
     * ausgefuellte Zeile eine leere Zeichenkette, und die soll keinen Fehler
     * ausloesen -, Unbekanntes wirft. So bekommt man die Pruefschaerfe des Enums,
     * ohne {@code @Enumerated} als neues Muster einzufuehren.</p>
     *
     * <p>{@code LinkedHashSet}: die Menge entdoppelt zwei Schreibweisen
     * desselben Tages, behaelt aber die eingegebene Reihenfolge.</p>
     */
    private static Set<String> normalizedWeekdays(Collection<String> rawWeekdays) {
        if (rawWeekdays == null) {
            return Set.of();
        }
        Set<String> weekdays = new LinkedHashSet<>();
        for (String rawWeekday : rawWeekdays) {
            if (rawWeekday == null || rawWeekday.isBlank()) {
                continue;
            }
            String candidate = rawWeekday.trim().toUpperCase(Locale.ROOT);
            try {
                weekdays.add(DayOfWeek.valueOf(candidate).name());
            } catch (IllegalArgumentException unknownDay) {
                // Die Meldung von valueOf lautet "No enum constant ..." und
                // taugt nicht fuer einen Menschen.
                throw new IllegalArgumentException(
                        "\"" + rawWeekday + "\" ist kein Wochentag.");
            }
        }
        return weekdays;
    }

    /** Wie {@link #normalizedWeekdays}, nur gegen die fuenf Fensterkonstanten. */
    private static Set<String> normalizedTimeWindows(Collection<String> rawTimeWindows) {
        if (rawTimeWindows == null) {
            return Set.of();
        }
        Set<String> timeWindows = new LinkedHashSet<>();
        for (String rawTimeWindow : rawTimeWindows) {
            if (rawTimeWindow == null || rawTimeWindow.isBlank()) {
                continue;
            }
            String candidate = rawTimeWindow.trim().toUpperCase(Locale.ROOT);
            if (!AcademyInterest.TIME_WINDOWS.contains(candidate)) {
                throw new IllegalArgumentException("\"" + rawTimeWindow
                        + "\" ist kein Zeitfenster. Erlaubt sind "
                        + String.join(", ", AcademyInterest.TIME_WINDOWS) + ".");
            }
            timeWindows.add(candidate);
        }
        return timeWindows;
    }

    /**
     * Bringt die Ausbilderrollen auf Rollenschreibweise; leer heisst "nur der
     * Autorenkreis sieht die Namen".
     *
     * <p>Woertlich wie {@code AuthGroupService.normalizedLeaderRoles}, samt der
     * Begruendung: geprueft wird nicht gegen den Rollenkatalog, weil die
     * ueblichen Ausbilderrollen erst durch einen Ingame-Titel entstehen und dann
     * in {@code title_role_mappings} stehen statt in {@code system_roles}.</p>
     *
     * <p>Eingebaute Rollen sind dagegen ausgeschlossen, und zwar jede einzelne:
     * {@code ROLE_USER}, {@code ROLE_MEMBER} und {@code ROLE_GUEST} traegt
     * praktisch jeder - eine davon genuegte, damit jeder Angemeldete die
     * Namensliste dieses Themas saehe. Weil schon <em>eine</em> passende Rolle
     * den Sichtkreis oeffnet, waere eine Pruefung "wenigstens eine taugt" hier
     * wertlos.</p>
     */
    private static Set<String> normalizedTeacherRoles(Collection<String> rawTeacherRoleNames) {
        if (rawTeacherRoleNames == null) {
            return Set.of();
        }
        Set<String> teacherRoleNames = new LinkedHashSet<>();
        for (String rawTeacherRoleName : rawTeacherRoleNames) {
            if (rawTeacherRoleName == null || rawTeacherRoleName.isBlank()) {
                continue;
            }
            String teacherRoleName = SystemRoles.normalize(rawTeacherRoleName);
            if (SystemRoles.isBuiltIn(teacherRoleName)) {
                throw new IllegalArgumentException(teacherRoleName
                        + " ist eine eingebaute Rolle und taugt nicht als Ausbilderrolle.");
            }
            teacherRoleNames.add(teacherRoleName);
        }
        return teacherRoleNames;
    }

    /**
     * Weist jede Bildquelle ab, die nicht von einem erlaubten Host kommt.
     *
     * <p>Die Meldung nennt den abgewiesenen Host, damit der Autor sofort weiss,
     * woran es liegt - eine still verschluckte Bildquelle waere ein
     * Fehlerbericht am naechsten Tag.</p>
     *
     * <p><b>Der Host wird exakt verglichen.</b> Ein {@code endsWith} oder
     * {@code contains} faellt auf {@code images.evetech.net.boeser-host.example}
     * herein - eine Adresse, die jeder registrieren kann und die auf den ersten
     * Blick wie die erlaubte aussieht. {@code URI.getHost()} trennt ausserdem
     * die Benutzerangabe ab, sodass auch
     * {@code https://images.evetech.net&#64;boeser-host.example/px.png} als das
     * erkannt wird, was es ist.</p>
     */
    private static void verifyImageHosts(String description) {
        if (description == null) {
            return;
        }
        Matcher matcher = MARKDOWN_IMAGE.matcher(description);
        while (matcher.find()) {
            String rawSource = matcher.group(1).trim();
            // Die Schreibweise ![a](<url>) ist erlaubtes Markdown; die spitzen
            // Klammern gehoeren nicht zur Adresse.
            if (rawSource.startsWith("<") && rawSource.endsWith(">") && rawSource.length() > 1) {
                rawSource = rawSource.substring(1, rawSource.length() - 1).trim();
            }
            if (rawSource.isEmpty()) {
                throw new IllegalArgumentException(
                        "Eine Bildquelle im Lehrplan ist leer. Erlaubt sind Bilder von "
                                + allowedImageHosts() + ".");
            }
            String host = hostOf(rawSource);
            if (host == null || !ALLOWED_IMAGE_HOSTS.contains(host)) {
                throw new IllegalArgumentException("Bildquelle nicht erlaubt: "
                        + (host == null ? rawSource : host) + ". Erlaubt sind Bilder von "
                        + allowedImageHosts() + ".");
            }
        }
    }

    /**
     * Der Host einer Bildadresse in Kleinschreibung, oder {@code null}, wenn es
     * keinen brauchbaren gibt.
     *
     * <p>{@code null} auch bei {@code http}, {@code data:} und jedem anderen
     * Schema: nur {@code https} kommt in Frage. Bei {@code http} laedt der
     * Browser das Bild auf einer https-Seite ohnehin nicht, und {@code data:}
     * gehoert aus Betriebsgruenden in keine Stufe dieses Features - ein
     * eingebettetes Bild ginge bei jeder Antwort mit ueber die Leitung.</p>
     */
    private static String hostOf(String rawSource) {
        try {
            URI uri = new URI(rawSource);
            if (!IMAGE_SCHEME.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException noUri) {
            return null;
        }
    }

    private static String allowedImageHosts() {
        return ALLOWED_IMAGE_HOSTS.stream().sorted().collect(Collectors.joining(", "));
    }

    /** Wochentage in Wochenordnung, nicht alphabetisch - {@code FRIDAY} zuerst waere absurd. */
    private static List<String> sortedWeekdays(Set<String> weekdays) {
        return weekdays.stream()
                .sorted(Comparator.<String>comparingInt(AcademyService::weekdayOrder)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** Zeitfenster in Tagesreihenfolge, nicht alphabetisch. */
    private static List<String> sortedTimeWindows(Set<String> timeWindows) {
        return timeWindows.stream()
                .sorted(Comparator.<String>comparingInt(AcademyService::timeWindowOrder)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Unbekanntes ans Ende statt in eine Ausnahme: die Sortierung ist Anzeige,
     * und ein Wert, den ein spaeteres Schema hinterlaesst, darf keine Liste
     * sprengen.
     */
    private static int weekdayOrder(String weekday) {
        try {
            return DayOfWeek.valueOf(weekday).getValue();
        } catch (IllegalArgumentException unknownDay) {
            return Integer.MAX_VALUE;
        }
    }

    /** Dasselbe fuer die Fenster: {@code indexOf} liefert fuer Unbekanntes -1, das waere vorn. */
    private static int timeWindowOrder(String timeWindow) {
        int index = AcademyInterest.TIME_WINDOWS.indexOf(timeWindow);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }

    private AcademyTopic requireTopic(Long topicId) {
        return topicRepo.findById(topicId).orElseThrow(() -> unknownTopic(topicId));
    }

    /**
     * Die eine Meldung fuer "gibt es nicht" und "geht dich nichts an".
     *
     * <p>Eine gemeinsame Quelle, weil die beiden Faelle ununterscheidbar bleiben
     * muessen. Zwei getrennt formulierte Texte gehen beim naechsten Umbau
     * auseinander, und dann verraet der Unterschied genau das, was die Pruefung
     * verbergen soll.</p>
     */
    private static IllegalArgumentException unknownTopic(Long topicId) {
        return new IllegalArgumentException("Thema " + topicId + " ist unbekannt.");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

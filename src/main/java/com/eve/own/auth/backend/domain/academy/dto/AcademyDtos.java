package com.eve.own.auth.backend.domain.academy.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Die Datensaetze der Academy - Anzeige wie Pflege.
 *
 * <p>Alle in einer Klasse als verschachtelte {@code record}s, wie
 * {@code AuthGroupDtos} es haelt. Und wie dort tragen sie die Sicht des
 * Aufrufers gleich mit, statt das Frontend aus Rollennamen selbst
 * zusammenreimen zu lassen, was es anbieten darf.</p>
 */
public class AcademyDtos {

    /**
     * Ein Thema, gesehen vom Aufrufer - <b>ohne</b> Lehrplan.
     *
     * <p>Der Lehrplan fehlt hier mit Absicht: bei zwoelf Themen gingen sonst
     * zwoelf Lehrplaene ueber die Leitung, bei jedem Laden der Liste. Er kommt
     * per {@link TopicDetailDto} nach, wenn jemand die Karte aufklappt.</p>
     *
     * @param interestCount  wie viele Leute Interesse bekundet haben. Geht an
     *     <b>jeden</b> Angemeldeten - das ist der Kern des Boards. Nur die Namen
     *     haengen am Sichtkreis.
     *     <p>Karteileichen zaehlen nicht mit: wer die Corp verlassen hat, traegt
     *     nur noch {@code ROLE_GUEST} und faellt vor der Zaehlung heraus. Zahl
     *     und Namensliste entstehen aus derselben gefilterten Menge, damit eine
     *     Karte nie "5 Interessierte" sagt und beim Aufklappen 3 Namen zeigt.
     * @param weekdayCounts  wie oft jeder Wochentag genannt wurde, in
     *     {@code DayOfWeek}-Ordnung ({@code MONDAY} zuerst). Alphabetisch waere
     *     {@code FRIDAY, MONDAY, SATURDAY...} - fuer das Auge wertlos.
     *     <p><b>Leer unterhalb von zwei Bekundungen.</b> Bei genau einer verraet
     *     "nur Mittwoch, USTZ" in einer Corp, in der sich alle kennen, faktisch
     *     den Namen - und der haengt am Sichtkreis, die Verteilung nicht. Eine
     *     leere Karte ist der Preis; ein Name durch die Hintertuer waere teurer.
     * @param windowCounts   dasselbe fuer die Zeitfenster, in Tagesreihenfolge
     *     ({@code AcademyInterest.TIME_WINDOWS}), mit derselben Schwelle
     * @param myWeekdays     die eigene Bekundung, mitgeliefert statt per zweitem
     *     Endpunkt: so kommt die Oberflaeche mit einem Ladevorgang aus
     * @param hasMyInterest  ob ueberhaupt eine eigene Bekundung vorliegt - eine
     *     Bekundung ganz ohne Tage gibt es nicht, aber die Oberflaeche soll das
     *     nicht aus einer leeren Liste schliessen muessen
     * @param canEdit        ob der Betrachter Themen anlegen, aendern und
     *     loeschen darf (der feste Autorenkreis)
     * @param canViewInterest ob er die <b>Namen</b> der Interessenten abrufen
     *     darf - Autorenkreis oder eine der am Thema hinterlegten
     *     Ausbilderrollen.
     *     <p>Steht neben {@code interestCount} und nicht in ihm: die Zahl ist
     *     eine Auskunft, die Berechtigung eine Zusicherung. Beides in einem Feld
     *     hiesse, die Oberflaeche muesste aus einer Zahl auf ein Recht
     *     schliessen - eine Ableitung, die nirgends geschrieben steht und
     *     deshalb still falsch wird. Ein {@code boolean} und kein
     *     {@code Boolean}: "unbekannt, ob erlaubt" gibt es nicht.
     */
    public record TopicDto(Long id, String title, String summary, boolean active,
                           List<String> teacherRoleNames,
                           int interestCount,
                           Map<String, Integer> weekdayCounts,
                           Map<String, Integer> windowCounts,
                           List<String> myWeekdays, List<String> myTimeWindows, String myNote,
                           boolean hasMyInterest, boolean canEdit, boolean canViewInterest) {}

    /**
     * Das Thema samt Lehrplan - die Antwort auf das Aufklappen einer Karte.
     *
     * <p>Der Lehrplan geht als roher Markdown-Text hinaus und nicht als fertiges
     * HTML. Das ist die Sicherheitsentscheidung dieses Features: gerendert wird
     * ausschliesslich im Browser, aus einem Token-Modell heraus, ueber
     * {@code &#123;&#123; &#125;&#125;}. Ein HTML-String vom Server waere die
     * erste Stelle im Projekt, an der Markup aus Daten in den DOM ginge - und
     * die Vorschau des Editors zeigt ohnehin ungespeicherten Text, haette also
     * einen zweiten, ungeschuetzten Pfad.</p>
     *
     * @param description {@code null}, solange niemand einen Lehrplan
     *     geschrieben hat - eine leere Zeichenkette waere dieselbe Aussage mit
     *     mehr Zeichen
     */
    public record TopicDetailDto(TopicDto topic, String description) {}

    /**
     * Ein Interessent mit Namen - nur fuer den Sichtkreis.
     *
     * <p>Wer nicht dazugehoert, bekommt eine {@code AccessDeniedException} und
     * keine leere Liste; eine leere Liste behauptete, niemand habe Interesse.</p>
     *
     * @param updatedAt geht als ISO-8601-Zeichenkette hinaus, damit die
     *     Oberflaeche das Datum in der Zeitzone des Betrachters anzeigen kann
     */
    public record InterestDto(Long accountId, String characterName,
                              List<String> weekdays, List<String> timeWindows,
                              String note, Instant updatedAt) {}

    /**
     * Was die Verwaltung beim Anlegen oder Aendern schickt; {@code id == null}
     * heisst: neu.
     *
     * <p>{@code teacherRoleNames} darf leer oder {@code null} sein - dann sehen
     * die Namen der Interessenten allein die Autoren.</p>
     */
    public record SaveTopicDto(Long id, String title, String summary, String description,
                               boolean active, List<String> teacherRoleNames) {}

    /**
     * Was eine Bekundung ausmacht - und was sie ausdruecklich <b>nicht</b>
     * enthaelt.
     *
     * <p><b>Keine {@code accountId}, keine {@code characterId}.</b> Der
     * Handelnde kommt ausschliesslich aus {@code CurrentUser.characterId()},
     * also aus der Sitzung. Damit ist die ganze Fehlerklasse "vergessene
     * Pruefung, ob die ID im Rumpf zum Angemeldeten gehoert" nicht abgeschwaecht
     * - sie ist nicht vorhanden, weil es keine fremde ID gibt, die jemand
     * hereinreichen koennte. {@code AuthGroupController} sagt es woertlich: "Ein
     * Parameter dafuer waere eine Hintertuer."</p>
     *
     * <p><b>Wer diesen Datensatz spaeter um ein solches Feld erweitert, hebt die
     * Sicherheitseigenschaft auf.</b> Soll ein Ausbilder eine tote Bekundung
     * entfernen koennen, gehoert das in einen eigenen Endpunkt mit eigener
     * Pruefung und einer Logzeile, die beide IDs nennt - so wie
     * {@code AuthGroupService.removeMember} der einzige Ort mit fremder ID ist
     * und die dichteste Pruefung traegt.</p>
     */
    public record SaveInterestDto(List<String> weekdays, List<String> timeWindows, String note) {}
}

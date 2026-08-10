package com.eve.own.auth.backend.domain.industry.dto;

import java.util.List;

/**
 * Die Datensaetze, die zwischen Industrie-Endpunkten und Oberflaeche laufen.
 *
 * <p>Gebuendelt in einem Behaelter, wie es das Projekt auch bei Assets und
 * Readiness haelt: zusammengehoerige Formen an einer Stelle statt zwanzig
 * Einzeldateien mit je fuenf Zeilen.</p>
 */
public final class IndustryDtos {

    private IndustryDtos() {
        throw new AssertionError("Nur ein Behaelter fuer die Datensaetze.");
    }

    /** Ein Treffer der Produktsuche. */
    public record ProductHitDto(long typeId, String typeName, String groupName, long blueprintTypeId) {}

    /**
     * Die vier Zahlen, die vor jeder Tiefenrechnung dastehen.
     *
     * <p>Sie beantworten die Fragen, die Anfaenger als Erstes falsch einschaetzen:
     * dass fuenfzig Raven zwingend fuenf Jobs sind, und dass die fertige Ware
     * vier Frachterladungen ergibt.</p>
     *
     * @param jobCount         Anzahl noetiger Jobs
     * @param runsPerJob       Laeufe je Job
     * @param totalRuns        Laeufe insgesamt
     * @param jobSeconds       Summe aller Jobdauern - Ofenzeit, nicht Wandzeit
     * @param materialCount    wie viele verschiedene Materialien unmittelbar noetig sind
     * @param packagedVolume   verpacktes Volumen der fertigen Ware in Kubikmetern.
     *                         Als Kommazahl: Tritanium hat 0,01 m3 je Einheit, auf
     *                         ganze Kubikmeter gerundet waere das hundertfach zu viel.
     * @param blueprintFound   ob es fuer dieses Produkt ueberhaupt eine Blaupause gibt
     * @param blueprintOwned   ob das Konto eine davon besitzt. Bewusst ein eigenes Feld
     *                         und nicht aus ME und TE erschlossen: eine unerforschte
     *                         Blaupause hat ebenfalls ME 0, und "nicht erforscht" ist
     *                         etwas ganz anderes als "gar nicht vorhanden" - ohne
     *                         Blaupause laesst sich der Job ueberhaupt nicht starten.
     */
    public record PlanSummaryDto(long jobCount, long runsPerJob, long totalRuns,
                                 long jobSeconds, int materialCount, double packagedVolume,
                                 int materialEfficiency, int timeEfficiency,
                                 boolean blueprintFound, boolean blueprintOwned) {}

    /**
     * Eine Zeile der Bedarfstabelle, angereichert um den Bestand.
     *
     * @param sourceKind  MINERAL, PI, REACTION, BUILDABLE, GAS oder RAW
     * @param buildable   ob "Bauen" ueberhaupt angeboten werden darf. Bei PI-Guetern
     *                    nicht - die lassen sich per Industriejob gar nicht herstellen,
     *                    und ein Bauen-Knopf fuehrt dort in eine Sackgasse.
     * @param have        wie viel davon <b>im Bausystem</b> liegt. Ohne gewaehltes
     *                    Bausystem der gesamte Bestand - dann meint die Zahl wieder
     *                    ganz EVE, und die Oberflaeche muss das kenntlich machen.
     * @param haveElsewhere wie viel im uebrigen EVE liegt. Bewusst mitgefuehrt statt
     *                    verschwiegen: wer 32,9 Millionen Pyerite in Delve hat und in
     *                    Branch baut, soll sie nicht ein zweites Mal kaufen, sondern
     *                    zwischen Schleppen und Kaufen waehlen koennen.
     * @param missing     was fehlt - gerechnet gegen {@code have}, nicht gegen die Summe
     * @param priceMissing ob kein Referenzpreis vorliegt - muss sichtbar sein,
     *                     statt als null ISK in die Summe einzugehen
     */
    public record RequirementDto(long typeId, String typeName, long needed, long have, long missing,
                                 String sourceKind, boolean buildable, String decision,
                                 int depth, Long parentTypeId,
                                 Double unitPrice, boolean priceMissing,
                                 double packagedVolume, int onCharacters,
                                 long haveElsewhere) {}

    /** Was beim Anlegen eines Auftrags mitgeschickt wird. */
    public record CreateOrderRequest(Long productTypeId, Long quantity,
                                     Long buildLocationId, String buildLocationName,
                                     Long buildSystemId) {}

    /**
     * Der Bauort eines bestehenden Auftrags.
     *
     * <p>{@code buildLocationId} bleibt leer, wenn nur ein Sonnensystem und keine
     * bestimmte Struktur gewaehlt wurde - das ist der Regelfall, solange keine
     * Corp-Strukturen eingelesen sind.</p>
     */
    public record BuildLocationRequest(Long buildSystemId, Long buildLocationId,
                                       String buildLocationName) {}

    /**
     * Die Vorschau: alles, was ohne angelegten Auftrag schon feststeht.
     *
     * <p>Bewusst getrennt vom Auftrag - man soll durchrechnen duerfen, ohne
     * gleich etwas anzulegen.</p>
     */
    public record PlanPreviewDto(long productTypeId, String productName,
                                 long quantity, PlanSummaryDto summary,
                                 List<RequirementDto> requirements) {}

    /**
     * Der Fortschritt eines Auftrags.
     *
     * @param delivered    fertig gelieferte Stueck, aus dem Jobbuch und nicht aus den Hangars
     * @param inProgress   Stueck, die gerade in einem laufenden Job stecken
     * @param percent      Anteil in Prozent, auf ganze Zahlen gerundet
     * @param coveredUnits fuer wie viele Endprodukte das vorhandene Material reicht.
     *                     Eine Bestandsaussage, kein Fortschritt - sie darf sinken.
     */
    public record ProgressDto(long target, long delivered, long inProgress, int percent,
                              long coveredUnits, int openJobs) {}

    /** Ein Auftrag in der Uebersicht. */
    public record OrderSummaryDto(long id, long productTypeId, String productName,
                                 long targetQuantity, String status,
                                 String buildLocationName, ProgressDto progress,
                                 String createdAt) {}

    /** Ein Auftrag mit allem, was der Arbeitsbildschirm braucht. */
    public record OrderDetailDto(OrderSummaryDto order, PlanSummaryDto summary,
                                 List<RequirementDto> requirements,
                                 List<JobDto> jobs) {}

    /** Ein Industriejob, wie ihn die Oberflaeche zeigt. */
    public record JobDto(long jobId, String activityLabel, Long productTypeId, String productName,
                         int runs, String status, String endDate, boolean assignedToOrder) {}

    /** Die Umstellung einer Kaufen/Bauen-Entscheidung. */
    public record DecisionRequest(Long typeId, String decision) {}

    /**
     * Eine Zeile der Einkaufsliste.
     *
     * @param source       DIRECT oder ORE - woher das Material kommen soll
     * @param buyTypeId    was tatsaechlich gekauft wird; bei ORE das Erz, nicht das Mineral
     * @param buyQuantity  wie viele Einheiten davon
     * @param totalCost    Ware plus Transport. Der Vergleich laeuft ueber diese Zahl,
     *                     nicht ueber den Einkaufspreis: bei Mineralien liegt der
     *                     Transport regelmaessig ueber dem Warenwert.
     * @param alternative  was der andere Weg gekostet haette, {@code null} wenn es keinen gibt
     * @param saving       wie viel der gewaehlte Weg spart
     * @param note         Hinweis, etwa auf nicht gegengerechnete Nebenprodukte
     */
    public record ProcurementLineDto(long typeId, String typeName, long neededQuantity,
                                     String source, Long buyTypeId, String buyTypeName,
                                     long buyQuantity,
                                     Double purchaseCost, double volume, Double totalCost,
                                     Double alternative, double saving, String note) {}

    /**
     * Die ganze Einkaufsliste samt Weg.
     *
     * @param jumpsFromJita  Sprungentfernung, {@code null} wenn kein Bauort gewaehlt
     *                       ist <em>oder</em> er ueber Tore nicht erreichbar ist
     * @param locationChosen ob ueberhaupt ein Bauort feststeht. Trennt die beiden
     *                       Faelle: "noch nicht gewaehlt" ist etwas anderes als
     *                       "nicht erreichbar", und nur das Zweite ist ein Problem.
     * @param loads         wie viele Ladungen noetig sind - eine halbe Fahrt gibt es nicht
     * @param withoutPrice  wie viele Zeilen keinen Marktpreis haben. Muss sichtbar sein,
     *                      sonst liest sich eine unvollstaendige Summe wie eine vollstaendige.
     */
    /**
     * Die ganze Einkaufsliste.
     *
     * @param oreVerdict warum kein Erz auf der Liste steht - oder warum doch.
     *                   Ohne diesen Satz trifft der Assistent die Entscheidung
     *                   unsichtbar, und wer Erz erwartet, haelt das Fehlen fuer
     *                   einen Fehler statt fuer ein Ergebnis.
     * @param oreFactor  wie nah das beste Erz an die Rentabilitaetsschwelle kommt.
     *                   1,0 heisst gleichauf, darunter verliert es. Als Zahl, damit
     *                   "knapp daneben" von "voellig aussichtslos" zu unterscheiden ist.
     */
    public record ProcurementDto(Integer jumpsFromJita, boolean locationChosen,
                                 String transport, String transportLabel,
                                 double freightPerCubicMeter, long loadCapacity,
                                 double goodsCost, double freightCost, double totalCost,
                                 long volume, long loads, int withoutPrice,
                                 List<ProcurementLineDto> lines,
                                 String oreVerdict, Double oreFactor) {}

    /**
     * Die Lage zu einer Blaupause, die ein Auftrag braucht.
     *
     * @param neededRuns    wie viele Laeufe der Auftrag verlangt
     * @param availableRuns wie viele vorhanden sind; {@code -1} bedeutet ein Original,
     *                      also unbegrenzt. Null heisst: keine Blaupause da.
     * @param owned         ob ueberhaupt eine im Kontoverbund liegt
     * @param sufficient    ob die Laeufe reichen. Der Haken in der Oberflaeche.
     * @param required      ob dieser Teil tatsaechlich gebaut werden soll. Steht er auf
     *                      "Kaufen", fehlt die Blaupause zwar, aber es ist kein Mangel -
     *                      die Zeile ist dann eine Auskunft fuer den Fall, dass man es
     *                      doch selbst machen will.
     * @param kind          "Blaupause" oder "Reaktionsformel" - ingame heissen sie
     *                      verschieden, und wer eine Formel unter "Blueprint" sucht,
     *                      findet sie nicht.
     */
    public record BlueprintCheckDto(long productTypeId, String productName, long blueprintTypeId,
                                    long neededRuns, long availableRuns,
                                    boolean owned, boolean sufficient,
                                    int materialEfficiency, int timeEfficiency,
                                    boolean required, String kind,
                                    String note) {}

    /**
     * Ein moeglicher Bauort.
     *
     * @param servicesKnown ob die Dienste ueberhaupt bekannt sind. Bei fremden
     *                      Strukturen nicht - dann bedeuten die drei Flaggen
     *                      darunter "unbekannt" und nicht "nein". Die Oberflaeche
     *                      sagt das ausdruecklich, statt zu raten.
     * @param hints         was sich hier anfangen laesst, aus dem Strukturtyp
     *                      abgeleitet
     */
    public record LocationDto(long structureId, String name, String systemName, Long systemId,
                              Double security, String typeName, String source,
                              boolean servicesKnown,
                              boolean manufacturing, boolean reprocessing, boolean reactions,
                              List<String> hints) {}
}

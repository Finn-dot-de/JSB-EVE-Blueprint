package com.eve.own.auth.backend.domain.character.dto;

import java.util.List;

/** Die Antworttypen der Charakter- und Account-Endpunkte. */
public final class CharacterDtos {

    private CharacterDtos() {
        throw new AssertionError("Nur ein Namensraum fuer die Antworttypen.");
    }

    /**
     * Ein Charakter eines Accounts.
     *
     * <p>Ersetzt die frueheren, feldgleichen Typen {@code AltDto} und
     * {@code AuthedAltDto} - zwei Namen fuer dieselbe Antwort.</p>
     */
    public record CharacterRefDto(Long id, String name, String portraitUrl, boolean isMain) {}

    /** Ein Account in der Corp-Statistik: Main samt der Alts in dieser Corporation. */
    public record AuthedMainDto(Long mainId, String mainName, String portraitUrl,
                                List<CharacterRefDto> alts) {}

    /** Ein Corp-Mitglied, das sich hier nie angemeldet hat. */
    public record UnauthedCharDto(Long id, String name, String portraitUrl) {}

    /**
     * Die Mitglieder-Bilanz einer Corporation.
     *
     * @param totalEsiMembers      Mitglieder laut ESI, also inklusive der nicht registrierten
     * @param registeredMains      hier bekannte Accounts mit mindestens einem Charakter in der Corp
     * @param registeredAlts       registrierte Charaktere, die nicht Main ihres Accounts sind
     * @param totalRegisteredChars alle hier bekannten Charaktere der Corporation
     */
    public record CorpStatsDto(Long corpId, String corpName, int totalEsiMembers,
                               int registeredMains, int registeredAlts, int totalRegisteredChars,
                               List<AuthedMainDto> authedMembers,
                               List<UnauthedCharDto> unauthedMembers) {}

    /** Ein Charakter in der Account-Uebersicht der Administration. */
    public record AdminAccountCharDto(Long id, String name, String portraitUrl, String corporationName) {}

    public record AdminAccountDto(Long mainId, String mainName, String portraitUrl,
                                  String corporationName, List<AdminAccountCharDto> alts) {}

    /**
     * Ein einzelnes Signal eines Alt-Vorschlags, mitsamt der Auskunft, ob es
     * ueberhaupt gemessen werden konnte.
     *
     * <p><b>{@code available == false} heisst "nicht gemessen" und niemals
     * "gemessen und nichts gefunden".</b> Der Unterschied ist der ganze Punkt
     * dieses Typs: ein Charakter ohne Mining-Zeilen hat keine gepruefte
     * Unaehnlichkeit - er hat gar keine Pruefung. Fliesst so etwas als 0 in den
     * Score ein, kommt eine niedrige Wahrscheinlichkeit heraus, die wie ein
     * Freispruch aussieht, obwohl nichts geschehen ist.</p>
     *
     * @param signal        technischer Name, z.B. {@code NAME}, {@code JOIN}, {@code MINING}
     * @param label         die Beschriftung fuer die Oberflaeche
     * @param available     ob es zu diesem Signal ueberhaupt Daten gab
     * @param score         der Einzelwert 0..100, {@code null} wenn nicht verfuegbar
     * @param weightPercent das Gewicht dieses Signals am Gesamtwert
     * @param detail        warum das Signal fehlt oder woraus sich sein Wert speist
     */
    public record AltSignalDto(String signal, String label, boolean available,
                               Integer score, int weightPercent, String detail) {}

    /**
     * Ein Verdacht: dieser nicht registrierte Charakter koennte zu diesem Konto
     * gehoeren.
     *
     * <p>Die {@code probability} ist eine gewichtete Summe von Heuristiken und
     * <b>keine geeichte Wahrscheinlichkeit</b>. Deshalb wandert die
     * Aufschluesselung mit: eine 94 aus drei Signalen und eine 94 aus einem
     * einzigen sind voellig verschiedene Aussagen, und nur die Aufschluesselung
     * sagt, welche von beiden vorliegt.</p>
     *
     * @param signalsUsed  wieviele Signale tatsaechlich Daten hatten
     * @param signalsTotal wieviele Signale es insgesamt gibt
     * @param corpId       die Corporation, aus deren Mitgliederliste der Verdacht
     *                     stammt - und ausdruecklich nicht die des Mains: der kann
     *                     in einer anderen sitzen
     */
    public record AltSuggestionDto(Long unauthedCharId, String unauthedCharName,
                                   Long mainId, String mainName,
                                   int probability,
                                   int signalsUsed, int signalsTotal,
                                   List<AltSignalDto> signals,
                                   Long corpId) {}

    /**
     * Eine Gruppe nicht registrierter Charaktere, die vermutlich <em>ein</em>
     * Mensch sind - ohne dass ein bekanntes Konto dazugehoert.
     *
     * <p><b>Das ist eine Beobachtung und keine Handlung.</b> Es gibt kein Konto,
     * dem sich diese Gruppe zuordnen liesse, also gibt es dazu auch keinen
     * Bestaetigungsweg und keine Schaltflaeche. Wer hier eine anbietet,
     * suggeriert, das Programm habe die Sache erledigt - es hat nur etwas
     * bemerkt.</p>
     *
     * @param members      alle Charaktere der Gruppe, nach Namen sortiert
     * @param probability  der Wert der <b>schwaechsten</b> Verbindung in der
     *                     Gruppe und ausdruecklich nicht der Mittelwert: eine
     *                     Gruppe ist nur so belastbar wie ihr duennstes Paar
     * @param signals      die Aufschluesselung eben dieser schwaechsten Verbindung
     * @param note         der Klartext dazu, was die Zahl bedeutet und was der
     *                     naechste Schritt ist - er liegt ausserhalb dieses Programms
     */
    public record AltGroupDto(Long corpId, List<UnauthedCharDto> members,
                              int probability, int signalsUsed, int signalsTotal,
                              List<AltSignalDto> signals, String note) {}

    /**
     * Ein bewertetes Paar zweier nicht registrierter Charaktere, wie es die
     * Kalibrieransicht zeigt - auch <b>unterhalb</b> der Schwelle.
     *
     * @param requiredThreshold die Schwelle, die genau dieses Paar haette
     *                          nehmen muessen. Sie haengt an der Zahl tragender
     *                          Signale und ist deshalb nicht fuer alle Zeilen
     *                          dieselbe; ohne sie waere die Spalte
     *                          {@code aboveThreshold} nicht nachvollziehbar.
     * @param aboveThreshold    ob das Paar eine Gruppenkante begruenden wuerde
     */
    public record AltPairDto(Long leftId, String leftName, Long rightId, String rightName,
                             Long corpId, int probability, int signalsUsed, int signalsTotal,
                             List<AltSignalDto> signals,
                             int requiredThreshold, boolean aboveThreshold) {}

    /** Ein Kontopaar der Kalibrieransicht samt der Schwelle, die fuer es gilt. */
    public record AltCalibrationEntryDto(AltSuggestionDto suggestion,
                                         int requiredThreshold, boolean aboveThreshold) {}

    /**
     * Ein Signal, wie es die Kalibrieransicht zeigt: sein Gewicht und wie oft es
     * ueberhaupt Daten hatte.
     *
     * <p><b>Ohne diese Zeile kann niemand ein Signal einstellen.</b> Ein
     * Director, der das Gewicht eines Signals verstellt und danach keine
     * Aenderung sieht, hat zwei ununterscheidbare Ursachen vor sich: das Gewicht
     * wirkt nicht, oder das Signal hatte in keinem einzigen Paar Daten. Genau
     * das trennt {@code availableInPairs}. Bei den neuen Quellen ist der zweite
     * Fall der Regelfall, solange die Erfassung erst wenige Tage laeuft - und
     * eine Null in dieser Spalte ist die einzige ehrliche Auskunft darueber.</p>
     *
     * @param signal           technischer Name, z.B. {@code ISK} oder {@code PRESENCE}
     * @param label            die Beschriftung fuer die Oberflaeche
     * @param weightPercent    das eingestellte Gewicht
     * @param availableInPairs in wievielen der gerechneten Paare dieses Signal
     *                         Daten hatte
     * @param examinedPairs    wieviele Paare insgesamt gerechnet wurden - der
     *                         Nenner dazu, denn "0" heisst ohne ihn nichts
     */
    public record AltSignalConfigDto(String signal, String label, int weightPercent,
                                     int availableInPairs, int examinedPairs) {}

    /**
     * Die Kalibrieransicht: was der Scorer denkt, bevor die Schwelle filtert.
     *
     * <p>Eine leere Vorschlagsliste hat zwei ununterscheidbare Ursachen - der
     * Scorer findet nichts, oder er laeuft nicht. Diese Antwort trennt sie:
     * {@code examinedAccountPairs} sagt, wieviel ueberhaupt gerechnet wurde, und
     * die Listen zeigen, wie knapp es darunter zugeht. <b>Hier wird nichts
     * bestaetigt</b> - kein Feld dieser Antwort fuehrt zu einer Zuordnung oder
     * einer Vormerkung.</p>
     *
     * @param limit                     wieviele Zeilen je Liste tatsaechlich geliefert wurden
     * @param maxLimit                  die harte Obergrenze gegen einen Vollabzug
     * @param examinedAccountPairs      gerechnete Paare "unregistriert gegen Konto"
     * @param examinedUnregisteredPairs gerechnete Paare "unregistriert gegen unregistriert"
     * @param minProbability            die geltende Schwelle bei mehreren Signalen
     * @param minProbabilitySingleSignal die hoehere Schwelle, wenn nur eines traegt
     * @param minAvailableSignals       wieviele Signale ueberhaupt vorliegen muessen
     * @param signalConfig              jedes Signal mit seinem Gewicht und der
     *                                  Auskunft, in wievielen der gerechneten
     *                                  Paare es ueberhaupt Daten hatte. Ohne
     *                                  diese Liste laesst sich ein Signal nicht
     *                                  einstellen: ein Gewicht zu verstellen und
     *                                  nichts zu sehen kann heissen, dass das
     *                                  Gewicht nicht wirkt - oder dass das Signal
     *                                  in keinem Paar Daten hatte.
     */
    public record AltCalibrationDto(int limit, int maxLimit,
                                    int examinedAccountPairs, int examinedUnregisteredPairs,
                                    int minProbability, int minProbabilitySingleSignal,
                                    int minAvailableSignals,
                                    List<AltSignalConfigDto> signalConfig,
                                    List<AltCalibrationEntryDto> accountPairs,
                                    List<AltPairDto> unregisteredPairs) {}

    /** Die Anfrage des Directors: diesen Charakter diesem Konto zuordnen. */
    public record AltLinkRequest(Long unauthedCharId, Long mainId) {}

    /**
     * Das Ergebnis einer bestaetigten Zuordnung.
     *
     * <p>{@code linked} ist bewusst {@code false}: bestaetigt wird eine
     * <em>Vormerkung</em>, nicht die Zuordnung selbst. Warum, steht in
     * {@link com.eve.own.auth.backend.domain.character.entity.AltLinkProposal}.
     * Ohne dieses Feld liesse die Antwort den Director glauben, der Charakter
     * haenge jetzt am Konto - er tut es nicht.</p>
     *
     * @param linked  ob {@code characters.main_character_id} geschrieben wurde
     * @param message der Klartext fuer den Nutzer, inklusive des naechsten Schritts
     */
    public record AltLinkResultDto(Long unauthedCharId, String unauthedCharName,
                                   Long mainId, String mainName,
                                   int probability, boolean linked, String message) {}

    /**
     * Ein Charakter, dessen Anmeldung abgelaufen ist.
     *
     * @param invalidSince seit wann - der Zeitpunkt des ERSTEN Fehlschlags,
     *                     damit "seit gestern" von "seit gerade eben" zu
     *                     unterscheiden ist
     */
    public record TokenHealthDto(Long characterId, String name,
                                 String invalidSince, String reason) {}
}

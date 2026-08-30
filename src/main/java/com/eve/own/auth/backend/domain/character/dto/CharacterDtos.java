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

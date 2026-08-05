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
}

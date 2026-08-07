package com.eve.own.auth.backend.domain.fleet.dto;

import java.util.List;

/**
 * Die Datensaetze des Readiness-Boards.
 *
 * <p>Geprueft wird der <em>Fit</em>, nicht die Huelle. Der Unterschied ist
 * bedeutend: die Skills eines Schiffs sagen nur, ob jemand es bewegen kann.
 * Ob er die verbauten Module auch einschalten kann, steht in deren eigenen
 * Voraussetzungen - ein Pilot ohne Heavy Assault Missile Specialization
 * fliegt den Rumpf einwandfrei und bekommt trotzdem keinen Schuss ab.</p>
 *
 * <p>Deshalb ist die Einheit hier der Fit: zwei Fits derselben Huelle sind
 * zwei verschiedene Anforderungen und stehen getrennt im Board.</p>
 */
public class ReadinessDtos {

    public record RequiredSkillDto(Long skillTypeId, String skillName, int level) {}

    public record MissingSkillDto(Long skillTypeId, String skillName, int requiredLevel, int currentLevel) {}

    // === Die kombinierte Matrix ===

    /**
     * Ein Charakter gegen einen Fit.
     *
     * @param owned wie viele Exemplare der Huelle im Hangar liegen
     * @param canFly Rumpf <em>und</em> alle Module bedienbar
     * @param canFlyHull nur der Rumpf bedienbar - trennt "kann das Schiff gar
     *     nicht fliegen" von "es fehlen bloss ein paar Modul-Skills"
     * @param missingSkills alle Luecken des gesamten Fits, hoechste Anforderung je Skill
     * @param fullySkilled zusaetzlich zum Fit auch der hinterlegte Skillplan erfuellt
     * @param missingPlanSkills was zum Skillplan noch fehlt - getrennt gefuehrt,
     *     weil es die Einsatzfaehigkeit nicht verhindert, sondern die Leistung begrenzt
     */
    public record CharacterReadinessDto(
            Long characterId, String characterName, String portraitUrl, boolean main,
            long owned, boolean skillDataAvailable, boolean canFly, boolean canFlyHull,
            int skillsMet, int skillsRequired,
            List<MissingSkillDto> missingSkills,
            boolean fullySkilled, List<MissingSkillDto> missingPlanSkills
    ) {}

    /**
     * Ein Account gegen einen Fit.
     *
     * @param isReady ein und derselbe Charakter hat die Huelle und kann den Fit fliegen
     * @param fullyReady dieser Charakter erfuellt zusaetzlich den Skillplan
     */
    public record AccountReadinessDto(
            Long mainId, String mainName, String portraitUrl, String corporationName,
            long owned, int charactersOwning,
            boolean canFly, int pilotsCapable, boolean skillDataAvailable, int bestSkillsMet, int skillsRequired,
            boolean hasShip, boolean hasSkills, boolean isReady,
            boolean fullyReady, int pilotsFullySkilled,
            List<CharacterReadinessDto> characters
    ) {}

    /**
     * Ein Fit und wer ihn stellen kann.
     *
     * @param moduleCount Anzahl verbauter Module, Drohnen und Ladung
     * @param requiredSkills die Vereinigung ueber Rumpf und alle Module
     * @param hullSkillsRequired davon der Anteil, der allein auf den Rumpf entfaellt
     * @param unresolved Eintraege des EFT-Texts, die die Stammdaten nicht kannten -
     *     sie konnten nicht geprueft werden und fehlen im Ergebnis
     * @param planNames die an diesem Fitting haengenden Skillplaene
     * @param planSkills was diese Plaene zusaetzlich verlangen, hoechste Stufe je Skill
     * @param accountsFullyReady wie viele Accounts auch den Skillplan erfuellen
     */
    public record FitReadinessDto(
            Long fitId, String fitName,
            Long typeId, String typeName, String iconUrl, String renderUrl,
            int moduleCount,
            List<RequiredSkillDto> requiredSkills, int hullSkillsRequired,
            List<String> unresolved,
            List<String> planNames, List<RequiredSkillDto> planSkills,
            long hullsTotal, int accountsReady, int accountsFullyReady,
            int accountsTotal, double coverage,
            List<AccountReadinessDto> ready,
            List<AccountReadinessDto> notReady
    ) {}

    public record DoctrineReadinessDto(
            String doctrineName, int accountsTotal, int fitsChecked, List<FitReadinessDto> fits
    ) {}

    // --- Selbstauskunft eines Mitglieds ---

    /**
     * Ein Fitting aus Sicht des eigenen Accounts.
     *
     * <p>Bewusst schmaler als {@link FitReadinessDto}: hier geht es nicht um
     * die Mannschaft, sondern um die eine Frage "kann ich das fliegen?".</p>
     *
     * @param bestCharacterName der Charakter, an dem die Auskunft haengt - der
     *     mit den besten Aussichten auf dieses Fitting
     * @param missingSkills was diesem Charakter fehlt, um den Fit zu bedienen
     * @param missingPlanSkills was ihm darueber hinaus zum Skillplan fehlt
     */
    public record MyFitDto(
            Long fitId, String fitName, String doctrineName,
            Long typeId, String typeName, String iconUrl, String renderUrl,
            int moduleCount, List<String> planNames,
            boolean hasShip, long owned,
            boolean canFly, boolean fullySkilled, boolean skillDataAvailable,
            String bestCharacterName,
            List<MissingSkillDto> missingSkills,
            List<MissingSkillDto> missingPlanSkills
    ) {}

    // --- EFT-Sandbox ---

    public record FitModuleDto(
            Long typeId, String typeName, String iconUrl, int quantity,
            String chargeName, Long chargeTypeId
    ) {}

    public record FitSlotGroupDto(String name, String icon, int moduleCount, List<FitModuleDto> modules) {}

    public record ParsedFitDto(
            Long shipTypeId, String shipTypeName, String fitName, String iconUrl, String renderUrl,
            int moduleCount, List<FitSlotGroupDto> groups, List<String> unresolved
    ) {}

    public record SandboxRequest(String eftString) {}

    public record SandboxResultDto(
            ParsedFitDto fit,
            FitReadinessDto board
    ) {}
}

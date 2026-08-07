package com.eve.own.auth.backend.domain.fleet.dto;

import java.util.List;

/** Die Datensaetze rund um die Skillplaene. */
public class SkillPlanDtos {

    /** Ein Skill auf einer geforderten Stufe. */
    public record SkillEntryDto(Long skillTypeId, String skillName, int level) {}

    /**
     * Ein benannter Plan.
     *
     * @param usedByFittings an wie vielen Fittings er haengt - eine Warnung
     *     davor, ihn beilaeufig zu aendern oder zu loeschen
     */
    public record SkillPlanDto(Long id, String name, String description,
                               List<SkillEntryDto> skills, int usedByFittings) {}

    public record SaveSkillPlanDto(Long id, String name, String description,
                                   List<SkillEntryDto> skills) {}

    /** Ein Treffer der Skill-Suche. */
    public record SkillOptionDto(Long typeId, String typeName) {}

    /**
     * Das Ergebnis eines eingefuegten Plantexts.
     *
     * @param unresolved Zeilen, zu denen kein Skill gefunden wurde
     */
    public record ImportResultDto(List<SkillEntryDto> skills, List<String> unresolved) {}

    public record ImportRequestDto(String planText) {}

    /** Welche Plaene an einem Fitting haengen sollen. */
    public record AssignPlansDto(List<Long> planIds) {}
}

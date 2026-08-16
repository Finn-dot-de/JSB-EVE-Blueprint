package com.eve.own.auth.backend.domain.navigation.dto;

import java.util.List;

/** Die Datensaetze der Navigation - Anzeige wie Pflege. */
public class NavigationDtos {

    // --- Was die Seitenleiste bekommt ---

    /** Ein Punkt innerhalb eines Registers. */
    public record MenuItemDto(String label, String url, String icon, boolean external) {}

    /**
     * Ein Eintrag der obersten Ebene.
     *
     * <p>Entweder ein Register - dann traegt {@code children} die Punkte und
     * {@code url} ist leer - oder ein einzelner Punkt ohne Register.</p>
     */
    public record MenuEntryDto(String label, String icon, String url, boolean external,
                               List<MenuItemDto> children) {}

    // --- Was die Verwaltung bekommt ---

    public record CategoryDto(Long id, String name, String icon, int sortOrder, int linkCount) {}

    public record LinkDto(Long id, String label, String url, String icon,
                          Long categoryId, String requiredRole, boolean active, int sortOrder) {}

    /** Der vollstaendige Stand fuer die Verwaltung - auch die abgeschalteten Eintraege. */
    public record AdminViewDto(List<CategoryDto> categories, List<LinkDto> links) {}

    public record SaveCategoryDto(Long id, String name, String icon) {}

    public record SaveLinkDto(Long id, String label, String url, String icon,
                              Long categoryId, String requiredRole, Boolean active) {}

    /** Womit ein Eintrag verschoben wird. */
    public enum MoveKind { LINK, CATEGORY }

    public enum MoveDirection { UP, DOWN }

    public record MoveDto(MoveKind kind, Long id, MoveDirection direction) {}
}

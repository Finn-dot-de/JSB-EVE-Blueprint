package com.eve.own.auth.backend.domain.navigation.service;

import com.eve.own.auth.backend.domain.navigation.dto.NavigationDtos;
import com.eve.own.auth.backend.domain.navigation.entity.NavigationCategory;
import com.eve.own.auth.backend.domain.navigation.entity.NavigationLink;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationCategoryRepository;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationLinkRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Baut die Seitenleiste und pflegt ihre Eintraege.
 *
 * <p>Die Reihenfolge kommt aus der Datenbank, nicht mehr aus einer Namensliste
 * im Frontend. Nur so hat das Verschieben in der Verwaltung ueberhaupt eine
 * Wirkung - und nur so kann ein neu angelegtes Register irgendwo anders landen
 * als ganz unten.</p>
 */
@Service
public class NavigationService {

    /** Externe Ziele erkennt man an ihrem Schema, nicht an einer Kennzeichnung. */
    private static final String EXTERNAL_PREFIX = "http";

    /** Abstand zwischen den Positionen, damit zwischen zwei Eintraegen Platz bleibt. */
    private static final int ORDER_STEP = 10;

    private final NavigationLinkRepository linkRepo;
    private final NavigationCategoryRepository categoryRepo;

    public NavigationService(NavigationLinkRepository linkRepo,
                             NavigationCategoryRepository categoryRepo) {
        this.linkRepo = linkRepo;
        this.categoryRepo = categoryRepo;
    }

    // ==================================================================
    // Was die Seitenleiste sieht
    // ==================================================================

    /**
     * Das Menue, wie es dieser Nutzer sehen darf - fertig sortiert.
     *
     * @param reachableRoles die Rollen des Nutzers samt Hierarchie
     */
    @Transactional(readOnly = true)
    public List<NavigationDtos.MenuEntryDto> menuFor(Set<String> reachableRoles) {
        List<NavigationLink> visible = linkRepo.findAll().stream()
                .filter(link -> Boolean.TRUE.equals(link.getActive()))
                .filter(link -> link.getRequiredRole() == null
                        || reachableRoles.contains(link.getRequiredRole()))
                .sorted(Comparator.comparingInt(NavigationService::linkOrder))
                .toList();

        Map<Long, List<NavigationDtos.MenuItemDto>> byCategory = new LinkedHashMap<>();
        List<NavigationLink> rootLinks = new ArrayList<>();
        for (NavigationLink link : visible) {
            if (link.getCategoryId() == null) {
                rootLinks.add(link);
            } else {
                byCategory.computeIfAbsent(link.getCategoryId(), key -> new ArrayList<>())
                        .add(new NavigationDtos.MenuItemDto(
                                link.getLabel(), link.getUrl(), link.getIcon(), isExternal(link)));
            }
        }

        // Register und einzelne Punkte teilen sich die oberste Ebene und
        // damit auch deren Reihenfolge.
        record TopLevel(int order, NavigationDtos.MenuEntryDto entry) {}
        List<TopLevel> top = new ArrayList<>();

        for (NavigationCategory category : categoryRepo.findAll()) {
            List<NavigationDtos.MenuItemDto> children = byCategory.get(category.getId());
            // Ein Register ohne sichtbare Punkte bleibt weg - ein leerer Ordner
            // waere fuer den Nutzer nur ein Klick ins Nichts.
            if (children == null || children.isEmpty()) {
                continue;
            }
            top.add(new TopLevel(categoryOrder(category), new NavigationDtos.MenuEntryDto(
                    category.getName(), category.getIcon(), null, false, children)));
        }

        for (NavigationLink link : rootLinks) {
            top.add(new TopLevel(linkOrder(link), new NavigationDtos.MenuEntryDto(
                    link.getLabel(), link.getIcon(), link.getUrl(), isExternal(link), List.of())));
        }

        return top.stream()
                .sorted(Comparator.comparingInt(TopLevel::order)
                        .thenComparing(entry -> entry.entry().label(), String.CASE_INSENSITIVE_ORDER))
                .map(TopLevel::entry)
                .toList();
    }

    // ==================================================================
    // Verwaltung
    // ==================================================================

    @Transactional(readOnly = true)
    public NavigationDtos.AdminViewDto adminView() {
        List<NavigationLink> links = linkRepo.findAll();
        Map<Long, Integer> linkCounts = new LinkedHashMap<>();
        for (NavigationLink link : links) {
            if (link.getCategoryId() != null) {
                linkCounts.merge(link.getCategoryId(), 1, Integer::sum);
            }
        }

        List<NavigationDtos.CategoryDto> categories = categoryRepo.findAll().stream()
                .sorted(Comparator.comparingInt(NavigationService::categoryOrder))
                .map(category -> new NavigationDtos.CategoryDto(
                        category.getId(), category.getName(), category.getIcon(),
                        categoryOrder(category), linkCounts.getOrDefault(category.getId(), 0)))
                .toList();

        List<NavigationDtos.LinkDto> linkDtos = links.stream()
                .sorted(Comparator.comparingInt(NavigationService::linkOrder)
                        .thenComparing(NavigationLink::getLabel, String.CASE_INSENSITIVE_ORDER))
                .map(NavigationService::toDto)
                .toList();

        return new NavigationDtos.AdminViewDto(categories, linkDtos);
    }

    /**
     * Legt ein Register an oder aendert es.
     *
     * @throws IllegalArgumentException bei leerem oder bereits vergebenem Namen
     */
    @Transactional
    public NavigationDtos.CategoryDto saveCategory(NavigationDtos.SaveCategoryDto dto) {
        String name = trimmed(dto.name());
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Das Register braucht einen Namen.");
        }

        Optional<NavigationCategory> sameName = categoryRepo.findByNameIgnoreCase(name);
        if (sameName.isPresent() && !sameName.get().getId().equals(dto.id())) {
            throw new IllegalArgumentException("Ein Register namens \"" + name + "\" gibt es schon.");
        }

        NavigationCategory category = dto.id() == null
                ? newCategory()
                : categoryRepo.findById(dto.id()).orElseThrow(
                        () -> new IllegalArgumentException("Register " + dto.id() + " ist unbekannt."));
        category.setName(name);
        category.setIcon(blankToDefault(dto.icon()));

        NavigationCategory saved = categoryRepo.save(category);
        return new NavigationDtos.CategoryDto(saved.getId(), saved.getName(), saved.getIcon(),
                categoryOrder(saved), linkRepo.findByCategoryId(saved.getId()).size());
    }

    /**
     * Loescht ein Register.
     *
     * <p>Die enthaltenen Punkte wandern in die oberste Ebene, statt mit zu
     * verschwinden: ein versehentlich geloeschtes Register darf keine
     * Menuepunkte mitreissen.</p>
     */
    @Transactional
    public void deleteCategory(Long categoryId) {
        NavigationCategory category = categoryRepo.findById(categoryId).orElseThrow(
                () -> new IllegalArgumentException("Register " + categoryId + " ist unbekannt."));

        int position = nextTopLevelOrder();
        for (NavigationLink link : linkRepo.findByCategoryId(categoryId)) {
            link.setCategoryId(null);
            link.setSortOrder(position);
            linkRepo.save(link);
            position += ORDER_STEP;
        }
        categoryRepo.delete(category);
    }

    /**
     * Legt einen Menuepunkt an oder aendert ihn.
     *
     * @throws IllegalArgumentException bei fehlender Beschriftung, fehlendem
     *     Ziel oder unbekanntem Register
     */
    @Transactional
    public NavigationDtos.LinkDto saveLink(NavigationDtos.SaveLinkDto dto) {
        String label = trimmed(dto.label());
        String url = trimmed(dto.url());
        if (label.isEmpty()) {
            throw new IllegalArgumentException("Der Menuepunkt braucht eine Beschriftung.");
        }
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Der Menuepunkt braucht ein Ziel.");
        }
        if (dto.categoryId() != null && !categoryRepo.existsById(dto.categoryId())) {
            throw new IllegalArgumentException("Register " + dto.categoryId() + " ist unbekannt.");
        }

        NavigationLink link = dto.id() == null
                ? new NavigationLink()
                : linkRepo.findById(dto.id()).orElseThrow(
                        () -> new IllegalArgumentException("Menuepunkt " + dto.id() + " ist unbekannt."));

        // Wechselt der Punkt das Register, kommt er dort ans Ende - seine alte
        // Position gilt in der neuen Umgebung nichts.
        boolean movedElsewhere = link.getId() == null
                || !java.util.Objects.equals(link.getCategoryId(), dto.categoryId());

        link.setLabel(label);
        link.setUrl(url);
        link.setIcon(blankToNull(dto.icon()));
        link.setCategoryId(dto.categoryId());
        link.setRequiredRole(blankToNull(dto.requiredRole()));
        link.setActive(dto.active() == null || dto.active());
        if (movedElsewhere) {
            link.setSortOrder(nextOrderIn(dto.categoryId()));
        }

        return toDto(linkRepo.save(link));
    }

    @Transactional
    public void deleteLink(Long linkId) {
        if (!linkRepo.existsById(linkId)) {
            throw new IllegalArgumentException("Menuepunkt " + linkId + " ist unbekannt.");
        }
        linkRepo.deleteById(linkId);
    }

    /**
     * Verschiebt einen Eintrag um eine Position.
     *
     * <p>Getauscht wird mit dem Nachbarn <em>derselben Ebene</em>: in der
     * obersten Ebene stehen Register und einzelne Punkte gemeinsam, innerhalb
     * eines Registers nur dessen Punkte. Ein Tausch statt einer Neuvergabe
     * aller Positionen, weil er genau zwei Zeilen anfasst und sich nicht mit
     * einem zweiten Klick ins Gehege kommt.</p>
     */
    @Transactional
    public void move(NavigationDtos.MoveDto dto) {
        if (dto.kind() == NavigationDtos.MoveKind.CATEGORY) {
            moveWithinTopLevel(dto.id(), true, dto.direction());
            return;
        }

        NavigationLink link = linkRepo.findById(dto.id()).orElseThrow(
                () -> new IllegalArgumentException("Menuepunkt " + dto.id() + " ist unbekannt."));
        if (link.getCategoryId() == null) {
            moveWithinTopLevel(dto.id(), false, dto.direction());
        } else {
            moveWithinCategory(link, dto.direction());
        }
    }

    // ==================================================================
    // Interna
    // ==================================================================

    /** Ein Eintrag der obersten Ebene - Register oder einzelner Punkt. */
    private record TopEntry(boolean category, Long id, int order) {}

    private void moveWithinTopLevel(Long id, boolean isCategory,
                                    NavigationDtos.MoveDirection direction) {
        List<TopEntry> entries = new ArrayList<>();
        categoryRepo.findAll().forEach(category ->
                entries.add(new TopEntry(true, category.getId(), categoryOrder(category))));
        linkRepo.findAll().stream()
                .filter(link -> link.getCategoryId() == null)
                .forEach(link -> entries.add(new TopEntry(false, link.getId(), linkOrder(link))));
        entries.sort(Comparator.comparingInt(TopEntry::order));

        int index = indexOf(entries, entry -> entry.category() == isCategory && entry.id().equals(id));
        int neighbour = neighbourOf(entries.size(), index, direction);
        if (neighbour < 0) {
            return;
        }

        TopEntry moved = entries.get(index);
        TopEntry other = entries.get(neighbour);
        setTopLevelOrder(moved, other.order());
        setTopLevelOrder(other, moved.order());
    }

    private void moveWithinCategory(NavigationLink link, NavigationDtos.MoveDirection direction) {
        List<NavigationLink> siblings = linkRepo.findByCategoryId(link.getCategoryId()).stream()
                .sorted(Comparator.comparingInt(NavigationService::linkOrder))
                .toList();

        int index = indexOf(siblings, sibling -> sibling.getId().equals(link.getId()));
        int neighbour = neighbourOf(siblings.size(), index, direction);
        if (neighbour < 0) {
            return;
        }

        NavigationLink other = siblings.get(neighbour);
        int ownOrder = linkOrder(link);
        link.setSortOrder(linkOrder(other));
        other.setSortOrder(ownOrder);
        linkRepo.save(link);
        linkRepo.save(other);
    }

    private void setTopLevelOrder(TopEntry entry, int order) {
        if (entry.category()) {
            categoryRepo.findById(entry.id()).ifPresent(category -> {
                category.setSortOrder(order);
                categoryRepo.save(category);
            });
        } else {
            linkRepo.findById(entry.id()).ifPresent(link -> {
                link.setSortOrder(order);
                linkRepo.save(link);
            });
        }
    }

    /** @return der Index des Nachbarn, oder -1 am Rand der Liste */
    private static int neighbourOf(int size, int index, NavigationDtos.MoveDirection direction) {
        if (index < 0) {
            return -1;
        }
        int neighbour = direction == NavigationDtos.MoveDirection.UP ? index - 1 : index + 1;
        return neighbour >= 0 && neighbour < size ? neighbour : -1;
    }

    private static <T> int indexOf(List<T> list, java.util.function.Predicate<T> match) {
        for (int i = 0; i < list.size(); i++) {
            if (match.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int nextTopLevelOrder() {
        int highest = 0;
        for (NavigationCategory category : categoryRepo.findAll()) {
            highest = Math.max(highest, categoryOrder(category));
        }
        for (NavigationLink link : linkRepo.findAll()) {
            if (link.getCategoryId() == null) {
                highest = Math.max(highest, linkOrder(link));
            }
        }
        return highest + ORDER_STEP;
    }

    private int nextOrderIn(Long categoryId) {
        if (categoryId == null) {
            return nextTopLevelOrder();
        }
        return linkRepo.findByCategoryId(categoryId).stream()
                .mapToInt(NavigationService::linkOrder)
                .max()
                .orElse(-ORDER_STEP) + ORDER_STEP;
    }

    private NavigationCategory newCategory() {
        NavigationCategory category = new NavigationCategory();
        category.setSortOrder(nextTopLevelOrder());
        return category;
    }

    private static NavigationDtos.LinkDto toDto(NavigationLink link) {
        return new NavigationDtos.LinkDto(
                link.getId(), link.getLabel(), link.getUrl(), link.getIcon(),
                link.getCategoryId(), link.getRequiredRole(),
                Boolean.TRUE.equals(link.getActive()), linkOrder(link));
    }

    /*
     * Bewusst zwei Namen statt einer Ueberladung: als Methodenreferenz in einer
     * Komparator-Kette kann der Compiler die Ueberladung nicht aufloesen.
     */

    /** Ohne vergebene Position zaehlt der Eintrag als ganz oben. */
    private static int linkOrder(NavigationLink link) {
        return link.getSortOrder() == null ? 0 : link.getSortOrder();
    }

    private static int categoryOrder(NavigationCategory category) {
        return category.getSortOrder() == null ? 0 : category.getSortOrder();
    }

    private static boolean isExternal(NavigationLink link) {
        return link.getUrl() != null && link.getUrl().startsWith(EXTERNAL_PREFIX);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value) {
        return value == null || value.isBlank()
                ? NavigationMigration.DEFAULT_CATEGORY_ICON
                : value.trim();
    }
}

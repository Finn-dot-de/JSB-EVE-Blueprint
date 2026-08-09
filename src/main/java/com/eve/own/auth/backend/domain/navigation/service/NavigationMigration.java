package com.eve.own.auth.backend.domain.navigation.service;

import com.eve.own.auth.backend.domain.navigation.entity.NavigationCategory;
import com.eve.own.auth.backend.domain.navigation.entity.NavigationLink;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationCategoryRepository;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationLinkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fuehrt die Navigation einmalig von Kategorie-Texten auf echte Register ueber.
 *
 * <p>Bis hierher trug jeder Link seinen Kategorie-Namen als Text, und die
 * Reihenfolge stand als Namensliste im Frontend. Beides wird hier in Zeilen
 * ueberfuehrt: aus jedem vorkommenden Namen wird ein Register, und die bisher
 * fest verdrahtete Reihenfolge wird als Startwert uebernommen.</p>
 *
 * <p>Die Reihenfolge wird ausdruecklich mitgenommen: ohne sie stuende das Menue
 * nach dem ersten Start in einer anderen Ordnung da, und niemand wuesste warum.
 * Nach der Uebernahme ist die Liste hier bedeutungslos - gepflegt wird die
 * Reihenfolge dann in der Oberflaeche.</p>
 *
 * <p>Zeilenweise idempotent: angefasst wird nur, was noch keine Position oder
 * noch kein Register hat.</p>
 */
@Slf4j
@Component
public class NavigationMigration implements ApplicationRunner {

    /** Das Standard-Symbol eines Registers - die Seitenleiste zeigte bisher immer dieses. */
    static final String DEFAULT_CATEGORY_ICON = "fa-solid fa-folder";

    /**
     * Die bis dahin im Frontend hinterlegte Reihenfolge.
     *
     * <p>Sie mischt Register und einzelne Punkte, genau wie die oberste Ebene
     * der Seitenleiste. Nur als Startwert gedacht.</p>
     */
    private static final List<String> LEGACY_ORDER = List.of(
            "Dashboard", "Services", "CharLink", "Gruppen Management", "Admin",
            "CorpTools", "Fleet Management", "SOV Monitor", "Buyback Program",
            "Intel Parser", "Moon Mining Pay!", "Patreon", "Reverse Buyback",
            "Ship Replacement", "Sovereignty Timer", "SYN Wiki", "Wiki");

    /** Abstand zwischen den vergebenen Positionen, damit spaeter Platz bleibt. */
    private static final int ORDER_STEP = 10;

    private final NavigationLinkRepository linkRepo;
    private final NavigationCategoryRepository categoryRepo;

    @PersistenceContext
    private EntityManager em;

    public NavigationMigration(NavigationLinkRepository linkRepo,
                               NavigationCategoryRepository categoryRepo) {
        this.linkRepo = linkRepo;
        this.categoryRepo = categoryRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<Long, String> legacyCategories = legacyCategoryNames();
        List<NavigationLink> links = linkRepo.findAll();

        int movedToCategory = adoptCategories(links, legacyCategories);
        int ordered = assignMissingOrder(links);

        if (movedToCategory > 0 || ordered > 0) {
            log.info("Navigation uebernommen: {} Links einem Register zugeordnet, "
                    + "{} Positionen vergeben.", movedToCategory, ordered);
        }
    }

    /**
     * Liest die alte Textspalte.
     *
     * <p>Ueber eine native Abfrage, weil das Feld nicht mehr an der Entitaet
     * haengt - es soll auch nicht wieder dorthin. Fehlt die Spalte (frische
     * Datenbank), gibt es schlicht nichts zu uebernehmen.</p>
     */
    private Map<Long, String> legacyCategoryNames() {
        try {
            @SuppressWarnings("unchecked")
            List<Tuple> rows = em.createNativeQuery(
                    "SELECT id AS \"id\", category AS \"category\" FROM navigation_links "
                            + "WHERE category IS NOT NULL AND category <> ''", Tuple.class)
                    .getResultList();

            Map<Long, String> byLinkId = new LinkedHashMap<>();
            for (Tuple row : rows) {
                byLinkId.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("category")));
            }
            return byLinkId;
        } catch (RuntimeException e) {
            log.debug("Keine alte Kategorie-Spalte vorhanden, nichts zu uebernehmen: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /** Legt zu jedem alten Kategorie-Namen ein Register an und haengt die Links dort ein. */
    private int adoptCategories(List<NavigationLink> links, Map<Long, String> legacyCategories) {
        if (legacyCategories.isEmpty()) {
            return 0;
        }

        Map<String, NavigationCategory> byName = new HashMap<>();
        categoryRepo.findAll().forEach(category -> byName.put(category.getName(), category));

        int moved = 0;
        for (NavigationLink link : links) {
            if (link.getCategoryId() != null) {
                continue;
            }
            String name = legacyCategories.get(link.getId());
            if (name == null) {
                continue;
            }

            NavigationCategory category = byName.computeIfAbsent(name, key -> {
                NavigationCategory fresh = new NavigationCategory();
                fresh.setName(key);
                fresh.setIcon(DEFAULT_CATEGORY_ICON);
                return categoryRepo.save(fresh);
            });
            link.setCategoryId(category.getId());
            linkRepo.save(link);
            moved++;
        }
        return moved;
    }

    /**
     * Vergibt fehlende Positionen.
     *
     * <p>Die oberste Ebene folgt der alten Namensliste; alles, was dort nicht
     * vorkommt, haengt sich hinten an - so wie es die Seitenleiste bisher auch
     * gehandhabt hat. Innerhalb eines Registers zaehlt die Reihenfolge der IDs,
     * also die Reihenfolge des Anlegens.</p>
     */
    private int assignMissingOrder(List<NavigationLink> links) {
        int touched = 0;

        // Oberste Ebene: Register und Links ohne Register teilen sich eine Reihenfolge.
        List<NavigationCategory> categories = categoryRepo.findAll();
        List<NavigationLink> rootLinks = links.stream()
                .filter(link -> link.getCategoryId() == null)
                .toList();

        int position = 0;
        List<NavigationCategory> pendingCategories = new ArrayList<>(categories);
        List<NavigationLink> pendingRootLinks = new ArrayList<>(rootLinks);

        for (String name : LEGACY_ORDER) {
            NavigationCategory category = takeCategory(pendingCategories, name);
            if (category != null) {
                if (category.getSortOrder() == null) {
                    category.setSortOrder(position);
                    categoryRepo.save(category);
                    touched++;
                }
                position += ORDER_STEP;
                continue;
            }
            NavigationLink link = takeLink(pendingRootLinks, name);
            if (link != null && link.getSortOrder() == null) {
                link.setSortOrder(position);
                linkRepo.save(link);
                touched++;
                position += ORDER_STEP;
            }
        }

        // Was in der alten Liste fehlte, kommt hinten dran.
        for (NavigationCategory category : pendingCategories) {
            if (category.getSortOrder() == null) {
                category.setSortOrder(position);
                categoryRepo.save(category);
                touched++;
                position += ORDER_STEP;
            }
        }
        for (NavigationLink link : pendingRootLinks) {
            if (link.getSortOrder() == null) {
                link.setSortOrder(position);
                linkRepo.save(link);
                touched++;
                position += ORDER_STEP;
            }
        }

        // Innerhalb der Register: nach ID, also in der Reihenfolge des Anlegens.
        for (NavigationCategory category : categories) {
            int inner = 0;
            for (NavigationLink link : links.stream()
                    .filter(link -> category.getId().equals(link.getCategoryId()))
                    .sorted(java.util.Comparator.comparing(NavigationLink::getId))
                    .toList()) {
                if (link.getSortOrder() == null) {
                    link.setSortOrder(inner);
                    linkRepo.save(link);
                    touched++;
                }
                inner += ORDER_STEP;
            }
        }
        return touched;
    }

    private static NavigationCategory takeCategory(List<NavigationCategory> pending, String name) {
        return pending.stream()
                .filter(category -> name.equalsIgnoreCase(category.getName()))
                .findFirst()
                .map(found -> {
                    pending.remove(found);
                    return found;
                })
                .orElse(null);
    }

    private static NavigationLink takeLink(List<NavigationLink> pending, String label) {
        return pending.stream()
                .filter(link -> label.equalsIgnoreCase(link.getLabel()))
                .findFirst()
                .map(found -> {
                    pending.remove(found);
                    return found;
                })
                .orElse(null);
    }
}

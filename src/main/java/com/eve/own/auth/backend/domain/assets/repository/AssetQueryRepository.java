package com.eve.own.auth.backend.domain.assets.repository;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AssetQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * BPCs teilen sich die type_id mit dem Original, der Marktpreis gehoert aber
     * fast immer zur BPO. Ohne echte Bewertungsgrundlage fuer Copies fliessen sie
     * mit 0 in die Auswertung ein, statt faelschlich den BPO-Preis zu erben.
     */
    private static final String UNIT_PRICE_EXPR =
            "(CASE WHEN a.is_blueprint_copy IS TRUE THEN 0 ELSE COALESCE(p.jita_buy, p.jita_sell, 0) END)";

    private static final String VALUE_EXPR =
            "(" + UNIT_PRICE_EXPR + " * a.quantity)";

    private static final String BASE_FROM = """
            FROM character_assets a
            JOIN characters c ON c.character_id = a.character_id
            LEFT JOIN characters mc ON mc.character_id = c.main_character_id
            LEFT JOIN corporations corp ON corp.corporation_id = c.corporation_id
            JOIN evesde."invTypes" t ON t."typeID" = a.type_id
            LEFT JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
            LEFT JOIN evesde."invCategories" cat ON cat."categoryID" = g."categoryID"
            LEFT JOIN market_prices p ON p.type_id = a.type_id
            LEFT JOIN asset_locations loc ON loc.location_id = a.root_location_id
            """;

    private static final Map<String, String> SORT_FLAT = Map.of(
            "value", VALUE_EXPR,
            "quantity", "a.quantity",
            "typeName", "t.\"typeName\"",
            "characterName", "c.name",
            "mainName", "COALESCE(mc.name, c.name)",
            "locationName", "loc.name",
            "corporationName", "corp.name",
            "groupName", "g.\"groupName\""
    );

    private static final Map<String, String> SORT_GROUPED = Map.of(
            "value", "SUM(" + VALUE_EXPR + ")",
            "quantity", "SUM(a.quantity)",
            "typeName", "t.\"typeName\"",
            "mainName", "COALESCE(mc.name, c.name)",
            "corporationName", "corp.name",
            "groupName", "g.\"groupName\""
    );

    // ==================================================================
    // 1. Detailsuche
    // ==================================================================
    public AssetDtos.PageDto<AssetDtos.AssetRowDto> search(AssetDtos.AssetSearchRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(req, params);

        int page = req.page() == null || req.page() < 0 ? 0 : req.page();
        int size = req.size() == null || req.size() < 1 ? 50 : Math.min(req.size(), 500);

        String sql = """
                SELECT a.item_id AS "itemId",
                       a.character_id AS "characterId",
                       c.name AS "characterName",
                       COALESCE(c.main_character_id, c.character_id) AS "mainId",
                       COALESCE(mc.name, c.name) AS "mainName",
                       corp.corporation_id AS "corporationId",
                       corp.name AS "corporationName",
                       t."typeID" AS "typeId",
                       t."typeName" AS "typeName",
                       g."groupID" AS "groupId",
                       g."groupName" AS "groupName",
                       cat."categoryID" AS "categoryId",
                       cat."categoryName" AS "categoryName",
                       a.quantity AS "quantity",
                       COALESCE(loc.name, 'Unbekannter Ort (' || a.root_location_id || ')') AS "locationName",
                       loc.system_name AS "systemName",
                       loc.region_name AS "regionName",
                       a.location_flag AS "locationFlag",
                       a.is_singleton AS "singleton",
                       -- Leerstring heisst "abgefragt, aber kein Name vergeben".
                       -- Nach aussen ist das dasselbe wie "kein Name".
                       NULLIF(a.custom_name, '') AS "customName",
                       a.is_blueprint_copy AS "isBlueprintCopy",
                       """ + UNIT_PRICE_EXPR + """
                        AS "unitPrice",
                       """ + VALUE_EXPR + """
                        AS "totalValue"
                """ + BASE_FROM + where
                + " ORDER BY " + orderBy(req, SORT_FLAT, VALUE_EXPR)
                + " LIMIT :limit OFFSET :offset";

        Query q = em.createNativeQuery(sql, Tuple.class);
        params.forEach(q::setParameter);
        q.setParameter("limit", size);
        q.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = q.getResultList();

        List<AssetDtos.AssetRowDto> content = new ArrayList<>(rows.size());
        double pageValue = 0d;
        for (Tuple r : rows) {
            double value = dbl(r, "totalValue");
            pageValue += value;
            content.add(new AssetDtos.AssetRowDto(
                    lng(r, "itemId"),
                    lng(r, "characterId"),
                    str(r, "characterName"),
                    lng(r, "mainId"),
                    str(r, "mainName"),
                    lng(r, "corporationId"),
                    str(r, "corporationName"),
                    lng(r, "typeId"),
                    str(r, "typeName"),
                    lng(r, "groupId"),
                    str(r, "groupName"),
                    lng(r, "categoryId"),
                    str(r, "categoryName"),
                    lng(r, "quantity"),
                    str(r, "locationName"),
                    str(r, "systemName"),
                    str(r, "regionName"),
                    str(r, "locationFlag"),
                    bool(r, "singleton"),
                    str(r, "customName"),
                    bool(r, "isBlueprintCopy"),
                    dbl(r, "unitPrice"),
                    value
            ));
        }

        long total = count("SELECT COUNT(*) " + BASE_FROM + where, params);
        double grandTotal = sum("SELECT COALESCE(SUM(" + VALUE_EXPR + "), 0) " + BASE_FROM + where, params);

        return new AssetDtos.PageDto<>(content, page, size, total,
                (int) Math.ceil((double) total / size), pageValue, grandTotal);
    }

    // ==================================================================
    // 2. Gruppierte Suche
    // ==================================================================
    public AssetDtos.PageDto<AssetDtos.AssetStackDto> searchGrouped(AssetDtos.AssetSearchRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(req, params);

        int page = req.page() == null || req.page() < 0 ? 0 : req.page();
        int size = req.size() == null || req.size() < 1 ? 50 : Math.min(req.size(), 500);

        String groupBy = """
                 GROUP BY t."typeID", t."typeName", g."groupName", cat."categoryName",
                          COALESCE(c.main_character_id, c.character_id),
                          COALESCE(mc.name, c.name), corp.name, p.jita_buy, p.jita_sell,
                          a.is_blueprint_copy
                """;

        String sql = """
                SELECT t."typeID" AS "typeId",
                       t."typeName" AS "typeName",
                       g."groupName" AS "groupName",
                       cat."categoryName" AS "categoryName",
                       COALESCE(c.main_character_id, c.character_id) AS "mainId",
                       COALESCE(mc.name, c.name) AS "mainName",
                       corp.name AS "corporationName",
                       a.is_blueprint_copy AS "isBlueprintCopy",
                       SUM(a.quantity) AS "quantity",
                       COUNT(DISTINCT a.root_location_id) AS "locationCount",
                       """ + UNIT_PRICE_EXPR + """
                        AS "unitPrice",
                       SUM(""" + VALUE_EXPR + """
                       ) AS "totalValue"
                """ + BASE_FROM + where + groupBy
                + " ORDER BY " + orderBy(req, SORT_GROUPED, "SUM(" + VALUE_EXPR + ")")
                + " LIMIT :limit OFFSET :offset";

        Query q = em.createNativeQuery(sql, Tuple.class);
        params.forEach(q::setParameter);
        q.setParameter("limit", size);
        q.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = q.getResultList();

        List<AssetDtos.AssetStackDto> content = new ArrayList<>(rows.size());
        double pageValue = 0d;
        for (Tuple r : rows) {
            double value = dbl(r, "totalValue");
            pageValue += value;
            content.add(new AssetDtos.AssetStackDto(
                    lng(r, "typeId"),
                    str(r, "typeName"),
                    str(r, "groupName"),
                    str(r, "categoryName"),
                    lng(r, "mainId"),
                    str(r, "mainName"),
                    str(r, "corporationName"),
                    bool(r, "isBlueprintCopy"),
                    lng(r, "quantity"),
                    lng(r, "locationCount").intValue(),
                    dbl(r, "unitPrice"),
                    value
            ));
        }

        long total = count("SELECT COUNT(*) FROM (SELECT 1 " + BASE_FROM + where + groupBy + ") sub", params);
        double grandTotal = sum("SELECT COALESCE(SUM(" + VALUE_EXPR + "), 0) " + BASE_FROM + where, params);

        return new AssetDtos.PageDto<>(content, page, size, total,
                (int) Math.ceil((double) total / size), pageValue, grandTotal);
    }

    // ==================================================================
    // 3. "Wer hat diesen Gegenstand?"
    // ==================================================================
    public List<Tuple> findHoldersOfType(Long typeId) {
        String sql = """
                SELECT COALESCE(c.main_character_id, c.character_id) AS "mainId",
                       COALESCE(mc.name, c.name) AS "mainName",
                       c.character_id AS "characterId",
                       c.name AS "characterName",
                       corp.name AS "corporationName",
                       a.root_location_id AS "locationId",
                       loc.name AS "locationName",
                       loc.system_name AS "systemName",
                       loc.region_name AS "regionName",
                       a.location_flag AS "locationFlag",
                       a.is_singleton AS "singleton",
                       NULLIF(a.custom_name, '') AS "customName",
                       SUM(a.quantity) AS "quantity",
                       SUM(""" + VALUE_EXPR + """
                       ) AS "value"
                """ + BASE_FROM + """
                WHERE a.type_id = :typeId
                GROUP BY COALESCE(c.main_character_id, c.character_id),
                         COALESCE(mc.name, c.name), c.character_id, c.name, corp.name,
                         a.root_location_id, loc.name, loc.system_name, loc.region_name, a.location_flag,
                         a.is_singleton, a.custom_name
                ORDER BY a.is_singleton NULLS FIRST, SUM(a.quantity) DESC, a.custom_name
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeId", typeId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public Tuple findTypeInfo(Long typeId) {
        String sql = """
                SELECT t."typeID" AS "typeId",
                       t."typeName" AS "typeName",
                       g."groupName" AS "groupName",
                       COALESCE(p.jita_buy, p.jita_sell, 0) AS "unitPrice"
                FROM evesde."invTypes" t
                LEFT JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                LEFT JOIN market_prices p ON p.type_id = t."typeID"
                WHERE t."typeID" = :typeId
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeId", typeId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res.isEmpty() ? null : res.get(0);
    }

    // ==================================================================
    // 4. Uebersicht / KPIs
    // ==================================================================
    public Tuple totals() {
        String sql = """
                SELECT COUNT(*) AS "stacks",
                       COALESCE(SUM(a.quantity), 0) AS "items",
                       COUNT(DISTINCT a.type_id) AS "types",
                       COUNT(DISTINCT a.character_id) AS "chars",
                       COALESCE(SUM(""" + VALUE_EXPR + """
                       ), 0) AS "value"
                """ + BASE_FROM;
        Query q = em.createNativeQuery(sql, Tuple.class);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res.isEmpty() ? null : res.get(0);
    }

    public List<Tuple> valueByCorporation() {
        return simpleBucket("corp.name", 50);
    }

    public List<Tuple> valueByCategory() {
        return simpleBucket("cat.\"categoryName\"", 50);
    }

    public List<Tuple> valueByRegion() {
        return simpleBucket("loc.region_name", 15);
    }

    private List<Tuple> simpleBucket(String column, int limit) {
        String sql = """
                SELECT COALESCE(""" + column + """
                       , 'Unbekannt') AS "name",
                       COALESCE(SUM(a.quantity), 0) AS "quantity",
                       COALESCE(SUM(""" + VALUE_EXPR + """
                       ), 0) AS "value"
                """ + BASE_FROM + """
                GROUP BY 1
                ORDER BY 3 DESC
                LIMIT :limit
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public List<Tuple> topTypes(int limit) {
        String sql = """
                SELECT t."typeID" AS "typeId",
                       t."typeName" AS "typeName",
                       g."groupName" AS "groupName",
                       COALESCE(SUM(a.quantity), 0) AS "quantity",
                       COALESCE(SUM(""" + VALUE_EXPR + """
                       ), 0) AS "value",
                       COUNT(DISTINCT COALESCE(c.main_character_id, c.character_id)) AS "holders"
                """ + BASE_FROM + """
                GROUP BY t."typeID", t."typeName", g."groupName"
                ORDER BY 5 DESC
                LIMIT :limit
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public List<Tuple> topHolders(int limit) {
        String sql = """
                SELECT COALESCE(c.main_character_id, c.character_id) AS "mainId",
                       COALESCE(mc.name, c.name) AS "mainName",
                       STRING_AGG(DISTINCT corp.name, ', ') AS "corporationName",
                       COUNT(*) AS "stacks",
                       COALESCE(SUM(""" + VALUE_EXPR + """
                       ), 0) AS "value"
                """ + BASE_FROM + """
                GROUP BY 1, 2
                ORDER BY 5 DESC
                LIMIT :limit
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    // ==================================================================
    // 5. Member-Detail
    // ==================================================================
    public List<Tuple> memberByCategory(Long mainId) {
        String sql = """
                SELECT COALESCE(cat."categoryName", 'Unbekannt') AS "name",
                       COALESCE(SUM(a.quantity), 0) AS "quantity",
                       COALESCE(SUM(""" + VALUE_EXPR + """
                       ), 0) AS "value"
                """ + BASE_FROM + """
                WHERE COALESCE(c.main_character_id, c.character_id) = :mainId
                GROUP BY 1
                ORDER BY 3 DESC
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("mainId", mainId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public List<Tuple> memberByLocation(Long mainId) {
        String sql = """
                SELECT a.root_location_id AS "locationId",
                       loc.name AS "locationName",
                       loc.system_name AS "systemName",
                       loc.region_name AS "regionName",
                       COUNT(*) AS "stacks",
                       COALESCE(SUM(""" + VALUE_EXPR + """
                       ), 0) AS "value"
                """ + BASE_FROM + """
                WHERE COALESCE(c.main_character_id, c.character_id) = :mainId
                GROUP BY 1, 2, 3, 4
                ORDER BY 6 DESC
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("mainId", mainId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    // ==================================================================
    // 6. Doktrin-Verfuegbarkeit
    // ==================================================================
    public List<Tuple> doctrineOwnership(List<Long> typeIds) {
        String sql = """
                SELECT COALESCE(c.main_character_id, c.character_id) AS "mainId",
                       COALESCE(mc.name, c.name) AS "mainName",
                       corp.name AS "corporationName",
                       a.type_id AS "typeId",
                       SUM(a.quantity) AS "quantity"
                """ + BASE_FROM + """
                WHERE a.type_id IN (:typeIds)
                GROUP BY 1, 2, 3, 4
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeIds", typeIds);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    // ==================================================================
    // 7. Filter-Optionen / Typeahead
    // ==================================================================
    private static final String DISTINCT_TYPES = """
            (SELECT DISTINCT type_id FROM character_assets) a
            JOIN evesde."invTypes" t ON t."typeID" = a.type_id
            LEFT JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
            LEFT JOIN evesde."invCategories" cat ON cat."categoryID" = g."categoryID"
            """;

    public List<Tuple> distinctCategories() {
        return idNameQuery("""
                SELECT DISTINCT cat."categoryID" AS "id", cat."categoryName" AS "name"
                FROM """ + DISTINCT_TYPES + """
                WHERE cat."categoryName" IS NOT NULL
                ORDER BY 2
                """);
    }

    public List<Tuple> distinctGroups(Long categoryId) {
        String filter = categoryId != null ? " AND cat.\"categoryID\" = :categoryId " : "";
        String sql = """
                SELECT DISTINCT g."groupID" AS "id", g."groupName" AS "name"
                FROM """ + DISTINCT_TYPES + """
                WHERE g."groupName" IS NOT NULL
                """ + filter + " ORDER BY 2";
        Query q = em.createNativeQuery(sql, Tuple.class);
        if (categoryId != null) q.setParameter("categoryId", categoryId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public List<Tuple> distinctLocations() {
        return idNameQuery("""
                SELECT l.location_id AS "id", l.name AS "name"
                FROM asset_locations l
                WHERE l.name IS NOT NULL
                ORDER BY 2
                """);
    }

    public List<String> distinctRegions() {
        Query q = em.createNativeQuery("""
                SELECT DISTINCT l.region_name
                FROM asset_locations l
                WHERE l.region_name IS NOT NULL
                ORDER BY 1
                """);
        @SuppressWarnings("unchecked")
        List<String> res = q.getResultList();
        return res;
    }

    public List<String> distinctLocationFlags() {
        Query q = em.createNativeQuery("""
                SELECT DISTINCT a.location_flag
                FROM character_assets a
                WHERE a.location_flag IS NOT NULL
                ORDER BY 1
                """);
        @SuppressWarnings("unchecked")
        List<String> res = q.getResultList();
        return res;
    }

    public List<Tuple> distinctCorporations() {
        return idNameQuery("""
                SELECT DISTINCT corp.corporation_id AS "id", corp.name AS "name"
                FROM corporations corp
                JOIN characters c ON c.corporation_id = corp.corporation_id
                WHERE corp.name IS NOT NULL
                ORDER BY 2
                """);
    }

    public List<Tuple> distinctMains() {
        return idNameQuery("""
                SELECT DISTINCT COALESCE(c.main_character_id, c.character_id) AS "id",
                                COALESCE(mc.name, c.name) AS "name"
                FROM characters c
                LEFT JOIN characters mc ON mc.character_id = c.main_character_id
                ORDER BY 2
                """);
    }

    public List<Tuple> suggestTypes(String term, int limit) {
        String sql = """
                SELECT t."typeID" AS "typeId",
                       t."typeName" AS "typeName",
                       g."groupName" AS "groupName",
                       COALESCE(SUM(a.quantity), 0) AS "quantity"
                """ + BASE_FROM + """
                WHERE LOWER(t."typeName") LIKE LOWER(CONCAT('%', :term, '%'))
                GROUP BY t."typeID", t."typeName", g."groupName"
                ORDER BY LENGTH(t."typeName") ASC, 4 DESC
                LIMIT :limit
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("term", term == null ? "" : term);
        q.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    private List<Tuple> idNameQuery(String sql) {
        Query q = em.createNativeQuery(sql, Tuple.class);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    // ==================================================================
    // 8. Mitglieder-Sicht ("My Assets")
    // ------------------------------------------------------------------
    // Dieselben Auswertungen wie oben, aber hart auf einen Account begrenzt.
    // Die Filter-Optionen duerfen hier NICHT die globalen Listen liefern:
    // sonst saehe ein einfaches Mitglied ueber die Standort- und Regions-
    // Dropdowns, wo die gesamte Corp ihre Sachen stehen hat.
    // ==================================================================

    /**
     * Was "mein Account" bedeutet: der Main und alle seine Alts.
     * Bewusst eine Konstante, damit die Definition an keiner Stelle abweicht.
     */
    private static final String MAIN_SCOPE = " COALESCE(c.main_character_id, c.character_id) = :mainId ";

    public List<Tuple> distinctCategoriesForMain(Long mainId) {
        return mainScopedTuples("""
                SELECT DISTINCT cat."categoryID" AS "id", cat."categoryName" AS "name"
                """ + BASE_FROM + " WHERE " + MAIN_SCOPE + """
                  AND cat."categoryName" IS NOT NULL
                ORDER BY 2
                """, mainId);
    }

    public List<Tuple> distinctGroupsForMain(Long mainId, Long categoryId) {
        String filter = categoryId != null ? " AND cat.\"categoryID\" = :categoryId " : "";
        String sql = """
                SELECT DISTINCT g."groupID" AS "id", g."groupName" AS "name"
                """ + BASE_FROM + " WHERE " + MAIN_SCOPE + """
                  AND g."groupName" IS NOT NULL
                """ + filter + " ORDER BY 2";

        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("mainId", mainId);
        if (categoryId != null) q.setParameter("categoryId", categoryId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public List<Tuple> distinctLocationsForMain(Long mainId) {
        return mainScopedTuples("""
                SELECT DISTINCT a.root_location_id AS "id",
                       COALESCE(loc.name, 'Unbekannter Ort (' || a.root_location_id || ')') AS "name"
                """ + BASE_FROM + " WHERE " + MAIN_SCOPE + """
                  AND a.root_location_id IS NOT NULL
                ORDER BY 2
                """, mainId);
    }

    public List<String> distinctRegionsForMain(Long mainId) {
        return mainScopedStrings("""
                SELECT DISTINCT loc.region_name
                """ + BASE_FROM + " WHERE " + MAIN_SCOPE + """
                  AND loc.region_name IS NOT NULL
                ORDER BY 1
                """, mainId);
    }

    public List<String> distinctLocationFlagsForMain(Long mainId) {
        return mainScopedStrings("""
                SELECT DISTINCT a.location_flag
                """ + BASE_FROM + " WHERE " + MAIN_SCOPE + """
                  AND a.location_flag IS NOT NULL
                ORDER BY 1
                """, mainId);
    }

    /** Die Charaktere des Accounts - fuer das "Charakter"-Dropdown der Mitglieder-Suche. */
    public List<Tuple> charactersOfMain(Long mainId) {
        String sql = """
                SELECT c.character_id AS "id", c.name AS "name"
                FROM characters c
                WHERE COALESCE(c.main_character_id, c.character_id) = :mainId
                ORDER BY 2
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("mainId", mainId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    public List<Tuple> suggestTypesForMain(Long mainId, String term, int limit) {
        String sql = """
                SELECT t."typeID" AS "typeId",
                       t."typeName" AS "typeName",
                       g."groupName" AS "groupName",
                       COALESCE(SUM(a.quantity), 0) AS "quantity"
                """ + BASE_FROM + " WHERE " + MAIN_SCOPE + """
                  AND LOWER(t."typeName") LIKE LOWER(CONCAT('%', :term, '%'))
                GROUP BY t."typeID", t."typeName", g."groupName"
                ORDER BY LENGTH(t."typeName") ASC, 4 DESC
                LIMIT :limit
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("mainId", mainId);
        q.setParameter("term", term == null ? "" : term);
        q.setParameter("limit", limit);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    private List<Tuple> mainScopedTuples(String sql, Long mainId) {
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("mainId", mainId);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    private List<String> mainScopedStrings(String sql, Long mainId) {
        Query q = em.createNativeQuery(sql);
        q.setParameter("mainId", mainId);
        @SuppressWarnings("unchecked")
        List<String> res = q.getResultList();
        return res;
    }

    // ==================================================================
    // Helfer
    // ==================================================================
    private String buildWhere(AssetDtos.AssetSearchRequest req, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();

        if (notBlank(req.q())) {
            conditions.add("""
                    (LOWER(t."typeName") LIKE LOWER(CONCAT('%', :q, '%'))
                     OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))
                     OR LOWER(COALESCE(mc.name, c.name)) LIKE LOWER(CONCAT('%', :q, '%'))
                     OR LOWER(COALESCE(loc.name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                     OR LOWER(COALESCE(g."groupName", '')) LIKE LOWER(CONCAT('%', :q, '%')))
                    """);
            params.put("q", req.q().trim());
        }
        if (req.typeId() != null) {
            conditions.add("a.type_id = :typeId");
            params.put("typeId", req.typeId());
        }
        if (req.groupId() != null) {
            conditions.add("g.\"groupID\" = :groupId");
            params.put("groupId", req.groupId());
        }
        if (req.categoryId() != null) {
            conditions.add("cat.\"categoryID\" = :categoryId");
            params.put("categoryId", req.categoryId());
        }
        if (req.characterId() != null) {
            conditions.add("a.character_id = :characterId");
            params.put("characterId", req.characterId());
        }
        if (req.mainId() != null) {
            conditions.add("COALESCE(c.main_character_id, c.character_id) = :mainId");
            params.put("mainId", req.mainId());
        }
        if (req.corporationId() != null) {
            conditions.add("c.corporation_id = :corporationId");
            params.put("corporationId", req.corporationId());
        }
        if (req.locationId() != null) {
            conditions.add("a.root_location_id = :locationId");
            params.put("locationId", req.locationId());
        }
        if (notBlank(req.regionName())) {
            conditions.add("loc.region_name = :regionName");
            params.put("regionName", req.regionName().trim());
        }
        if (notBlank(req.locationFlag())) {
            conditions.add("a.location_flag = :locationFlag");
            params.put("locationFlag", req.locationFlag().trim());
        }
        if (req.minQuantity() != null && req.minQuantity() > 0) {
            conditions.add("a.quantity >= :minQuantity");
            params.put("minQuantity", req.minQuantity());
        }
        if (req.minValue() != null && req.minValue() > 0) {
            conditions.add(VALUE_EXPR + " >= :minValue");
            params.put("minValue", req.minValue());
        }
        if (Boolean.TRUE.equals(req.shipsOnly())) {
            conditions.add("g.\"categoryID\" = 6");
        }

        if (conditions.isEmpty()) return " ";
        return " WHERE " + String.join(" AND ", conditions) + " ";
    }

    private String orderBy(AssetDtos.AssetSearchRequest req, Map<String, String> whitelist, String fallback) {
        String key = req.sort();
        String column = (key == null || key.isBlank())
                ? fallback
                : whitelist.getOrDefault(key, fallback);
        boolean asc = "asc".equalsIgnoreCase(req.direction());
        return column + (asc ? " ASC" : " DESC") + " NULLS LAST";
    }

    private long count(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        Object single = q.getSingleResult();
        return single == null ? 0L : ((Number) single).longValue();
    }

    private double sum(String sql, Map<String, Object> params) {
        Query q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        Object single = q.getSingleResult();
        return single == null ? 0d : ((Number) single).doubleValue();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static Long lng(Tuple t, String alias) {
        Object v = safe(t, alias);
        return v == null ? 0L : ((Number) v).longValue();
    }

    public static Double dbl(Tuple t, String alias) {
        Object v = safe(t, alias);
        return v == null ? 0d : ((Number) v).doubleValue();
    }

    public static String str(Tuple t, String alias) {
        Object v = safe(t, alias);
        return v == null ? null : String.valueOf(v);
    }

    public static Boolean bool(Tuple t, String alias) {
        Object v = safe(t, alias);
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static Object safe(Tuple t, String alias) {
        try {
            return t.get(alias);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
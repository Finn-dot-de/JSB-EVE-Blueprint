package com.eve.own.auth.backend.domain.fleet.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native Abfragen fuer den Doktrin-Readiness-Check.
 *
 * <p>Bewusst nativ statt JPQL: die Skill-Anforderungen der Schiffe stehen im
 * SDE-Schema {@code evesde} und muessen <em>rekursiv</em> aufgeloest werden
 * (ein Cerberus braucht Heavy Assault Cruisers, das braucht Caldari Cruiser,
 * das braucht Caldari Frigate und Spaceship Command). Das leistet nur ein
 * {@code WITH RECURSIVE}.</p>
 */
@Repository
public class ReadinessQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * Die sechs Attribut-Paare, in denen die SDE die Voraussetzungen eines Typs ablegt:
     * links die attributeID mit der typeID des Skills, rechts die mit dem noetigen Level.
     * (182/277 = Primary, 183/278 = Secondary, 184/279 = Tertiary,
     * 1285/1286 = Quaternary, 1289/1287 = Quinary, 1290/1288 = Senary)
     *
     * <p>Die Werte liegen je nach SDE-Build in {@code valueInt} oder {@code valueFloat},
     * deshalb ueberall COALESCE ueber beide Spalten.</p>
     */
    private static final String DIRECT_REQUIREMENTS = """
            direct_req AS (
                SELECT ta."typeID"                                             AS type_id,
                       CAST(COALESCE(ta."valueInt", ta."valueFloat") AS BIGINT)  AS skill_type_id,
                       CAST(COALESCE(lv."valueInt", lv."valueFloat") AS INTEGER) AS required_level
                FROM evesde."dgmTypeAttributes" ta
                JOIN (VALUES (182, 277), (183, 278), (184, 279),
                             (1285, 1286), (1289, 1287), (1290, 1288))
                     AS m(skill_attr, level_attr) ON m.skill_attr = ta."attributeID"
                LEFT JOIN evesde."dgmTypeAttributes" lv
                       ON lv."typeID" = ta."typeID" AND lv."attributeID" = m.level_attr
                WHERE COALESCE(ta."valueInt", ta."valueFloat") IS NOT NULL
            )
            """;

    /**
     * Haengt an die direkten Anforderungen die Rekursion an. Tiefe 8 ist reichlich -
     * der laengste echte Skillbaum in EVE liegt bei 4 Ebenen - und schuetzt zugleich
     * vor fehlerhaften Zyklen in den Stammdaten.
     */
    private static final String REQUIREMENT_TREE = """
            tree(root_id, skill_type_id, required_level, depth) AS (
                SELECT d.type_id, d.skill_type_id, COALESCE(d.required_level, 1), 1
                FROM direct_req d
                WHERE d.type_id IN (:typeIds)
                UNION ALL
                SELECT t.root_id, d.skill_type_id, COALESCE(d.required_level, 1), t.depth + 1
                FROM tree t
                JOIN direct_req d ON d.type_id = t.skill_type_id
                WHERE t.depth < 8
            )
            """;

    // ==================================================================
    // 1. Account-Struktur (Main + Alts)
    // ==================================================================

    /**
     * Alle registrierten Charaktere mit ihrer Account-Zuordnung.
     *
     * <p>Die Corporation stammt bewusst vom <em>Main</em> - ein Account wird in der
     * Auswertung unter der Corp seines Mains gefuehrt, auch wenn ein Alt woanders
     * geparkt ist.</p>
     */
    public List<Tuple> accountRoster() {
        String sql = """
                SELECT c.character_id                              AS "characterId",
                       c.name                                      AS "characterName",
                       COALESCE(c.main_character_id, c.character_id) AS "mainId",
                       COALESCE(mc.name, c.name)                   AS "mainName",
                       corp.name                                   AS "corporationName"
                FROM characters c
                LEFT JOIN characters mc ON mc.character_id = c.main_character_id
                LEFT JOIN corporations corp
                       ON corp.corporation_id = COALESCE(mc.corporation_id, c.corporation_id)
                ORDER BY 4, 2
                """;
        @SuppressWarnings("unchecked")
        List<Tuple> res = em.createNativeQuery(sql, Tuple.class).getResultList();
        return res;
    }

    // ==================================================================
    // 2. Hangar-Check
    // ==================================================================

    /** Wie viele Exemplare der gesuchten Huellen jeder einzelne Charakter besitzt. */
    public List<Tuple> hullOwnership(List<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return List.of();
        String sql = """
                SELECT a.character_id AS "characterId",
                       a.type_id      AS "typeId",
                       SUM(a.quantity) AS "quantity"
                FROM character_assets a
                WHERE a.type_id IN (:typeIds)
                GROUP BY 1, 2
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeIds", typeIds);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    // ==================================================================
    // 3. Skill-Check
    // ==================================================================

    /**
     * Vollstaendig aufgeloeste Skill-Anforderungen je Huelle.
     *
     * <p>Enthaelt nicht nur die direkten Voraussetzungen (z.B. Heavy Assault
     * Cruisers V), sondern den kompletten Vorbedingungsbaum. Nur so ergibt der
     * Check "kann er das Schiff wirklich fliegen?" ein ehrliches Ergebnis.</p>
     */
    public List<Tuple> skillRequirements(List<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return List.of();
        String sql = "WITH RECURSIVE " + DIRECT_REQUIREMENTS + ", " + REQUIREMENT_TREE + """
                SELECT t.root_id        AS "typeId",
                       t.skill_type_id  AS "skillTypeId",
                       st."typeName"    AS "skillName",
                       MAX(t.required_level) AS "requiredLevel"
                FROM tree t
                LEFT JOIN evesde."invTypes" st ON st."typeID" = t.skill_type_id
                GROUP BY 1, 2, 3
                ORDER BY 1, 3
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeIds", typeIds);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    /**
     * Alle Luecken: welcher Charakter hat welchen benoetigten Skill nicht hoch genug.
     *
     * <p>Beruecksichtigt werden nur Charaktere, zu denen ueberhaupt Skills
     * synchronisiert sind. Ein Alt ohne Token wuerde sonst faelschlich als
     * "kann nichts fliegen" erscheinen statt als "unbekannt".</p>
     */
    public List<Tuple> skillGaps(List<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return List.of();
        String sql = "WITH RECURSIVE " + DIRECT_REQUIREMENTS + ", " + REQUIREMENT_TREE + """
                , reqs AS (
                    SELECT t.root_id AS type_id,
                           t.skill_type_id,
                           MAX(t.required_level) AS required_level
                    FROM tree t
                    GROUP BY 1, 2
                )
                SELECT known.character_id       AS "characterId",
                       r.type_id                AS "typeId",
                       r.skill_type_id          AS "skillTypeId",
                       st."typeName"            AS "skillName",
                       r.required_level         AS "requiredLevel",
                       COALESCE(cs.active_level, 0) AS "currentLevel"
                FROM (SELECT DISTINCT character_id FROM character_skills) known
                CROSS JOIN reqs r
                LEFT JOIN character_skills cs
                       ON cs.character_id = known.character_id
                      AND cs.skill_type_id = r.skill_type_id
                LEFT JOIN evesde."invTypes" st ON st."typeID" = r.skill_type_id
                WHERE COALESCE(cs.active_level, 0) < r.required_level
                ORDER BY 1, 2, 4
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeIds", typeIds);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    /** Charaktere, zu denen ein Skill-Snapshot vorliegt. */
    public List<Long> charactersWithSkillData() {
        @SuppressWarnings("unchecked")
        List<Number> res = em.createNativeQuery(
                "SELECT DISTINCT character_id FROM character_skills").getResultList();
        return res.stream().map(Number::longValue).toList();
    }

    // ==================================================================
    // 4. Typaufloesung fuer den EFT-Parser
    // ==================================================================

    /**
     * Loest Item-Namen (kleingeschrieben) gegen die SDE auf und liefert gleich mit,
     * in welchen Slot das Item gehoert.
     *
     * <p>Die Slot-Zuordnung kommt aus {@code dgmTypeEffects} (11 = low, 12 = high,
     * 13 = mid, 2663 = rig, 3772 = subsystem) und nicht aus der Reihenfolge der
     * EFT-Bloecke. Das ist deutlich robuster, weil verschiedene Tools (Pyfa,
     * Ingame-Export, Foren-Copy-Paste) die Bloecke unterschiedlich anordnen und
     * Leerzeilen gerne verschluckt werden.</p>
     *
     * <p>Bei mehrdeutigen Namen gewinnt der veroeffentlichte Eintrag mit der
     * kleinsten typeID - damit ist die Aufloesung reproduzierbar.</p>
     */
    public List<Tuple> resolveTypesByName(List<String> lowerCaseNames) {
        if (lowerCaseNames == null || lowerCaseNames.isEmpty()) return List.of();
        String sql = """
                SELECT DISTINCT ON (x."lookup")
                       x."lookup", x."typeId", x."typeName", x."categoryId",
                       x."groupName", x."slotEffectId", x."published"
                FROM (
                    SELECT LOWER(t."typeName")        AS "lookup",
                           t."typeID"                 AS "typeId",
                           t."typeName"               AS "typeName",
                           g."categoryID"             AS "categoryId",
                           g."groupName"              AS "groupName",
                           COALESCE(t.published, 0)   AS "published",
                           (SELECT MIN(te."effectID")
                              FROM evesde."dgmTypeEffects" te
                             WHERE te."typeID" = t."typeID"
                               AND te."effectID" IN (11, 12, 13, 2663, 3772)) AS "slotEffectId"
                    FROM evesde."invTypes" t
                    LEFT JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                    WHERE LOWER(t."typeName") IN (:names)
                ) x
                ORDER BY x."lookup", x."published" DESC, x."typeId"
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("names", lowerCaseNames);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }

    /** Anzeigename einer typeID - fuer Huellen, die nur als ID vorliegen. */
    public List<Tuple> typeNames(List<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return List.of();
        String sql = """
                SELECT t."typeID" AS "typeId", t."typeName" AS "typeName"
                FROM evesde."invTypes" t
                WHERE t."typeID" IN (:typeIds)
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("typeIds", typeIds);
        @SuppressWarnings("unchecked")
        List<Tuple> res = q.getResultList();
        return res;
    }
}

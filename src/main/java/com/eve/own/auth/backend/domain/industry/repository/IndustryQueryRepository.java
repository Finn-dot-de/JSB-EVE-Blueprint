package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.IndustryActivity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Native Abfragen gegen die Industrie-Stammdaten im Schema {@code evesde}.
 *
 * <p>Nativ und nicht JPQL, weil die Stueckliste <em>rekursiv</em> ist: ein Titan
 * besteht aus Capital-Komponenten, die wiederum aus Mineralien und
 * Reaktionsprodukten bestehen. Das leistet nur ein {@code WITH RECURSIVE}. Fuer
 * einen Erebus sind es vier Ebenen mit 21, 44, 8 und 9 Knoten - beherrschbar,
 * aber von Hand nicht zusammenzusetzen.</p>
 *
 * <p>Zwei Dinge, die hier bewusst anders sind als naheliegend:</p>
 * <ul>
 *   <li>Verfolgt werden <b>Fertigung und Reaktionen</b> gemeinsam (SDE 1 und 11).
 *       Nur mit der Fertigung erscheinen Fulleroferrocene und Photonic
 *       Metamaterials als Rohstoff, obwohl sie in einem Reaktor entstehen.</li>
 *   <li>{@code published} ist ein {@code smallint}, kein Boolean. Ohne den
 *       Filter erscheinen Entwickler-Artefakte wie "Test Reaction Blueprint"
 *       in der Vorschlagsliste.</li>
 * </ul>
 */
@Repository
public class IndustryQueryRepository {

    /** Fertigung und Reaktion - die beiden Aktivitaeten, aus denen Gegenstaende hervorgehen. */
    private static final String PRODUCING_ACTIVITIES =
            IndustryActivity.MANUFACTURING + ", " + IndustryActivity.REACTION_SDE;

    /**
     * Tiefenbegrenzung der Rekursion.
     *
     * <p>Der tiefste echte Baum in EVE liegt bei vier Ebenen. Zehn ist reichlich
     * und schuetzt zugleich davor, dass ein Fehler in den Stammdaten die Abfrage
     * endlos laufen laesst - der Pfadschutz allein genuegt dafuer nicht, wenn
     * zwei Blaupausen einander wechselseitig als Material fuehren.</p>
     */
    private static final int MAX_DEPTH = 10;

    @PersistenceContext
    private EntityManager em;

    // ===========================================================
    //  Suche
    // ===========================================================

    /** Ein Treffer der Produktsuche. */
    public record ProductHit(long typeId, String typeName, String groupName, long blueprintTypeId) {}

    /**
     * Sucht baubare Dinge nach einem Namensteil.
     *
     * <p>Nur was tatsaechlich eine Blaupause hat - eine Vorschlagsliste, aus der
     * sich die Haelfte nicht bauen laesst, ist keine Hilfe. Der Gruppenname
     * steht mit dabei, weil "Raven" und "Raven Navy Issue" sonst nicht
     * auseinanderzuhalten sind.</p>
     *
     * <p>Sortiert wird nach Trefferqualitaet: exakter Name zuerst, dann
     * Wortanfang, dann alles Uebrige - sonst steht bei der Eingabe "raven" das
     * "Raven Navy Issue Blueprint" vor der Raven.</p>
     */
    public List<ProductHit> searchProducts(String namePart, int limit) {
        if (namePart == null || namePart.isBlank()) {
            return List.of();
        }
        String needle = namePart.trim().toLowerCase(Locale.ROOT);

        Query query = em.createNativeQuery("""
                SELECT p."productTypeID"  AS type_id,
                       t."typeName"       AS type_name,
                       g."groupName"      AS group_name,
                       p."typeID"         AS blueprint_id
                FROM evesde."industryActivityProducts" p
                JOIN evesde."invTypes" t  ON t."typeID"  = p."productTypeID"
                JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                JOIN evesde."invTypes" bp ON bp."typeID" = p."typeID"
                WHERE p."activityID" IN (%s)
                  AND t."published" = 1
                  AND bp."published" = 1
                  AND lower(t."typeName") LIKE :pattern
                ORDER BY CASE
                             WHEN lower(t."typeName") = :exact THEN 0
                             WHEN lower(t."typeName") LIKE :prefix THEN 1
                             ELSE 2
                         END,
                         length(t."typeName"),
                         t."typeName"
                LIMIT :limit
                """.formatted(PRODUCING_ACTIVITIES), Tuple.class);
        query.setParameter("pattern", "%" + needle + "%");
        query.setParameter("prefix", needle + "%");
        query.setParameter("exact", needle);
        query.setParameter("limit", Math.clamp(limit, 1, 100));

        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(r -> new ProductHit(
                        num(r, "type_id"),
                        str(r, "type_name"),
                        str(r, "group_name"),
                        num(r, "blueprint_id")))
                .toList();
    }

    // ===========================================================
    //  Blaupause
    // ===========================================================

    /**
     * Die Eckdaten einer Blaupause.
     *
     * @param unitsPerRun        wie viele Stueck ein Lauf liefert - nicht immer eins
     * @param maxProductionLimit hoechste Laufzahl je Job, je Blaupause verschieden
     * @param secondsPerRun      Grunddauer eines Laufs, ohne jeden Bonus
     */
    public record BlueprintInfo(long blueprintTypeId, String blueprintName, long productTypeId,
                                String productName, int activityId, long unitsPerRun,
                                long maxProductionLimit, long secondsPerRun) {}

    /** Die Blaupause zu einem Produkt, oder {@code null}, wenn es keine gibt. */
    public BlueprintInfo blueprintFor(long productTypeId) {
        Query query = em.createNativeQuery("""
                SELECT p."typeID"                       AS bp_id,
                       bp."typeName"                    AS bp_name,
                       p."productTypeID"                AS product_id,
                       t."typeName"                     AS product_name,
                       p."activityID"                   AS activity_id,
                       GREATEST(p."quantity", 1)        AS units_per_run,
                       COALESCE(b."maxProductionLimit", 1) AS max_runs,
                       COALESCE(ia."time", 0)           AS seconds_per_run
                FROM evesde."industryActivityProducts" p
                JOIN evesde."invTypes" bp ON bp."typeID" = p."typeID"
                JOIN evesde."invTypes" t  ON t."typeID"  = p."productTypeID"
                LEFT JOIN evesde."industryBlueprints" b ON b."typeID" = p."typeID"
                LEFT JOIN evesde."industryActivity" ia
                       ON ia."typeID" = p."typeID" AND ia."activityID" = p."activityID"
                WHERE p."productTypeID" = :productId
                  AND p."activityID" IN (%s)
                ORDER BY p."activityID"
                LIMIT 1
                """.formatted(PRODUCING_ACTIVITIES), Tuple.class);
        query.setParameter("productId", productTypeId);

        List<Tuple> rows = query.getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Tuple r = rows.getFirst();
        return new BlueprintInfo(
                num(r, "bp_id"), str(r, "bp_name"),
                num(r, "product_id"), str(r, "product_name"),
                (int) num(r, "activity_id"),
                num(r, "units_per_run"), num(r, "max_runs"), num(r, "seconds_per_run"));
    }

    // ===========================================================
    //  Stueckliste
    // ===========================================================

    /**
     * Ein Knoten der Stueckliste.
     *
     * @param quantityPerUnit Menge je <em>einem</em> Endprodukt - kann gebrochen sein,
     *                        wenn ein Lauf mehrere Stueck liefert. Erst beim Umrechnen
     *                        auf Laeufe wird aufgerundet.
     * @param quantityPerRun  Menge je <em>Lauf</em>, also die Zahl, die in den
     *                        Stammdaten steht. Der Unterschied ist keine Feinheit:
     *                        die Titanium-Carbide-Formel braucht 100 Titanium
     *                        Chromide und liefert dabei 10.000 Stueck. Je Stueck
     *                        sind das 0,01 - und wer diese Zahl aufrundet, rechnet
     *                        mit 1 statt 100 und liegt hundertfach daneben.
     * @param sourceKind      woher das Ding kommt, siehe {@link #SOURCE_KINDS}
     */
    public record BomNode(int depth, long typeId, String typeName, Long parentTypeId,
                          double quantityPerUnit, String sourceKind, long unitsPerRun,
                          long quantityPerRun) {}

    /**
     * Die Herkunftsarten.
     *
     * <p>Das Etikett entscheidet, was die Oberflaeche anbietet. Ein PI-Gut laesst
     * sich per Industriejob <em>gar nicht</em> herstellen - wer dort "bauen"
     * anbietet, schickt den Nutzer in eine Sackgasse.</p>
     */
    public static final List<String> SOURCE_KINDS =
            List.of("BUILDABLE", "REACTION", "MINERAL", "PI", "GAS", "RAW");

    /** Eine Materialkante: {@code material} geht in {@code produkt} ein. */
    public record MaterialEdge(long produktTypeId, long materialTypeId) {}

    /**
     * Alle Materialkanten zwischen den Zeilen eines Auftrags.
     *
     * <p>Es gibt sie, weil die Bedarfstabelle sie nicht tragen kann. Dort steht
     * je Auftrag und Typ <em>eine</em> Zeile mit einer Elternangabe - eine
     * Stueckliste ist aber ein Netz: In einem gemessenen Phoenix-Auftrag hat
     * Reinforced Carbon Fiber siebzehn Verbraucher, und von 214 echten Kanten
     * liessen sich 71 ablegen. Wer aus der Tabelle ableiten will, ob ein Bauteil
     * startklar ist, uebersieht zwei Drittel seiner Zutaten.</p>
     *
     * <p>Bewusst live aus den Stammdaten und nicht eingefroren. Eingefroren
     * werden die <em>Mengen</em>, damit der Fortschrittsbalken nicht springt;
     * die Struktur aendert sich nur, wenn CCP ein Rezept aendert - und dann hat
     * sich die echte Baureihenfolge tatsaechlich geaendert.</p>
     *
     * <p>Ohne Mengen, und das ist wichtig: Fuenf Produkte haben mehrere
     * Blaupausen derselben Aktivitaet. Fuer die blosse Frage "haengt das
     * zusammen" ist das unschaedlich, fuer eine Menge waere es falsch.</p>
     */
    public List<MaterialEdge> orderEdges(long orderId) {
        Query query = em.createNativeQuery("""
                SELECT DISTINCT p."productTypeID" AS produkt,
                                m."materialTypeID" AS material
                FROM evesde."industryActivityProducts" p
                JOIN evesde."industryActivityMaterials" m
                  ON m."typeID" = p."typeID" AND m."activityID" = p."activityID"
                WHERE p."activityID" IN (%1$s)
                  -- Beide Enden muessen Zeilen desselben Auftrags sein. Das
                  -- Endprodukt ist keine Bedarfszeile und kommt darum extra
                  -- dazu; ohne es fehlen die Kanten der obersten Ebene.
                  AND (p."productTypeID" IN (
                           SELECT r.type_id FROM industry_order_requirements r
                           WHERE r.order_id = :orderId)
                       OR p."productTypeID" = (
                           SELECT o.product_type_id FROM industry_orders o
                           WHERE o.id = :orderId))
                  AND m."materialTypeID" IN (
                           SELECT r.type_id FROM industry_order_requirements r
                           WHERE r.order_id = :orderId)
                """.formatted(PRODUCING_ACTIVITIES), Tuple.class);
        query.setParameter("orderId", orderId);

        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(r -> new MaterialEdge(num(r, "produkt"), num(r, "material")))
                .toList();
    }

    /**
     * Loest ein Produkt bis auf seine Grundbestandteile auf.
     *
     * <p>Die Mengen beziehen sich auf ein einzelnes Endprodukt und tragen noch
     * keinen einzigen Bonus - das ist Absicht. Boni haengen an Blaupause,
     * Struktur und Ort und werden erst beim Rechnen aufgeschlagen; die
     * Stueckliste selbst ist reine Stammdatenkunde.</p>
     *
     * @param maxDepth wie tief aufgeloest wird. 1 liefert nur die unmittelbaren
     *                 Materialien - genau das, was die Oberflaeche zuerst zeigt.
     */
    public List<BomNode> billOfMaterials(long productTypeId, int maxDepth) {
        int depth = Math.clamp(maxDepth, 1, MAX_DEPTH);

        Query query = em.createNativeQuery("""
                WITH RECURSIVE bom AS (
                    SELECT p."productTypeID"  AS parent_id,
                           m."materialTypeID" AS material_id,
                           m."quantity"::numeric / GREATEST(p."quantity", 1) AS menge,
                           1                  AS ebene,
                           ARRAY[p."productTypeID"] AS pfad
                    FROM evesde."industryActivityProducts" p
                    JOIN evesde."industryActivityMaterials" m
                      ON m."typeID" = p."typeID" AND m."activityID" = p."activityID"
                    WHERE p."productTypeID" = :productId
                      AND p."activityID" IN (%1$s)

                    UNION ALL

                    SELECT p."productTypeID",
                           m."materialTypeID",
                           b.menge * m."quantity"::numeric / GREATEST(p."quantity", 1),
                           b.ebene + 1,
                           b.pfad || p."productTypeID"
                    FROM bom b
                    JOIN evesde."industryActivityProducts" p
                      ON p."productTypeID" = b.material_id AND p."activityID" IN (%1$s)
                    JOIN evesde."industryActivityMaterials" m
                      ON m."typeID" = p."typeID" AND m."activityID" = p."activityID"
                    WHERE b.ebene < :maxDepth
                      AND NOT (p."productTypeID" = ANY(b.pfad))
                )
                SELECT b.ebene                       AS ebene,
                       b.material_id                 AS type_id,
                       t."typeName"                  AS type_name,
                       b.parent_id                   AS parent_id,
                       SUM(b.menge)                  AS menge,
                       COALESCE(MAX(GREATEST(herk."quantity", 1)), 1) AS units_per_run,
                       MAX(m_roh."quantity")         AS quantity_per_run,
                       CASE
                           WHEN MAX(herk."activityID") = %2$d THEN 'REACTION'
                           WHEN MAX(herk."activityID") = %3$d THEN 'BUILDABLE'
                           WHEN g."groupName" = 'Mineral' THEN 'MINERAL'
                           WHEN EXISTS (SELECT 1 FROM evesde."planetSchematicsTypeMap" pm
                                        WHERE pm."typeID" = b.material_id) THEN 'PI'
                           WHEN g."groupName" = 'Harvestable Cloud' THEN 'GAS'
                           ELSE 'RAW'
                       END                           AS source_kind
                FROM bom b
                JOIN evesde."invTypes" t  ON t."typeID"  = b.material_id
                JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                LEFT JOIN evesde."industryActivityProducts" herk
                       ON herk."productTypeID" = b.material_id
                      AND herk."activityID" IN (%1$s)
                -- Die rohe Menge je Lauf, wie sie in den Stammdaten steht.
                LEFT JOIN evesde."industryActivityProducts" p_eltern
                       ON p_eltern."productTypeID" = b.parent_id
                      AND p_eltern."activityID" IN (%1$s)
                LEFT JOIN evesde."industryActivityMaterials" m_roh
                       ON m_roh."typeID" = p_eltern."typeID"
                      AND m_roh."activityID" = p_eltern."activityID"
                      AND m_roh."materialTypeID" = b.material_id
                GROUP BY b.ebene, b.material_id, t."typeName", b.parent_id, g."groupName"
                ORDER BY b.ebene, SUM(b.menge) DESC
                """.formatted(PRODUCING_ACTIVITIES,
                IndustryActivity.REACTION_SDE, IndustryActivity.MANUFACTURING), Tuple.class);
        query.setParameter("productId", productTypeId);
        query.setParameter("maxDepth", depth);

        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(r -> new BomNode(
                        (int) num(r, "ebene"),
                        num(r, "type_id"),
                        str(r, "type_name"),
                        numOrNull(r, "parent_id"),
                        ((Number) r.get("menge")).doubleValue(),
                        str(r, "source_kind"),
                        num(r, "units_per_run"),
                        num(r, "quantity_per_run")))
                .toList();
    }

    // ===========================================================
    //  Bestand
    // ===========================================================

    /**
     * Was von einem Material im Kontoverbund liegt, getrennt nach Ort.
     *
     * @param quantity     die Menge im gefragten Bausystem
     * @param elsewhere    die Menge im uebrigen EVE
     * @param onCharacters auf wie vielen Charakteren die Menge im Bausystem verteilt ist
     */
    public record Holding(long typeId, long quantity, long elsewhere, int onCharacters) {

        /** Der gesamte Bestand, unabhaengig vom Ort. */
        public long total() {
            return quantity + elsewhere;
        }
    }

    /**
     * Summiert den Bestand der genannten Materialien ueber die Charaktere eines Kontos.
     *
     * <p>Die Charakterliste kommt von aussen und muss bereits auf das Konto des
     * Anfragenden eingeschraenkt sein - dieselbe Zusage wie bei den Assets:
     * niemand sieht fremde Hangars.</p>
     *
     * <p>Getrennt wird nach dem Bausystem, weil eine Gesamtsumme ueber ganz EVE
     * die falsche Frage beantwortet: Material in Delve hilft beim Bauen in Branch
     * nicht. Aufgeteilt statt gefiltert, weil es sonst so aussaehe, als besaesse
     * man es gar nicht - und die Einkaufsliste wuerde zum Kauf von Dingen raten,
     * die im eigenen Hangar stehen.</p>
     *
     * <p>Der {@code COALESCE} auf die {@code location_id} ist notwendig und keine
     * Verzierung: bei Material, das im All schwebt statt in einer Station,
     * fuehrt {@code asset_locations} die Art {@code SOLAR_SYSTEM}, und dort
     * <em>ist</em> die {@code location_id} bereits die Systemkennung. Wer die
     * Bedingung auf {@code system_id} allein vereinfacht, verliert genau dieses
     * Material.</p>
     *
     * <p>Ein Standort ohne bekanntes System zaehlt bewusst als "anderswo".
     * Falsch als "vor Ort" gezaehlt heisst: jemand fliegt hin und der Job startet
     * nicht. Falsch als "anderswo" heisst: ein ueberfluessiger Kauf droht - und
     * den faengt die zweite Spalte ab, solange sie sichtbar ist.</p>
     *
     * @param systemId das Bausystem, oder {@code null} fuer "ueberall"
     */
    public List<Holding> holdings(Collection<Long> characterIds, Collection<Long> typeIds,
                                  Long systemId) {
        if (characterIds == null || characterIds.isEmpty() || typeIds == null || typeIds.isEmpty()) {
            return List.of();
        }
        // Ohne Bausystem gilt alles als "vor Ort" - die alte Bedeutung. Das ist
        // kein Uebergang, sondern Dauerzustand: der Bauort bleibt freiwillig.
        String amOrt = systemId == null ? "TRUE" : """
                COALESCE(l.system_id,
                         CASE WHEN l.location_kind = 'SOLAR_SYSTEM'
                              THEN l.location_id END) = :systemId
                """;

        // "IS NOT TRUE" statt "NOT": ist das System unbekannt, ist der Vergleich
        // weder wahr noch falsch, sondern NULL - und ein NOT darauf bleibt NULL.
        // Die Zeile fiele dann aus BEIDEN Summen heraus und das Material waere
        // spurlos weg. So landet alles Unbekannte verlaesslich bei "anderswo".
        Query query = em.createNativeQuery("""
                SELECT a.type_id AS type_id,
                       COALESCE(SUM(a.quantity) FILTER (WHERE %1$s), 0) AS im_system,
                       COALESCE(SUM(a.quantity) FILTER (WHERE (%1$s) IS NOT TRUE), 0) AS anderswo,
                       COUNT(DISTINCT a.character_id) FILTER (WHERE %1$s) AS chars
                FROM character_assets a
                LEFT JOIN asset_locations l ON l.location_id = a.root_location_id
                WHERE a.character_id IN (:characterIds)
                  AND a.type_id IN (:typeIds)
                GROUP BY a.type_id
                """.formatted(amOrt), Tuple.class);
        query.setParameter("characterIds", characterIds);
        query.setParameter("typeIds", typeIds);
        if (systemId != null) {
            query.setParameter("systemId", systemId);
        }

        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(r -> new Holding(num(r, "type_id"), num(r, "im_system"),
                        num(r, "anderswo"), (int) num(r, "chars")))
                .toList();
    }

    /**
     * Das <b>verpackte</b> Volumen je Stueck.
     *
     * <p>Bewusst {@code invVolumes} vor {@code invTypes.volume}: letzteres ist bei
     * Schiffen das zusammengebaute Volumen. Eine Raven steht dort mit 470.000 m3,
     * verpackt sind es 50.000 - wer die falsche Spalte nimmt, liegt bei jeder
     * Transportaussage um Faktor neun daneben. {@code invVolumes} enthaelt nur
     * Schiffe und Module; fuer Erze und Mineralien ist {@code invTypes.volume}
     * bereits das richtige Volumen.</p>
     *
     * <p>Als {@code double} und nicht gerundet: Tritanium hat 0,01 m3 je Einheit.
     * Auf ganze Kubikmeter aufgerundet waere das ein hundertfacher Fehler - und
     * er ginge unbemerkt in jede Frachtkostenrechnung ein.</p>
     */
    public Map<Long, Double> packagedVolumes(Collection<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) {
            return Map.of();
        }
        Query query = em.createNativeQuery("""
                SELECT t."typeID" AS type_id,
                       COALESCE(v."volume", t."volume", 0) AS volumen
                FROM evesde."invTypes" t
                LEFT JOIN evesde."invVolumes" v ON v."typeID" = t."typeID"
                WHERE t."typeID" IN (:typeIds)
                """, Tuple.class);
        query.setParameter("typeIds", typeIds);

        List<Tuple> rows = query.getResultList();
        Map<Long, Double> karte = new HashMap<>(rows.size());
        for (Tuple r : rows) {
            karte.put(num(r, "type_id"), ((Number) r.get("volumen")).doubleValue());
        }
        return karte;
    }

    // ===========================================================
    //  Beschaffung
    // ===========================================================

    /**
     * Ein Erz, aus dem sich ein Mineral gewinnen laesst.
     *
     * @param mineralPerBatch wie viel des gesuchten Minerals eine Portion liefert
     * @param mineralCount    wie viele verschiedene Minerale das Erz insgesamt liefert.
     *                        Ist die Zahl groesser als eins, faellt beim Aufbereiten
     *                        mehr an als gesucht - die Rechnung wird dadurch eher zu
     *                        teuer als zu billig.
     */
    public record OreSource(long typeId, String typeName, long portionSize, long mineralPerBatch,
                            double volumePerUnit, Double jitaSell, int mineralCount) {}

    /**
     * Die komprimierten Erze, aus denen ein Mineral gewonnen werden kann.
     *
     * <p>Bewusst nur komprimierte: rohes Erz ist beim Transport um Groessenordnungen
     * schlechter. Nachgerechnet fuer 5,2 Millionen Tritanium - als Mineral 52.000 m3,
     * als rohes Veldspar 162.500 m3, als komprimiertes 1.625 m3. Wer rohes Erz
     * vorschlaegt, verteuert den Transport auf das Hundertfache.</p>
     *
     * <p>Ohne Marktpreis wird ein Erz mitgeliefert, aber mit {@code jitaSell = null} -
     * der Aufrufer soll es dann sichtbar weglassen statt es mit null ISK zu bewerten.</p>
     */
    public List<OreSource> compressedOreSourcesFor(long mineralTypeId) {
        Query query = em.createNativeQuery("""
                SELECT ore."typeID"                            AS type_id,
                       ore."typeName"                          AS type_name,
                       GREATEST(ore."portionSize", 1)          AS portion_size,
                       tm."quantity"                           AS mineral_per_batch,
                       COALESCE(v."volume", ore."volume", 0)   AS volume_per_unit,
                       p.jita_sell                             AS jita_sell,
                       (SELECT count(*) FROM evesde."invTypeMaterials" x
                        WHERE x."typeID" = ore."typeID")       AS mineral_count
                FROM evesde."invTypeMaterials" tm
                JOIN evesde."invTypes" ore     ON ore."typeID"   = tm."typeID"
                JOIN evesde."invGroups" g      ON g."groupID"    = ore."groupID"
                JOIN evesde."invCategories" c  ON c."categoryID" = g."categoryID"
                LEFT JOIN evesde."invVolumes" v ON v."typeID"    = ore."typeID"
                LEFT JOIN market_prices p       ON p.type_id     = ore."typeID"
                WHERE tm."materialTypeID" = :mineralId
                  AND c."categoryName" = 'Asteroid'
                  AND ore."published" = 1
                  AND ore."typeName" LIKE 'Compressed %'
                  AND tm."quantity" > 0
                ORDER BY tm."quantity" DESC
                """, Tuple.class);
        query.setParameter("mineralId", mineralTypeId);

        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(r -> new OreSource(
                        num(r, "type_id"), str(r, "type_name"),
                        num(r, "portion_size"), num(r, "mineral_per_batch"),
                        ((Number) r.get("volume_per_unit")).doubleValue(),
                        r.get("jita_sell") == null ? null : ((Number) r.get("jita_sell")).doubleValue(),
                        (int) num(r, "mineral_count")))
                .toList();
    }

    /**
     * Alle Materialien einer Portion Erz, nicht nur ein gesuchtes.
     *
     * <p>Braucht, wer die Nebenprodukte gegenrechnen will. Compressed Zeolites
     * liefert je hundert Einheiten 8000 Pyerite, 400 Mexallon und 65 Atmospheric
     * Gases - wer nur die erste Zahl sieht, haelt das Erz fuer teurer, als es
     * ist.</p>
     *
     * <p>Die Mengen stehen je {@code portionSize}, nicht je Stueck.</p>
     */
    public Map<Long, Long> materialsPerPortion(long oreTypeId) {
        Query query = em.createNativeQuery("""
                SELECT "materialTypeID" AS material_id, "quantity" AS menge
                FROM evesde."invTypeMaterials"
                WHERE "typeID" = :oreId AND "quantity" > 0
                """, Tuple.class);
        query.setParameter("oreId", oreTypeId);

        List<Tuple> rows = query.getResultList();
        Map<Long, Long> je = new HashMap<>(rows.size());
        for (Tuple r : rows) {
            je.put(num(r, "material_id"), num(r, "menge"));
        }
        return je;
    }

    /**
     * Die fuer die Ausbeute massgeblichen Skillstufen eines Kontos.
     *
     * @param reprocessing der allgemeine Skill Reprocessing
     * @param efficiency   Reprocessing Efficiency
     * @param oreSpecific  der Gruppenskill zu genau diesem Erz
     */
    public record ReprocessingSkills(int reprocessing, int efficiency, int oreSpecific) {}

    /** Reprocessing - drei Prozent Ausbeute je Stufe. */
    private static final long SKILL_REPROCESSING = 3385L;

    /** Reprocessing Efficiency - zwei Prozent je Stufe. */
    private static final long SKILL_REPROCESSING_EFFICIENCY = 3389L;

    /**
     * Das Attribut, in dem ein Erz seinen Aufbereitungs-Skill fuehrt.
     *
     * <p>Wichtig, weil CCP die frueheren Einzelskills abgeloest hat: es gibt kein
     * "Veldspar Processing" mehr, das jemand haette - Veldspar und Scordite
     * verlangen heute beide "Simple Ore Processing". Wer die alten Namen fest
     * verdrahtet, findet nie einen Treffer und rechnet dauerhaft mit Stufe null.</p>
     */
    private static final long ATTRIBUTE_REPROCESSING_SKILL = 790L;

    /**
     * Die besten Aufbereitungs-Skills im Kontoverbund fuer ein bestimmtes Erz.
     *
     * <p>Das Beste aus allen Charakteren, denn aufbereiten kann derjenige, der es
     * am besten kann - das Erz laesst sich innerhalb des Kontos umlagern.</p>
     */
    public ReprocessingSkills reprocessingSkills(Collection<Long> characterIds, long oreTypeId) {
        if (characterIds == null || characterIds.isEmpty()) {
            return new ReprocessingSkills(0, 0, 0);
        }
        Query query = em.createNativeQuery("""
                WITH erzskill AS (
                    SELECT CAST(COALESCE(ta."valueInt", ta."valueFloat") AS BIGINT) AS skill_id
                    FROM evesde."dgmTypeAttributes" ta
                    WHERE ta."typeID" = :oreId AND ta."attributeID" = :attr
                )
                SELECT
                    COALESCE(MAX(s.trained_level) FILTER (WHERE s.skill_type_id = :allgemein), 0) AS allgemein,
                    COALESCE(MAX(s.trained_level) FILTER (WHERE s.skill_type_id = :effizienz), 0) AS effizienz,
                    COALESCE(MAX(s.trained_level) FILTER (
                        WHERE s.skill_type_id IN (SELECT skill_id FROM erzskill)), 0) AS erzspezifisch
                FROM character_skills s
                WHERE s.character_id IN (:characterIds)
                """, Tuple.class);
        query.setParameter("oreId", oreTypeId);
        query.setParameter("attr", ATTRIBUTE_REPROCESSING_SKILL);
        query.setParameter("allgemein", SKILL_REPROCESSING);
        query.setParameter("effizienz", SKILL_REPROCESSING_EFFICIENCY);
        query.setParameter("characterIds", characterIds);

        List<Tuple> rows = query.getResultList();
        if (rows.isEmpty()) {
            return new ReprocessingSkills(0, 0, 0);
        }
        Tuple r = rows.getFirst();
        return new ReprocessingSkills(
                (int) num(r, "allgemein"), (int) num(r, "effizienz"), (int) num(r, "erzspezifisch"));
    }

    /** Ob ein Typ ein Mineral ist - nur dort lohnt der Blick auf Erze. */
    public boolean isMineral(long typeId) {
        Query query = em.createNativeQuery("""
                SELECT 1 FROM evesde."invTypes" t
                JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                WHERE t."typeID" = :typeId AND g."groupName" = 'Mineral'
                """);
        query.setParameter("typeId", typeId);
        return !query.getResultList().isEmpty();
    }

    /** Der Jita-Verkaufspreis eines Typs, oder {@code null}. */
    public Double jitaSell(long typeId) {
        Query query = em.createNativeQuery(
                "SELECT jita_sell FROM market_prices WHERE type_id = :typeId AND jita_sell IS NOT NULL");
        query.setParameter("typeId", typeId);
        List<?> rows = query.getResultList();
        return rows.isEmpty() ? null : ((Number) rows.getFirst()).doubleValue();
    }

    // ===========================================================
    //  Preisrelevante Typen
    // ===========================================================

    /**
     * Alle Typen, fuer die der Assistent einen Marktpreis braucht.
     *
     * <p>Der bestehende Preisabgleich holt nur, was in den Hangars liegt. Das
     * genuegt fuer die Bestandsbewertung, aber nicht fuer eine Beschaffungsfrage:
     * um zu vergleichen, ob Tritanium oder komprimiertes Veldspar guenstiger ist,
     * braucht es den Preis des Erzes - und das liegt eben gerade <em>nicht</em>
     * im Hangar, sonst muesste man es nicht kaufen. Nachgezaehlt: von 186
     * komprimierten Erzen hatten zehn einen Preis.</p>
     *
     * <p>Erfasst wird, was in einer Stueckliste vorkommt, was daraus entsteht,
     * und alle Erze - roh wie komprimiert.</p>
     */
    public List<Long> priceRelevantTypeIds() {
        Query query = em.createNativeQuery("""
                SELECT DISTINCT t."typeID" AS type_id
                FROM evesde."invTypes" t
                JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                JOIN evesde."invCategories" c ON c."categoryID" = g."categoryID"
                WHERE t."published" = 1
                  AND (
                        t."typeID" IN (SELECT "materialTypeID" FROM evesde."industryActivityMaterials"
                                       WHERE "activityID" IN (%s))
                     OR t."typeID" IN (SELECT "productTypeID" FROM evesde."industryActivityProducts"
                                       WHERE "activityID" IN (%s))
                     OR c."categoryName" = 'Asteroid'
                  )
                """.formatted(PRODUCING_ACTIVITIES, PRODUCING_ACTIVITIES), Tuple.class);

        List<Tuple> rows = query.getResultList();
        return rows.stream().map(r -> num(r, "type_id")).toList();
    }

    // ===========================================================
    //  Entfernung
    // ===========================================================

    /** Jita 4-4 - der Bezugspunkt jeder Beschaffungsrechnung in EVE. */
    public static final long JITA_SYSTEM_ID = 30000142L;

    /**
     * Die Sprungentfernung <em>aller</em> Systeme von Jita, in einem Zug.
     *
     * <p>Bewusst alles auf einmal und nicht je Anfrage: die Breitensuche kostet
     * rund eine Sekunde, und sie berechnet ohnehin die ganze Karte - eine
     * Abfrage fuer ein einzelnes System wirft das Ergebnis nur weg. Gemessen:
     * 5.228 ueber Tore erreichbare Systeme, das weiteste 57 Sprünge entfernt.</p>
     *
     * <p>Die Sprungdaten liegen im SDE und aendern sich zur Laufzeit nie. Der
     * Aufrufer darf das Ergebnis also dauerhaft behalten.</p>
     *
     * <p>Nicht enthaltene Systeme sind ueber Tore nicht erreichbar - Wurmloecher
     * und einige abgeschnittene Regionen. Dort ist "nicht erreichbar" die
     * ehrliche Antwort und nicht etwa eine grosse Zahl.</p>
     */
    public Map<Long, Integer> allJumpsFromJita() {
        Query query = em.createNativeQuery("""
                WITH RECURSIVE reise AS (
                    -- CAST(...) statt der Kurzform ::bigint: die beiden
                    -- Doppelpunkte verschmelzen sonst mit dem Parameternamen,
                    -- und Hibernate sucht nach einem Parameter "jita::bigint".
                    SELECT CAST(:jita AS bigint) AS system_id, 0 AS spruenge
                    UNION
                    SELECT j."toSolarSystemID", r.spruenge + 1
                    FROM reise r
                    JOIN evesde."mapSolarSystemJumps" j ON j."fromSolarSystemID" = r.system_id
                    WHERE r.spruenge < :maxJumps
                )
                SELECT system_id, MIN(spruenge) AS spruenge FROM reise GROUP BY system_id
                """, Tuple.class);
        query.setParameter("jita", JITA_SYSTEM_ID);
        query.setParameter("maxJumps", MAX_JUMPS);

        List<Tuple> rows = query.getResultList();
        Map<Long, Integer> karte = new HashMap<>(rows.size());
        for (Tuple r : rows) {
            karte.put(num(r, "system_id"), (int) num(r, "spruenge"));
        }
        return karte;
    }

    /**
     * Obergrenze der Sprungsuche.
     *
     * <p>Die weitesten Ecken von New Eden liegen rund 100 Sprünge auseinander.
     * Wer darueber hinaus sucht, sucht in einem anderen Universum.</p>
     */
    private static final int MAX_JUMPS = 120;

    // ===========================================================
    //  Namen aus den Stammdaten
    // ===========================================================

    /** Der Name eines Typs - etwa "Tatara" zu 35836. */
    public java.util.Optional<String> typeName(Long typeId) {
        if (typeId == null) {
            return java.util.Optional.empty();
        }
        Query query = em.createNativeQuery("""
                SELECT "typeName" FROM evesde."invTypes" WHERE "typeID" = :typeId
                """);
        query.setParameter("typeId", typeId);
        List<?> rows = query.getResultList();
        return rows.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(rows.getFirst().toString());
    }

    /**
     * Name, Sicherheitsstatus und Region eines Systems.
     *
     * @param region kann leer sein - Wurmlochsysteme haben keinen Regionsnamen,
     *               der jemandem etwas sagt
     */
    public record SystemInfo(long id, String name, double security, String region) {}

    /**
     * Die Eckdaten eines Sonnensystems.
     *
     * <p>Der Sicherheitsstatus zaehlt hier mehr als die Zierde: er bestimmt den
     * Sicherheitsfaktor der Struktur-Rigs und damit den Materialbedarf.</p>
     */
    public java.util.Optional<SystemInfo> systemInfo(Long solarSystemId) {
        if (solarSystemId == null) {
            return java.util.Optional.empty();
        }
        Query query = em.createNativeQuery("""
                SELECT "solarSystemID" AS id, "solarSystemName" AS name, "security" AS sec
                FROM evesde."mapSolarSystems" WHERE "solarSystemID" = :id
                """, Tuple.class);
        query.setParameter("id", solarSystemId);
        List<Tuple> rows = query.getResultList();
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Tuple r = rows.getFirst();
        Object sec = r.get("sec");
        return java.util.Optional.of(new SystemInfo(
                num(r, "id"), str(r, "name"),
                sec == null ? 0.0 : ((Number) sec).doubleValue(), null));
    }

    /**
     * Sucht Sonnensysteme nach einem Namensteil - fuer die Bauortsuche.
     *
     * <p>Praefix statt Teilzeichenkette: bei 8490 Systemen findet ein "an" in der
     * Mitte hunderte Treffer, von denen keiner der gemeinte ist.</p>
     */
    public List<SystemInfo> searchSystems(String namePart, int limit) {
        if (namePart == null || namePart.isBlank()) {
            return List.of();
        }
        Query query = em.createNativeQuery("""
                SELECT s."solarSystemID"    AS id,
                       s."solarSystemName"  AS name,
                       s."security"         AS sec,
                       r."regionName"       AS region
                FROM evesde."mapSolarSystems" s
                LEFT JOIN evesde."mapRegions" r ON r."regionID" = s."regionID"
                WHERE lower(s."solarSystemName") LIKE :pattern
                ORDER BY length(s."solarSystemName"), s."solarSystemName"
                LIMIT :limit
                """, Tuple.class);
        query.setParameter("pattern", namePart.trim().toLowerCase(Locale.ROOT) + "%");
        query.setParameter("limit", Math.clamp(limit, 1, 50));

        List<Tuple> rows = query.getResultList();
        return rows.stream()
                .map(r -> {
                    Object sec = r.get("sec");
                    return new SystemInfo(num(r, "id"), str(r, "name"),
                            sec == null ? 0.0 : ((Number) sec).doubleValue(),
                            str(r, "region"));
                })
                .toList();
    }

    /**
     * In welchen Systemen ein Konto ueberhaupt Material liegen hat, und wie viele
     * verschiedene Typen dort liegen.
     *
     * <p>Damit die Bauortsuche nicht nur Namen anbietet, sondern gleich sagt, wo
     * schon etwas steht. Wer 147 Systeme mit Material hat - so viele sind es hier
     * wirklich - trifft die Wahl sonst blind.</p>
     *
     * <p>Der {@code COALESCE} ist kein Schoenheitsfehler: bei Standorten der Art
     * {@code SOLAR_SYSTEM} - Material, das im All schwebt statt in einer Station -
     * bleibt {@code system_id} leer, weil die {@code location_id} bereits die
     * Systemkennung <em>ist</em>. Alle neunzehn solchen Zeilen lassen sich damit
     * gegen {@code mapSolarSystems} aufloesen.</p>
     */
    public Map<Long, Integer> assetSystemsOf(Collection<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return Map.of();
        }
        Query query = em.createNativeQuery("""
                SELECT COALESCE(l.system_id,
                                CASE WHEN l.location_kind = 'SOLAR_SYSTEM'
                                     THEN l.location_id END) AS system_id,
                       COUNT(DISTINCT a.type_id)             AS typen
                FROM character_assets a
                JOIN asset_locations l ON l.location_id = a.root_location_id
                WHERE a.character_id IN (:characterIds)
                GROUP BY 1
                HAVING COALESCE(l.system_id,
                                CASE WHEN l.location_kind = 'SOLAR_SYSTEM'
                                     THEN l.location_id END) IS NOT NULL
                """, Tuple.class);
        query.setParameter("characterIds", characterIds);

        Map<Long, Integer> je = new HashMap<>();
        List<Tuple> rows = query.getResultList();
        for (Tuple r : rows) {
            je.put(num(r, "system_id"), (int) num(r, "typen"));
        }
        return je;
    }

    // ===========================================================
    //  Hilfen
    // ===========================================================

    private static long num(Tuple row, String column) {
        Object value = row.get(column);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Long numOrNull(Tuple row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String str(Tuple row, String column) {
        Object value = row.get(column);
        return value == null ? "" : value.toString();
    }
}

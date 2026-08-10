package com.eve.own.auth.backend.domain.industry.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legt die Indizes an, ohne die der Industrie-Assistent unbenutzbar langsam ist.
 *
 * <p>Warum das hier steht und nicht als Entitaet: die Tabellen gehoeren dem
 * SDE-Schema {@code evesde}. Das ist eingespielte Fremddaten, kein von Hibernate
 * verwaltetes Schema - {@code ddl-auto=update} fasst es nie an. Ohne diesen
 * Schritt entstehen die Indizes auf einer frisch aufgesetzten Datenbank
 * schlicht nicht, und dann laeuft die Namenssuche statt in unter einer
 * Millisekunde in Sekunden.</p>
 *
 * <p>Gemessen an dieser Datenbank: die Suche nach einem Namensteil fiel von
 * 34,9 ms auf 0,77 ms, die rekursive Stueckliste eines Titanen von mehreren
 * Sekunden auf wenige Millisekunden.</p>
 *
 * <p>Idempotent ueber {@code IF NOT EXISTS} - der Lauf kostet bei jedem Start
 * nur einen Blick in den Katalog. Fehler werden protokolliert, aber nicht
 * weitergereicht: eine Anwendung, die wegen eines fehlenden Index gar nicht
 * mehr hochkommt, waere schlimmer als eine langsame Suche. Das kann etwa
 * passieren, wenn der Datenbankbenutzer auf {@code evesde} keine Schreibrechte
 * hat.</p>
 */
@Slf4j
@Component
@Order(200)
public class IndustryIndexMigration implements ApplicationRunner {

    /**
     * Die Trigramm-Erweiterung fuer die unscharfe Namenssuche.
     *
     * <p>Steht getrennt, weil sie als Einzige Datenbank-weit wirkt und in
     * manchen Umgebungen erhoehte Rechte braucht. Schlaegt sie fehl, faellt nur
     * der GIN-Index weg; die Suche laeuft dann ueber den Praefix-Index weiter.</p>
     */
    private static final String TRIGRAM_EXTENSION = "CREATE EXTENSION IF NOT EXISTS pg_trgm";

    /**
     * Die Indizes selbst.
     *
     * <p>Jeder trifft eine Abfrage, die der Assistent tatsaechlich stellt:
     * die beiden {@code INCLUDE}-Indizes auf Produkte und Materialien tragen
     * die rekursive Stueckliste, {@code idx_iam_material} beantwortet die
     * Gegenrichtung ("worin steckt dieses Material"), und die beiden auf
     * {@code typeName} tragen die Vorschlagsliste im Suchfeld.</p>
     */
    private static final List<String> INDEXES = List.of(
            """
            CREATE INDEX IF NOT EXISTS idx_iap_product_activity
                ON evesde."industryActivityProducts" ("productTypeID", "activityID")
                INCLUDE ("typeID", "quantity")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_iap_type_activity
                ON evesde."industryActivityProducts" ("typeID", "activityID")
                INCLUDE ("productTypeID", "quantity")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_iam_type_activity
                ON evesde."industryActivityMaterials" ("typeID", "activityID")
                INCLUDE ("materialTypeID", "quantity")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_iam_material
                ON evesde."industryActivityMaterials" ("materialTypeID", "activityID")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_ias_type_activity
                ON evesde."industryActivitySkills" ("typeID", "activityID")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_iap_prob
                ON evesde."industryActivityProbabilities" ("typeID", "activityID", "productTypeID")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_itm_material
                ON evesde."invTypeMaterials" ("materialTypeID")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_invtypes_groupid
                ON evesde."invTypes" ("groupID")
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_invtypes_typename_lower
                ON evesde."invTypes" (lower("typeName") varchar_pattern_ops)
            """);

    /** Braucht die Trigramm-Erweiterung, deshalb getrennt und nach ihr. */
    private static final String TRIGRAM_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_invtypes_typename_trgm
                ON evesde."invTypes" USING gin ("typeName" gin_trgm_ops)
            """;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int angelegt = 0;
        for (String ddl : INDEXES) {
            angelegt += execute(ddl) ? 1 : 0;
        }
        if (execute(TRIGRAM_EXTENSION)) {
            execute(TRIGRAM_INDEX);
        }
        log.debug("Industrie-Indizes geprüft, {} von {} Anweisungen ohne Fehler",
                angelegt, INDEXES.size());
    }

    /**
     * Fuehrt eine DDL-Anweisung aus und schluckt Fehler bewusst.
     *
     * @return ob es geklappt hat
     */
    private boolean execute(String ddl) {
        try {
            em.createNativeQuery(ddl).executeUpdate();
            return true;
        } catch (RuntimeException e) {
            // Fehlende Rechte auf dem Fremdschema sind der wahrscheinlichste Grund.
            // Die Anwendung laeuft dann langsamer, aber sie laeuft.
            log.warn("Index konnte nicht angelegt werden, die Suche bleibt langsam: {}",
                    e.getMessage());
            return false;
        }
    }
}

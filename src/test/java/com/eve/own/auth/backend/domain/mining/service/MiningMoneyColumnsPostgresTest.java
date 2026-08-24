package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Die Umstellung der Geldspalten gegen ein echtes Postgres.
 *
 * <p><b>Warum das nicht gemockt werden darf.</b> Der ganze Zweck von
 * {@link MiningMoneyColumnMigration} ist eine Aussage ueber die Datenbank:
 * {@code ddl-auto=update} aendert bestehende Spaltentypen nicht, also bleibt
 * eine Spalte {@code double precision}, wenn niemand sie anfasst. Eine
 * Umstellung, bei der genau das passiert, ist keine Umstellung - der Code sieht
 * danach genau aus und rundet weiter still. Ein Mock haette diese Zusage
 * wortlos durchgewunken; nachweisen laesst sie sich nur an
 * {@code information_schema} und an einem Betrag, den ein {@code double} nicht
 * halten kann.</p>
 *
 * <p>Aufbau wie bei {@code HoldingsBySystemTest}: eigenes Schema, das danach
 * wieder abgeraeumt wird; die Tabellen der Anwendung in {@code public} werden
 * nie angefasst. Ist keine Entwicklungsdatenbank erreichbar, wird uebersprungen
 * statt rot zu werden.</p>
 */
@DisplayName("Geldspalten in Postgres")
class MiningMoneyColumnsPostgresTest {

    private static final String SCHEMA = "geldspalten_test";
    private static final String BASIS = "jdbc:postgresql://localhost:5434/eve_own_auth";
    private static final String URL = BASIS + "?currentSchema=" + SCHEMA;

    private static final String BENUTZER = konfig("DB_USER");
    private static final String PASSWORT = konfig("DB_PASSWORD");

    /**
     * Der Drift, der im Bestand tatsaechlich steht: die Summe der PVE-ISK stand
     * mit diesen Nachkommastellen in der Datenbank, die keine Zahlung je hatte.
     */
    private static final String DRIFT = "1319981075.6900005";

    /**
     * Ein Betrag, den ein {@code double} nicht halten kann.
     *
     * <p>Bei 10^15 liegt der Abstand zweier benachbarter {@code double} bei
     * einem Achtel - die beiden letzten Stellen sind dort schlicht nicht mehr
     * darstellbar. Genau daran scheitert die alte Spalte, und zwar lautlos.</p>
     */
    private static final String ZU_GENAU_FUER_DOUBLE = "999999999999999.99";

    private static EntityManagerFactory emf;
    private static EntityManager em;
    private static MiningMoneyColumnMigration migration;

    @BeforeAll
    static void aufsetzen() throws Exception {
        assumeTrue(erreichbar(), "Keine Entwicklungsdatenbank auf localhost:5434 - übersprungen.");

        schemaAufbauen();

        DriverManagerDataSource ds = new DriverManagerDataSource(URL, BENUTZER, PASSWORT);
        ds.setDriverClassName("org.postgresql.Driver");
        emf = entityManagerFactory(ds);
        em = emf.createEntityManager();

        migration = new MiningMoneyColumnMigration();
        var feld = MiningMoneyColumnMigration.class.getDeclaredField("em");
        feld.setAccessible(true);
        feld.set(migration, em);

        // Vorher: genau der Zustand, den ddl-auto=update hinterlaesst.
        assertThat(datentyp("mining_tax_invoices", "total_tax")).isEqualTo("double precision");

        // @Transactional gibt es hier ohne Spring-Proxy nicht - die Klammer
        // steht deshalb von Hand. Sie gehoert zur Aussage: DDL ist in Postgres
        // transaktional, die Spalten kippen gemeinsam oder gar nicht.
        em.getTransaction().begin();
        migration.run(null);
        em.getTransaction().commit();
    }

    @AfterAll
    static void abraeumen() throws SQLException {
        if (em != null) {
            em.close();
            em = null;
        }
        if (emf != null) {
            emf.close();
            emf = null;
        }
        if (erreichbar()) {
            try (Connection c = verbindung(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    @Test
    @DisplayName("keine Geldspalte ist danach noch double precision")
    void keineSpalteBleibtDouble() {
        // DIE ZUSAGE DER GANZEN UMSTELLUNG. Bliebe auch nur eine Spalte
        // double precision, bindet Hibernate den BigDecimal per JDBC dagegen,
        // Postgres castet nach float8 - stillschweigend - und die Anwendung
        // schreibt weiter ungenau. Das waere schlimmer als gar nichts, weil der
        // Quelltext dann behauptet, das Problem sei geloest.
        assertThat(datentyp("mining_tax_invoices", "total_tax")).isEqualTo("numeric");
        assertThat(datentyp("mining_tax_rates", "current_jita_buy")).isEqualTo("numeric");
        assertThat(datentyp("mining_tax_rates", "tax_percentage")).isEqualTo("numeric");
        assertThat(datentyp("character_activity", "value")).isEqualTo("numeric");
    }

    @Test
    @DisplayName("die Betragsspalten tragen zwei Nachkommastellen, der Satz drei")
    void stellenzahlPasstZurBedeutung() {
        // ISK hat ingame genau zwei Nachkommastellen; ein Steuersatz ist ein
        // Faktor und braucht keine zwanzig Stellen.
        assertThat(stellen("mining_tax_invoices", "total_tax")).containsExactly(20, 2);
        assertThat(stellen("character_activity", "value")).containsExactly(20, 2);
        assertThat(stellen("mining_tax_rates", "tax_percentage")).containsExactly(6, 3);
    }

    @Test
    @DisplayName("raeumt den vorhandenen Drift auf, statt ihn festzuschreiben")
    void castRepariertDenDrift() {
        // 1319981075.6900005 war nie eine Zahlung - die Stellen sind erst beim
        // Addieren von doubles entstanden. Postgres castet ueber die kuerzeste
        // Darstellung, die denselben double ergibt; was naeher als ein halber
        // Cent am gemeinten Wert liegt, kommt exakt heraus.
        assertThat(betrag("SELECT value FROM character_activity WHERE id = 1"))
                .isEqualByComparingTo("1319981075.69");
    }

    @Test
    @DisplayName("verliert bei den vorhandenen Rechnungen keine Stelle")
    void bestandBleibtUnveraendert() {
        assertThat(betrag("SELECT total_tax FROM mining_tax_invoices WHERE id = 1"))
                .isEqualByComparingTo("6138868.00");
    }

    @Test
    @DisplayName("haelt danach einen Betrag, den ein double nicht halten kann")
    void haeltWasEinDoubleNichtHaelt() {
        // Der Gegenbeweis in einem Test: derselbe Betrag geht in die
        // umgestellte Spalte und in eine, die double geblieben ist. Nur eine der
        // beiden gibt ihn zurueck.
        em.getTransaction().begin();
        em.createNativeQuery("INSERT INTO mining_tax_invoices (id, main_character_id, month, "
                        + "total_tax) VALUES (99, 1, '2026-01', " + ZU_GENAU_FUER_DOUBLE + ")")
                .executeUpdate();
        em.createNativeQuery("INSERT INTO alte_spalte (id, betrag) VALUES (99, "
                + ZU_GENAU_FUER_DOUBLE + ")").executeUpdate();
        em.getTransaction().commit();

        assertThat(betrag("SELECT total_tax FROM mining_tax_invoices WHERE id = 99"))
                .isEqualByComparingTo(ZU_GENAU_FUER_DOUBLE);
        assertThat(betrag("SELECT betrag::numeric(20,2) FROM alte_spalte WHERE id = 99"))
                .isNotEqualByComparingTo(ZU_GENAU_FUER_DOUBLE);
    }

    @Test
    @DisplayName("laeuft ein zweites Mal, ohne etwas zu tun")
    void istIdempotent() {
        // Der Lauf steht bei jedem Start an. Ein ALTER schreibt die Tabelle neu
        // und nimmt ACCESS EXCLUSIVE - das jedes Mal zu tun, waere ein Neustart,
        // der die Anwendung sperrt.
        em.getTransaction().begin();
        migration.run(null);
        em.getTransaction().commit();

        assertThat(datentyp("mining_tax_invoices", "total_tax")).isEqualTo("numeric");
        assertThat(betrag("SELECT total_tax FROM mining_tax_invoices WHERE id = 1"))
                .isEqualByComparingTo("6138868.00");
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    /**
     * Zugangsdaten aus der Umgebung, ersatzweise aus {@code .env} - genau wie
     * bei {@code HoldingsBySystemTest}. Ein Passwort im Quelltext waere eines im
     * Repository.
     */
    private static String konfig(String schluessel) {
        String ausUmgebung = System.getenv(schluessel);
        if (ausUmgebung != null && !ausUmgebung.isBlank()) {
            return ausUmgebung;
        }
        try {
            for (String zeile : java.nio.file.Files.readAllLines(java.nio.file.Path.of(".env"))) {
                if (zeile.startsWith(schluessel + "=")) {
                    return zeile.substring(schluessel.length() + 1).trim();
                }
            }
        } catch (java.io.IOException e) {
            // Keine .env - dann bleibt es beim Ueberspringen.
        }
        return null;
    }

    private static boolean erreichbar() {
        if (BENUTZER == null || PASSWORT == null) {
            return false;
        }
        try (Connection ignored = verbindung()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Connection verbindung() throws SQLException {
        return DriverManager.getConnection(BASIS, BENUTZER, PASSWORT);
    }

    /** Die drei Tabellen so, wie ddl-auto=update sie hinterlassen hat: alles double. */
    private static void schemaAufbauen() throws SQLException {
        try (Connection c = verbindung(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("SET search_path TO " + SCHEMA);

            s.execute("""
                    CREATE TABLE mining_tax_invoices (
                        id                bigint PRIMARY KEY,
                        main_character_id bigint NOT NULL,
                        month             varchar(255) NOT NULL,
                        total_tax         double precision NOT NULL,
                        details_json      text,
                        CONSTRAINT uk_invoice_month UNIQUE (main_character_id, month)
                    )""");
            s.execute("""
                    CREATE TABLE mining_tax_rates (
                        type_id          bigint PRIMARY KEY,
                        type_name        varchar(255),
                        category         varchar(255),
                        tax_percentage   double precision,
                        current_jita_buy double precision
                    )""");
            s.execute("""
                    CREATE TABLE character_activity (
                        id            bigint PRIMARY KEY,
                        character_id  bigint NOT NULL,
                        activity_type varchar(255) NOT NULL,
                        value         double precision,
                        "timestamp"   timestamptz
                    )""");
            // Bleibt absichtlich double - als Gegenprobe im Test.
            s.execute("CREATE TABLE alte_spalte (id bigint PRIMARY KEY, betrag double precision)");

            s.execute("INSERT INTO mining_tax_invoices VALUES "
                    + "(1, 2118431553, '2026-07', 6138868, '[]')");
            s.execute("INSERT INTO mining_tax_rates VALUES (16262, 'White Glaze', 'ICE', 10, 210200)");
            s.execute("INSERT INTO character_activity (id, character_id, activity_type, value) "
                    + "VALUES (1, 2118431553, 'PVE_ISK', " + DRIFT + ")");
        }
    }

    /** Hibernate ohne eine einzige Entitaet - alle Abfragen hier sind nativ. */
    private static EntityManagerFactory entityManagerFactory(DataSource ds) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(ds);
        bean.setPackagesToScan("com.eve.own.auth.backend.keineentitaeten");
        bean.setPersistenceProvider(new HibernatePersistenceProvider());
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "none");
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        bean.setJpaPropertyMap(props);
        bean.afterPropertiesSet();
        return bean.getObject();
    }

    private static String datentyp(String tabelle, String spalte) {
        List<?> zeilen = em.createNativeQuery("""
                        SELECT data_type FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = :t AND column_name = :c
                        """)
                .setParameter("t", tabelle)
                .setParameter("c", spalte)
                .getResultList();
        return zeilen.isEmpty() ? null : String.valueOf(zeilen.getFirst());
    }

    /** @return Genauigkeit und Nachkommastellen der Spalte */
    private static List<Integer> stellen(String tabelle, String spalte) {
        Object[] zeile = (Object[]) em.createNativeQuery("""
                        SELECT numeric_precision, numeric_scale FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = :t AND column_name = :c
                        """)
                .setParameter("t", tabelle)
                .setParameter("c", spalte)
                .getSingleResult();
        return List.of(((Number) zeile[0]).intValue(), ((Number) zeile[1]).intValue());
    }

    private static BigDecimal betrag(String sql) {
        return new BigDecimal(String.valueOf(em.createNativeQuery(sql).getSingleResult()));
    }
}

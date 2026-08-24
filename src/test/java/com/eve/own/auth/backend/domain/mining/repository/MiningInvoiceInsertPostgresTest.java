package com.eve.own.auth.backend.domain.mining.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Das Einfrieren einer Monatsrechnung gegen ein echtes Postgres.
 *
 * <p><b>Warum das nicht gemockt werden darf.</b> Die Zusage von
 * {@link MiningTaxInvoiceRepository#insertIfAbsent} ist eine Aussage ueber
 * Postgres: {@code ON CONFLICT (main_character_id, month) DO NOTHING} loest
 * genau die Eindeutigkeitsbedingung auf, an der zwei gleichzeitige Schreiber
 * bisher gescheitert sind. Ob die Spaltenliste im {@code ON CONFLICT} zu einer
 * tatsaechlich vorhandenen Bedingung passt, weiss nur die Datenbank - passt sie
 * nicht, ist die Anweisung selbst ein Fehler, und ein Mock haette das wortlos
 * durchgewunken.</p>
 *
 * <p>Die Anweisung wird ausdruecklich <b>aus der Annotation gelesen</b> und
 * nicht im Test abgeschrieben. Eine abgeschriebene Kopie prueft sonst
 * irgendwann etwas anderes als das, was ausgeliefert wird.</p>
 *
 * <p>Aufbau wie bei {@code HoldingsBySystemTest}: eigenes Schema, das danach
 * wieder abgeraeumt wird. Ohne erreichbare Entwicklungsdatenbank wird
 * uebersprungen statt rot zu werden.</p>
 */
@DisplayName("Einfrieren einer Monatsrechnung in Postgres")
class MiningInvoiceInsertPostgresTest {

    private static final String SCHEMA = "einfrieren_test";
    private static final String BASIS = "jdbc:postgresql://localhost:5434/eve_own_auth";
    private static final String URL = BASIS + "?currentSchema=" + SCHEMA;

    private static final String BENUTZER = konfig("DB_USER");
    private static final String PASSWORT = konfig("DB_PASSWORD");

    private static final Long ACCOUNT = 2_118_431_553L;
    private static final String MONAT = "2026-07";

    /** Die ausgelieferte Anweisung, aus der Annotation gelesen. */
    private static String einfuegen;

    private static EntityManagerFactory emf;

    @BeforeAll
    static void aufsetzen() throws Exception {
        assumeTrue(erreichbar(), "Keine Entwicklungsdatenbank auf localhost:5434 - übersprungen.");

        Method methode = MiningTaxInvoiceRepository.class.getMethod("insertIfAbsent",
                Long.class, String.class, BigDecimal.class, String.class, Instant.class);
        einfuegen = methode.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();

        schemaAufbauen();

        DriverManagerDataSource ds = new DriverManagerDataSource(URL, BENUTZER, PASSWORT);
        ds.setDriverClassName("org.postgresql.Driver");
        emf = entityManagerFactory(ds);
    }

    @AfterAll
    static void abraeumen() throws SQLException {
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

    @BeforeEach
    void leeren() throws SQLException {
        if (erreichbar()) {
            try (Connection c = verbindung(); Statement s = c.createStatement()) {
                s.execute("DELETE FROM " + SCHEMA + ".mining_tax_invoices");
            }
        }
    }

    @Test
    @DisplayName("die Eindeutigkeitsbedingung besteht wirklich")
    void bedingungExistiert() {
        // Erst der Beweis, dass es ueberhaupt etwas aufzuloesen gibt: ein
        // blankes INSERT scheitert am zweiten Mal. Genau das ist zuvor bei jedem
        // zweiten gleichzeitigen Seitenaufruf passiert - als HTTP 500 auf einen
        // reinen Lesevorgang.
        inTransaktion(em -> blankEinfuegen(em, "111.11"));

        assertThatThrownBy(() -> inTransaktion(em -> blankEinfuegen(em, "222.22")))
                .hasMessageContaining("uk_invoice_month");
    }

    @Test
    @DisplayName("schreibt beim ersten Mal und tut beim zweiten Mal nichts")
    void zweiterLaufSchreibtNicht() {
        assertThat(inTransaktion(this::einfuegen)).isEqualTo(1);
        // Kein Fehler, keine zweite Zeile - und vor allem: keine Ausnahme, die
        // den ganzen Lauf und damit alle folgenden Accounts abbraeche.
        assertThat(inTransaktion(this::einfuegen)).isZero();

        assertThat(zeilen()).isEqualTo(1);
    }

    @Test
    @DisplayName("ueberschreibt eine bestehende Rechnung nicht")
    void bestehendeRechnungBleibtStehen() {
        // DO NOTHING und nicht DO UPDATE: eine eingefrorene Rechnung ist ein
        // Beleg. Wuerde sie im Vorbeigehen ueberschrieben, aenderte sich der
        // Betrag mit jedem Preisabgleich - und der Snapshot haette keinen Zweck.
        inTransaktion(em -> blankEinfuegen(em, "6138868.00"));

        assertThat(inTransaktion(this::einfuegen)).isZero();
        assertThat(betrag()).isEqualByComparingTo("6138868.00");
    }

    @Test
    @DisplayName("zwei gleichzeitige Schreiber kollidieren nicht")
    void zweiGleichzeitigeSchreiber() throws Exception {
        // Zwei echte Verbindungen, zwei echte Transaktionen, derselbe Account
        // und derselbe Monat. Der zweite blockiert bis zum Commit des ersten und
        // faellt danach in DO NOTHING - statt in eine
        // DataIntegrityViolationException.
        CyclicBarrier gleichzeitig = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> ersterSchreiber = pool.submit(() -> {
                gleichzeitig.await();
                return inTransaktion(this::einfuegen);
            });
            Future<Integer> zweiterSchreiber = pool.submit(() -> {
                gleichzeitig.await();
                return inTransaktion(this::einfuegen);
            });

            assertThatCode(() -> assertThat(ersterSchreiber.get() + zweiterSchreiber.get())
                    .isEqualTo(1)).doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }

        assertThat(zeilen()).isEqualTo(1);
    }

    @Test
    @DisplayName("haelt den Zeitpunkt des Einfrierens fest")
    void haeltDenZeitpunktFest() {
        // Ein Beleg ohne Datum ist kein Beleg: als eine Rechnung im Bestand
        // nachweislich unvollstaendig war, liess sich nicht mehr sagen, wann sie
        // geschrieben wurde - nur erschliessen. Der Instant geht hier durch eine
        // native Abfrage, also ohne die Typumsetzung der Entitaet.
        Instant vorher = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        inTransaktion(this::einfuegen);

        Object gelesen = einzelwert("SELECT frozen_at FROM mining_tax_invoices "
                + "WHERE main_character_id = " + ACCOUNT);
        Instant eingefroren = gelesen instanceof java.sql.Timestamp stempel
                ? stempel.toInstant()
                : (Instant) gelesen;
        assertThat(eingefroren).isAfterOrEqualTo(vorher);
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private int einfuegen(EntityManager em) {
        return em.createNativeQuery(einfuegen)
                .setParameter("accountId", ACCOUNT)
                .setParameter("month", MONAT)
                .setParameter("totalTax", new BigDecimal("16019868.00"))
                .setParameter("detailsJson", "[]")
                .setParameter("frozenAt", Instant.now())
                .executeUpdate();
    }

    private int blankEinfuegen(EntityManager em, String betrag) {
        return em.createNativeQuery("INSERT INTO mining_tax_invoices "
                        + "(main_character_id, month, total_tax, details_json) "
                        + "VALUES (" + ACCOUNT + ", '" + MONAT + "', " + betrag + ", '[]')")
                .executeUpdate();
    }

    private int inTransaktion(java.util.function.ToIntFunction<EntityManager> arbeit) {
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            try {
                int betroffen = arbeit.applyAsInt(em);
                em.getTransaction().commit();
                return betroffen;
            } catch (RuntimeException e) {
                em.getTransaction().rollback();
                throw e;
            }
        }
    }

    private static Object einzelwert(String sql) {
        try (EntityManager em = emf.createEntityManager()) {
            return em.createNativeQuery(sql).getSingleResult();
        }
    }

    private static long zeilen() {
        return ((Number) einzelwert("SELECT count(*) FROM mining_tax_invoices")).longValue();
    }

    private static BigDecimal betrag() {
        return new BigDecimal(String.valueOf(einzelwert(
                "SELECT total_tax FROM mining_tax_invoices WHERE main_character_id = " + ACCOUNT)));
    }

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

    /** Die Tabelle so, wie die Entitaet sie beschreibt - samt der Bedingung, um die es geht. */
    private static void schemaAufbauen() throws SQLException {
        try (Connection c = verbindung(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("""
                    CREATE TABLE %s.mining_tax_invoices (
                        id                bigserial PRIMARY KEY,
                        main_character_id bigint NOT NULL,
                        month             varchar(255) NOT NULL,
                        total_tax         numeric(20,2) NOT NULL,
                        details_json      text,
                        frozen_at         timestamptz,
                        CONSTRAINT uk_invoice_month UNIQUE (main_character_id, month)
                    )""".formatted(SCHEMA));
        }
    }

    private static EntityManagerFactory entityManagerFactory(DataSource ds) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(ds);
        // Ein leeres Paket: alle Abfragen hier sind nativ und brauchen keine Entitaet.
        bean.setPackagesToScan("com.eve.own.auth.backend.keineentitaeten");
        bean.setPersistenceProvider(new HibernatePersistenceProvider());
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "none");
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        bean.setJpaPropertyMap(props);
        bean.afterPropertiesSet();
        return bean.getObject();
    }
}

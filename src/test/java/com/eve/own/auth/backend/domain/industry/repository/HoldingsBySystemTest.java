package com.eve.own.auth.backend.domain.industry.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.Holding;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Die Bestandsabfrage gegen ein echtes Postgres.
 *
 * <p>Bewusst nicht gegen ein gemocktes Repository. Der Fehler, um den es hier
 * geht, steckt im SQL und nicht in der Java-Logik - ein Mock haette jede der
 * folgenden Zusagen wortlos durchgewunken. Genau das ist in diesem Projekt
 * mehrfach passiert: ein fehlender Ortsbezug, ein Volumen als {@code long} statt
 * {@code double}, eine Menge je Stueck statt je Lauf. Alle drei mit gruenen
 * Tests.</p>
 *
 * <p>Der Test legt sich ein <b>eigenes Schema</b> an und raeumt es wieder ab.
 * Die Tabellen der Anwendung liegen in {@code public} und werden nie angefasst;
 * die Verbindung setzt {@code currentSchema}, sodass unqualifizierte Namen im
 * Testschema landen. Testcontainers waere der sauberere Weg, kommt in dieser
 * Umgebung aber nicht an den Docker-Endpunkt heran.</p>
 *
 * <p>Ist keine Entwicklungsdatenbank erreichbar, wird uebersprungen statt rot zu
 * werden. Das ist eine bewusste Abwaegung: ein Test, der ohne laufende Datenbank
 * fehlschlaegt, wird beim ersten Mal entnervt geloescht.</p>
 */
class HoldingsBySystemTest {

    private static final String SCHEMA = "industrie_test";
    private static final String BASIS = "jdbc:postgresql://localhost:5434/eve_own_auth";
    private static final String URL = BASIS + "?currentSchema=" + SCHEMA;

    /**
     * Zugangsdaten aus der Umgebung, ersatzweise aus {@code .env}.
     *
     * <p>Ausdruecklich nicht im Quelltext: die Datei ist versioniert, {@code .env}
     * ist es nicht. Ein Passwort im Test waere eines im Repository.</p>
     */
    private static final String BENUTZER = konfig("DB_USER");
    private static final String PASSWORT = konfig("DB_PASSWORD");

    private static String konfig(String schluessel) {
        String ausUmgebung = System.getenv(schluessel);
        if (ausUmgebung != null && !ausUmgebung.isBlank()) {
            return ausUmgebung;
        }
        try {
            for (String zeile : java.nio.file.Files.readAllLines(
                    java.nio.file.Path.of(".env"))) {
                if (zeile.startsWith(schluessel + "=")) {
                    return zeile.substring(schluessel.length() + 1).trim();
                }
            }
        } catch (java.io.IOException e) {
            // Keine .env - dann bleibt es beim Ueberspringen.
        }
        return null;
    }

    private static final long BAUSYSTEM = 30_000_142L;
    private static final long ANDERES_SYSTEM = 30_004_759L;

    private static final long TRITANIUM = 34L;
    private static final long PYERITE = 35L;
    private static final long CHARAKTER = 90_000_001L;
    private static final long ZWEITER_CHARAKTER = 90_000_002L;

    private static final long STATION_IM_BAUSYSTEM = 60_003_760L;
    private static final long STRUKTUR_ANDERSWO = 1_035_000_000_001L;
    private static final long STATION_OHNE_SYSTEM = 60_012_574L;

    private static EntityManagerFactory emf;
    private static IndustryQueryRepository repo;

    @BeforeAll
    static void aufsetzen() throws Exception {
        assumeTrue(erreichbar(), "Keine Entwicklungsdatenbank auf localhost:5434 - übersprungen.");

        schemaAufbauen();

        DriverManagerDataSource ds = new DriverManagerDataSource(URL, BENUTZER, PASSWORT);
        ds.setDriverClassName("org.postgresql.Driver");
        emf = entityManagerFactory(ds);

        repo = new IndustryQueryRepository();
        var feld = IndustryQueryRepository.class.getDeclaredField("em");
        feld.setAccessible(true);
        feld.set(repo, emf.createEntityManager());
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

    /** Hibernate ohne eine einzige Entitaet - die Abfrage ist nativ. */
    private static EntityManagerFactory entityManagerFactory(DataSource ds) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(ds);
        // Ein leeres Paket: die Abfrage ist nativ und braucht keine Entitaet.
        // Ohne packagesToScan sucht Spring eine persistence.xml, die es nicht gibt.
        bean.setPackagesToScan("com.eve.own.auth.backend.keineentitaeten");
        bean.setPersistenceProvider(new HibernatePersistenceProvider());
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "none");
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        bean.setJpaPropertyMap(props);
        bean.afterPropertiesSet();
        return bean.getObject();
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

    /** Nur die beiden Tabellen, um die es geht - samt der unbequemen Faelle. */
    private static void schemaAufbauen() throws SQLException {
        try (Connection c = verbindung(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("SET search_path TO " + SCHEMA);

            s.execute("""
                    CREATE TABLE character_assets (
                        item_id          bigint PRIMARY KEY,
                        character_id     bigint NOT NULL,
                        location_id      bigint,
                        quantity         integer,
                        type_id          bigint NOT NULL,
                        root_location_id bigint
                    )""");
            s.execute("""
                    CREATE TABLE asset_locations (
                        location_id   bigint PRIMARY KEY,
                        location_kind varchar(16),
                        name          varchar(255),
                        system_id     bigint
                    )""");

            ort(s, STATION_IM_BAUSYSTEM, "STATION", BAUSYSTEM, "Jita 4-4");
            ort(s, STRUKTUR_ANDERSWO, "STRUCTURE", ANDERES_SYSTEM, "Werft");
            // Material, das im All schwebt: die Kennung IST das System, system_id bleibt leer.
            ort(s, BAUSYSTEM, "SOLAR_SYSTEM", null, "Jita");
            // Eine Station, deren System nie geholt wurde - drei solche gibt es real.
            ort(s, STATION_OHNE_SYSTEM, "STATION", null, "P-VYVL");

            bestand(s, 1, CHARAKTER, TRITANIUM, 100, STATION_IM_BAUSYSTEM);
            bestand(s, 2, CHARAKTER, TRITANIUM, 700, STRUKTUR_ANDERSWO);
            bestand(s, 3, ZWEITER_CHARAKTER, TRITANIUM, 20, STATION_IM_BAUSYSTEM);
            bestand(s, 4, CHARAKTER, TRITANIUM, 5, BAUSYSTEM);
            bestand(s, 5, CHARAKTER, TRITANIUM, 33, STATION_OHNE_SYSTEM);
            bestand(s, 6, CHARAKTER, PYERITE, 900, STRUKTUR_ANDERSWO);
        }
    }

    private static void ort(Statement s, long id, String art, Long systemId, String name)
            throws SQLException {
        s.execute("INSERT INTO %s.asset_locations VALUES (%d, '%s', '%s', %s)"
                .formatted(SCHEMA, id, art, name, systemId == null ? "NULL" : systemId));
    }

    private static void bestand(Statement s, long itemId, long charId, long typeId,
                                int menge, long wurzel) throws SQLException {
        s.execute("INSERT INTO %s.character_assets VALUES (%d, %d, %d, %d, %d, %d)"
                .formatted(SCHEMA, itemId, charId, wurzel, menge, typeId, wurzel));
    }

    private Holding fuer(long typeId, Long systemId) {
        List<Holding> zeilen = repo.holdings(
                List.of(CHARAKTER, ZWEITER_CHARAKTER), Set.of(TRITANIUM, PYERITE), systemId);
        return zeilen.stream()
                .filter(h -> h.typeId() == typeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("keine Zeile für Typ " + typeId));
    }

    @Test
    @DisplayName("zählt Material aus einer Station im Bausystem als vorhanden")
    void stationImBausystem() {
        // 100 vom einen Charakter, 20 vom anderen, 5 aus dem All - alles in Jita.
        assertThat(fuer(TRITANIUM, BAUSYSTEM).quantity()).isEqualTo(125);
    }

    @Test
    @DisplayName("zählt Material aus einem anderen System nicht als vorhanden")
    void andereSystemeZaehlenNicht() {
        Holding pyerite = fuer(PYERITE, BAUSYSTEM);

        // Genau der gemeldete Fall: man besitzt 900 Pyerite, aber nicht hier.
        assertThat(pyerite.quantity()).isZero();
        assertThat(pyerite.elsewhere()).isEqualTo(900);
    }

    @Test
    @DisplayName("findet Material, das im All schwebt statt in einer Station")
    void materialImAll() {
        // Bei Standorten der Art SOLAR_SYSTEM ist die location_id selbst die
        // Systemkennung; system_id bleibt leer. Wer die Bedingung auf system_id
        // allein vereinfacht, verliert dieses Material lautlos.
        assertThat(fuer(TRITANIUM, BAUSYSTEM).quantity()).isEqualTo(125);
        assertThat(fuer(TRITANIUM, ANDERES_SYSTEM).quantity()).isEqualTo(700);
    }

    @Test
    @DisplayName("führt Material an unbekannten Orten als anderswo, nicht als vorhanden")
    void unbekannterOrtZaehltAlsAnderswo() {
        Holding tritanium = fuer(TRITANIUM, BAUSYSTEM);

        // Falsch als "vor Ort" gezählt heißt: jemand fliegt hin und der Job
        // startet nicht. Falsch als "anderswo" heißt nur, dass ein überflüssiger
        // Kauf droht - und den fängt die zweite Spalte ab, solange sie sichtbar ist.
        assertThat(tritanium.quantity()).isEqualTo(125);
        assertThat(tritanium.elsewhere()).isEqualTo(700 + 33);
    }

    @Test
    @DisplayName("ohne Bausystem bleibt die alte Bedeutung: ganz EVE")
    void ohneBausystem() {
        Holding tritanium = fuer(TRITANIUM, null);

        assertThat(tritanium.quantity()).isEqualTo(858);
        assertThat(tritanium.elsewhere()).isZero();
        assertThat(fuer(PYERITE, null).quantity()).isEqualTo(900);
    }

    @Test
    @DisplayName("verliert unter keinen Umständen Material")
    void nichtsVerschwindet() {
        // Der billigste Test mit dem größten Nutzen. Ein NOT auf einen Vergleich
        // mit NULL ergibt wieder NULL, und die Zeile fiele dann aus BEIDEN Summen
        // heraus - das Material wäre spurlos weg, ohne dass irgendwo eine Null
        // auffiele. Diese Invariante schließt das aus.
        for (Long system : new Long[] {BAUSYSTEM, ANDERES_SYSTEM, 30_000_001L, null}) {
            assertThat(fuer(TRITANIUM, system).total())
                    .as("Tritanium bei Bausystem %s", system)
                    .isEqualTo(858);
            assertThat(fuer(PYERITE, system).total())
                    .as("Pyerite bei Bausystem %s", system)
                    .isEqualTo(900);
        }
    }

    @Test
    @DisplayName("die Sprungtabelle von Jita lässt sich überhaupt abfragen")
    void spruengeVonJitaLaufen() {
        // Diese Abfrage lag monatelang tot da, weil kein Auftrag ein Bausystem
        // hatte. Beim ersten echten Aufruf scheiterte sie sofort: die Kurzform
        // ":jita::bigint" verschmilzt den Postgres-Cast mit dem Parameternamen,
        // und Hibernate sucht dann nach einem Parameter "jita::bigint". Die
        // Einkaufsliste verschwand dadurch vollständig.
        //
        // Ein Mock hätte das nie gezeigt - der Fehler steckt im SQL-Text selbst.
        Map<Long, Integer> spruenge = repo.allJumpsFromJita();

        assertThat(spruenge).isNotEmpty();
        // Jita zu sich selbst sind null Sprünge.
        assertThat(spruenge).containsEntry(30_000_142L, 0);
        // Und Perimeter liegt genau einen Sprung entfernt.
        assertThat(spruenge).containsEntry(30_000_144L, 1);
    }

    @Test
    @DisplayName("zählt die Charaktere, auf die sich der Bestand vor Ort verteilt")
    void charakterZahlBeziehtSichAufDenOrt() {
        // "auf 2 Chars" neben einer Menge, die nur ein Charakter am Bauort hat,
        // wäre eine Auskunft über etwas anderes als die Zahl daneben.
        assertThat(fuer(TRITANIUM, BAUSYSTEM).onCharacters()).isEqualTo(2);
        assertThat(fuer(PYERITE, BAUSYSTEM).onCharacters()).isZero();
    }
}

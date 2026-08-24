package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Der Lauf, der die Geldspalten auf {@code numeric} zieht.
 *
 * <p>Diese Klasse ist die Antwort auf eine Eigenheit von
 * {@code ddl-auto=update}: es legt fehlende Spalten an und vergleicht
 * bestehende Typen nie. Eine Eigenschaft von {@code Double} auf
 * {@code BigDecimal} umzustellen, ohne die Spalte mitzunehmen, ist deshalb
 * KEINE Umstellung - Postgres castet den gebundenen Wert stillschweigend nach
 * {@code float8}, und der Code sieht danach nur genau aus.</p>
 *
 * <p>Hier steht die Logik gegen einen gemockten {@code EntityManager}; dass die
 * Spalte in einer echten Datenbank danach tatsaechlich {@code numeric} ist,
 * beweist {@code MiningMoneyColumnsPostgresTest}.</p>
 */
@DisplayName("Umstellung der Geldspalten")
class MiningMoneyColumnMigrationTest {

    private static final List<String> GELDSPALTEN = List.of(
            "mining_tax_invoices.total_tax",
            "mining_tax_rates.current_jita_buy",
            "mining_tax_rates.tax_percentage",
            "character_activity.value");

    /** Was {@code information_schema.columns} melden soll, je "tabelle.spalte". */
    private Map<String, String> typen;

    /** Jede ausgefuehrte DDL-Anweisung, in der Reihenfolge des Laufs. */
    private List<String> ausgefuehrt;

    /** Spalten, deren ALTER absichtlich wirkungslos bleibt. */
    private List<String> altertNichtWirklich;

    /** Spalten, deren ALTER fehlschlaegt. */
    private List<String> altertMitFehler;

    private MiningMoneyColumnMigration migration;

    @BeforeEach
    void setUp() throws Exception {
        typen = new HashMap<>();
        GELDSPALTEN.forEach(spalte -> typen.put(spalte, "double precision"));
        ausgefuehrt = new ArrayList<>();
        altertNichtWirklich = new ArrayList<>();
        altertMitFehler = new ArrayList<>();

        migration = new MiningMoneyColumnMigration();
        var feld = MiningMoneyColumnMigration.class.getDeclaredField("em");
        feld.setAccessible(true);
        feld.set(migration, entityManager());
    }

    /**
     * Ein {@code EntityManager}, der den Katalog nachspielt.
     *
     * <p>Eine Abfrage auf {@code information_schema} beantwortet er aus
     * {@link #typen}; ein {@code ALTER} traegt sich in {@link #ausgefuehrt} ein
     * und setzt den Typ auf {@code numeric} - es sei denn, der Test hat fuer
     * diese Spalte etwas anderes bestellt.</p>
     */
    private EntityManager entityManager() {
        EntityManager em = Mockito.mock(EntityManager.class);
        Mockito.when(em.createNativeQuery(Mockito.anyString())).thenAnswer(aufruf -> {
            String sql = aufruf.getArgument(0);
            return sql.contains("information_schema") ? katalogAbfrage() : ddlAbfrage(sql);
        });
        return em;
    }

    private Query katalogAbfrage() {
        Query query = Mockito.mock(Query.class);
        Map<String, String> parameter = new HashMap<>();
        Mockito.when(query.setParameter(Mockito.anyString(), Mockito.any())).thenAnswer(aufruf -> {
            // Der Cast auf Object ist noetig, sonst waehlt der Uebersetzer
            // String.valueOf(char[]) - getArgument ist generisch.
            parameter.put(aufruf.getArgument(0), String.valueOf((Object) aufruf.getArgument(1)));
            return query;
        });
        Mockito.when(query.getResultList()).thenAnswer(aufruf -> {
            String typ = typen.get(parameter.get("table") + "." + parameter.get("column"));
            return typ == null ? List.of() : List.of(typ);
        });
        return query;
    }

    private Query ddlAbfrage(String sql) {
        Query query = Mockito.mock(Query.class);
        Mockito.when(query.executeUpdate()).thenAnswer(aufruf -> {
            ausgefuehrt.add(sql);
            String spalte = spalteAus(sql);
            if (altertMitFehler.contains(spalte)) {
                throw new IllegalStateException("kein Recht auf die Tabelle");
            }
            if (!altertNichtWirklich.contains(spalte)) {
                typen.put(spalte, "numeric");
            }
            return 0;
        });
        return query;
    }

    /** "ALTER TABLE t ALTER COLUMN c TYPE ..." -&gt; "t.c" */
    private static String spalteAus(String sql) {
        String[] teile = sql.split("\\s+");
        return teile[2] + "." + teile[5];
    }

    @Nested
    @DisplayName("Der Lauf selbst")
    class DerLauf {

        @Test
        @DisplayName("stellt jede Geldspalte auf numeric um")
        void wandeltAlleSpalten() {
            migration.run(null);

            assertThat(ausgefuehrt).hasSize(GELDSPALTEN.size());
            assertThat(ausgefuehrt).contains(
                    "ALTER TABLE mining_tax_invoices ALTER COLUMN total_tax TYPE numeric(20,2) "
                            + "USING total_tax::numeric(20,2)",
                    "ALTER TABLE character_activity ALTER COLUMN value TYPE numeric(20,2) "
                            + "USING value::numeric(20,2)");
            assertThat(typen).containsOnlyKeys(GELDSPALTEN.toArray(String[]::new))
                    .containsValues("numeric");
        }

        @Test
        @DisplayName("gibt dem Steuersatz eine engere Spalte als dem Betrag")
        void satzBekommtEigeneStellenzahl() {
            // Ein Satz ist ein Faktor zwischen 0 und 100, kein Betrag. numeric(20,2)
            // waere nicht falsch, aber es verschweigt, dass hier drei
            // Nachkommastellen gebraucht werden und zwanzig Stellen Unsinn waeren.
            migration.run(null);

            assertThat(ausgefuehrt).contains(
                    "ALTER TABLE mining_tax_rates ALTER COLUMN tax_percentage TYPE numeric(6,3) "
                            + "USING tax_percentage::numeric(6,3)");
        }

        @Test
        @DisplayName("fasst eine bereits umgestellte Spalte nicht noch einmal an")
        void istIdempotent() {
            // Der Lauf laeuft bei JEDEM Start. Ein ALTER schreibt die Tabelle neu
            // und nimmt dabei ACCESS EXCLUSIVE - das jedes Mal zu tun, waere ein
            // Neustart, der die Anwendung sperrt.
            typen.replaceAll((spalte, typ) -> "numeric");

            migration.run(null);

            assertThat(ausgefuehrt).isEmpty();
        }

        @Test
        @DisplayName("laesst eine Spalte aus, die es noch gar nicht gibt")
        void ueberspringtFehlendeSpalte() {
            // Frische Datenbank: Hibernate legt die Tabelle gleich richtig an,
            // weil die Entitaet precision und scale nennt. Ein ALTER auf eine
            // nicht vorhandene Spalte waere dagegen ein Fehler - und dieser Lauf
            // reicht Fehler weiter.
            typen.remove("character_activity.value");

            assertThatCode(() -> migration.run(null)).doesNotThrowAnyException();

            assertThat(ausgefuehrt).noneMatch(sql -> sql.contains("character_activity"));
        }
    }

    @Nested
    @DisplayName("Wenn es schiefgeht")
    class WennEsSchiefgeht {

        @Test
        @DisplayName("bricht den Start ab, wenn das ALTER fehlschlaegt")
        void wirftBeiFehlgeschlagenemAlter() {
            // OHNE DIESE REGEL - also mit dem geschluckten Fehler, den
            // IndustryIndexMigration bewusst verwendet - kaeme die Anwendung hoch
            // und schriebe ab dann BigDecimal-Betraege in eine float8-Spalte.
            // Postgres castet das stillschweigend. Ein roter Container ist besser
            // als eine falsche Rechnung.
            altertMitFehler.add("mining_tax_rates.tax_percentage");

            assertThatThrownBy(() -> migration.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("kein Recht");
        }

        @Test
        @DisplayName("bricht den Start ab, wenn eine Spalte hinterher double precision geblieben ist")
        void wirftWennSpalteDoubleBleibt() {
            // DAS IST DER KERN DER GANZEN KLASSE. Eine Umstellung, bei der die
            // Spalte double precision bleibt, ist keine Umstellung - der Code
            // sieht danach genau aus und ist es nicht. Der Fall tritt nicht nur
            // nach einem Fehlschlag ein: er tritt auch ein, wenn jemand die
            // Datenbank aus einem alten Abzug aufsetzt und der Lauf hier gar
            // nichts zu tun bekam.
            altertNichtWirklich.add("mining_tax_invoices.total_tax");

            assertThatThrownBy(() -> migration.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mining_tax_invoices.total_tax")
                    .hasMessageContaining("double precision");
        }
    }
}

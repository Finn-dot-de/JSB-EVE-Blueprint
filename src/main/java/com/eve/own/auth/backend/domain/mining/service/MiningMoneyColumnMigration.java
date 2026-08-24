package com.eve.own.auth.backend.domain.mining.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stellt die Geldspalten von {@code double precision} auf {@code numeric} um.
 *
 * <h2>Warum es diese Klasse ueberhaupt gibt</h2>
 * <p>{@code ddl-auto=update} legt fehlende Tabellen und Spalten an und
 * vergleicht bestehende Spaltentypen <b>nie</b>. Wird eine Java-Eigenschaft von
 * {@code Double} auf {@link java.math.BigDecimal} umgestellt, bleibt die Spalte
 * also {@code double precision}. Hibernate bindet den {@code BigDecimal} dann
 * per JDBC an eine {@code float8}-Spalte, Postgres castet stillschweigend, und
 * die Anwendung schreibt weiter ungenau - nur sieht der Code jetzt genau aus.
 * Das waere schlimmer als gar nichts, denn ein Fehler, den man sieht, wird
 * gesucht.</p>
 *
 * <p>Ein Migrationsverzeichnis (Flyway, Liquibase) gibt es im Projekt nicht, und
 * es nachtraeglich unter {@code ddl-auto=update} einzuziehen hiesse, ein
 * gewachsenes Schema als Grundlinie einzufrieren - ein eigenes Vorhaben mit
 * eigenem Risiko. Diese Klasse folgt stattdessen dem Muster, das hier schon
 * zweimal steht: {@code IndustryIndexMigration} und {@code NavigationMigration}.</p>
 *
 * <h2>Wo sie sich von {@code IndustryIndexMigration} unterscheidet</h2>
 * <p>Dort werden Fehler bewusst geschluckt - "eine Anwendung, die wegen eines
 * fehlenden Index gar nicht mehr hochkommt, waere schlimmer als eine langsame
 * Suche". Bei Geld gilt das Gegenteil, und zwar aus demselben Grund wie oben:
 * schlaegt das {@code ALTER} fehl und startet die Anwendung trotzdem, schreibt
 * sie ab dann falsche Betraege, ohne dass es auffaellt. Ein roter Container ist
 * besser als eine falsche Rechnung. Deshalb</p>
 * <ul>
 *   <li>wird jeder Fehlschlag weitergeworfen,</li>
 *   <li>laeuft der Lauf unter {@code @Transactional} - DDL ist in Postgres
 *       transaktional, die Spalten kippen gemeinsam oder gar nicht,</li>
 *   <li>und steht danach eine Wächterprüfung, die den Start abbricht, wenn auch
 *       nur eine der Spalten hinterher nicht {@code numeric} ist. Die schuetzt
 *       auch den Fall, dass jemand die Datenbank neu aufsetzt und der Lauf hier
 *       gar nichts zu tun hatte.</li>
 * </ul>
 *
 * <h2>Warum {@code ALTER ... USING} hier gefahrlos ist</h2>
 * <p>Der Cast repariert den vorhandenen Drift, statt ihn festzuschreiben:
 * {@code 1319981075.6900005::numeric(20,2)} ergibt {@code 1319981075.69}.
 * Postgres castet ueber die kuerzeste Darstellung, die denselben {@code double}
 * ergibt - alles, was naeher als ein halber Cent am gemeinten Wert liegt, kommt
 * exakt heraus. Die betroffenen Tabellen sind klein (Zehner- bis
 * Hunderterbereich), die {@code ACCESS EXCLUSIVE}-Sperre dauert Millisekunden.
 * Kein View und kein Index haengt an einer dieser Spalten.</p>
 */
@Slf4j
@Component
@Order(MiningMoneyColumnMigration.RUN_ORDER)
public class MiningMoneyColumnMigration implements ApplicationRunner, Ordered {

    /**
     * Vor allem anderen, was Geld schreibt.
     *
     * <p>Kleiner als {@code IndustryIndexMigration} (200) und kleiner als der
     * Standardwert der uebrigen {@code ApplicationRunner} - insbesondere kleiner
     * als der von {@code MiningTaxRateInitializer}, der beim Start Steuersaetze
     * speichert. Liefe der zuerst, schriebe er den ersten Preis in eine noch
     * ungewandelte {@code float8}-Spalte.</p>
     */
    public static final int RUN_ORDER = 150;

    /** Was Postgres in {@code information_schema.columns} fuer {@code numeric} meldet. */
    private static final String NUMERIC = "numeric";

    /**
     * Eine umzustellende Spalte.
     *
     * @param type der Zieltyp samt Stellen - {@code numeric(20,2)} fuer Betraege
     *     (zwei Nachkommastellen, weil ISK ingame genau zwei hat; zwanzig
     *     Stellen, weil damit jeder Betrag bis 1e18 exakt bleibt), enger fuer
     *     einen Prozentsatz, der ein Faktor ist und kein Betrag
     */
    private record MoneyColumn(String table, String column, String type) {

        String alterStatement() {
            return "ALTER TABLE %s ALTER COLUMN %s TYPE %s USING %s::%s"
                    .formatted(table, column, type, column, type);
        }
    }

    /**
     * Die Spalten, an denen Genauigkeit zaehlt.
     *
     * <p>Ausdruecklich nicht jede Zahl im Projekt. Umgestellt wird, worauf sich
     * jemand verlaesst: was geschuldet, gezahlt und gutgeschrieben wird. Ein
     * Wallet-Schnappschuss ({@code character_stats.wallet_balance}) wird bei
     * jedem Abgleich ueberschrieben und niemand schuldet etwas daraus; die
     * Marktpreise in {@code market_prices} bewerten Besitz und schaetzen
     * Gebuehren, die Mining-Steuer liest sie nicht - sie hat mit
     * {@code mining_tax_rates.current_jita_buy} ihre eigene Preisspalte. Beide
     * bleiben deshalb, wie sie sind. Weniger, aber richtig.</p>
     *
     * <p>{@code character_activity.value} traegt neben {@code TAX_PAYMENT} auch
     * Kopfgelder, Abschuesse und abgebautes Volumen. Eine Spalte hat einen Typ,
     * also wandern sie mit - {@code numeric(20,2)} traegt eine Anzahl von 185
     * ebenso wie 1.472.369,60 m³.</p>
     */
    private static final List<MoneyColumn> COLUMNS = List.of(
            new MoneyColumn("mining_tax_invoices", "total_tax", "numeric(20,2)"),
            new MoneyColumn("mining_tax_rates", "current_jita_buy", "numeric(20,2)"),
            new MoneyColumn("mining_tax_rates", "tax_percentage", "numeric(6,3)"),
            new MoneyColumn("character_activity", "value", "numeric(20,2)"));

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int changed = 0;
        for (MoneyColumn column : COLUMNS) {
            changed += convert(column) ? 1 : 0;
        }
        verifyAll();

        if (changed > 0) {
            log.info("{} Geldspalten von double precision auf numeric umgestellt.", changed);
        }
    }

    /**
     * Dieselbe Reihenfolge noch einmal, diesmal als Schnittstelle.
     *
     * <p>Doppelt gemoppelt, und mit Absicht: anders als
     * {@code IndustryIndexMigration} traegt diese Klasse ein
     * {@code @Transactional}, der Bean ist also ein Proxy. Ob sich die
     * Reihenfolge aus einer Annotation hinter einem Proxy noch finden laesst,
     * haengt an der Art des Proxys; {@link Ordered} haengt an gar nichts. Und
     * ginge die Reihenfolge verloren, faenden Betraege ihren Weg in noch nicht
     * umgestellte Spalten - genau der Zustand, den diese Klasse verhindert.</p>
     */
    @Override
    public int getOrder() {
        return RUN_ORDER;
    }

    /**
     * Stellt eine Spalte um, sofern noetig.
     *
     * @return ob tatsaechlich etwas geaendert wurde
     */
    private boolean convert(MoneyColumn column) {
        String type = dataTypeOf(column);
        if (type == null) {
            // Die Tabelle entsteht erst durch Hibernate - dann legt es sie
            // gleich richtig an, weil die Entitaet precision und scale nennt.
            log.debug("Spalte {}.{} existiert noch nicht, nichts umzustellen.",
                    column.table(), column.column());
            return false;
        }
        if (NUMERIC.equals(type)) {
            return false;
        }

        log.info("Stelle {}.{} von {} auf {} um.", column.table(), column.column(), type, column.type());
        em.createNativeQuery(column.alterStatement()).executeUpdate();
        return true;
    }

    /**
     * Bricht den Start ab, wenn eine der Spalten hinterher nicht {@code numeric}
     * ist.
     *
     * <p>Der ganze Sinn dieser Klasse haengt an dieser Pruefung: ohne sie kann
     * die Anwendung mit einer {@code float8}-Spalte weiterlaufen und schreibt
     * dann Betraege, die genau aussehen und es nicht sind.</p>
     */
    private void verifyAll() {
        for (MoneyColumn column : COLUMNS) {
            String type = dataTypeOf(column);
            if (type != null && !NUMERIC.equals(type)) {
                throw new IllegalStateException(("Geldspalte %s.%s ist %s statt %s. Die Anwendung "
                        + "wuerde jeden Betrag stillschweigend gerundet speichern und dabei genau "
                        + "aussehen. Start abgebrochen.")
                        .formatted(column.table(), column.column(), type, NUMERIC));
            }
        }
    }

    /** @return der von Postgres gemeldete Typ, oder {@code null} wenn es die Spalte nicht gibt */
    private String dataTypeOf(MoneyColumn column) {
        List<?> rows = em.createNativeQuery("""
                        SELECT data_type FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = :table
                          AND column_name = :column
                        """)
                .setParameter("table", column.table())
                .setParameter("column", column.column())
                .getResultList();

        return rows.isEmpty() ? null : String.valueOf(rows.getFirst());
    }
}

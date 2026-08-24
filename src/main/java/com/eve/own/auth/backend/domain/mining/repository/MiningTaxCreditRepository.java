package com.eve.own.auth.backend.domain.mining.repository;

import com.eve.own.auth.backend.domain.mining.entity.MiningTaxCredit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Der Zugriff auf die Steuergutschriften.
 *
 * <p>Es gibt hier bewusst <b>kein</b> Loeschen und kein Filtern nach Zustand.
 * Eine Buchung verschwindet nie (siehe {@link MiningTaxCredit}), und eine
 * Abfrage {@code findByStatus(ACTIVE)} waere die naheliegende Falle: sie liefert
 * fuer die Summenbildung zwar dasselbe Ergebnis wie eine Abfrage ueber alles,
 * aber nur solange jede Gegenbuchung vollstaendig ist. Die Sammelabfragen laden
 * deshalb immer alles und rechnen in Java - dieselbe Handhabung wie bei
 * {@code MiningTaxInvoiceRepository.findAll()} in der Admin-Uebersicht.</p>
 *
 * <p>Die Verlaufsabfragen liefern absteigend nach Zeitpunkt: gefragt ist immer
 * "was ist zuletzt passiert".</p>
 */
@Repository
public interface MiningTaxCreditRepository extends JpaRepository<MiningTaxCredit, Long> {

    /** Der Verlauf eines Accounts - die Frage "wie kam der zu seinem Guthaben?". */
    List<MiningTaxCredit> findByAccountIdOrderByOccurredAtDesc(Long accountId);

    /**
     * Die juengsten Buchungen ueber alle Accounts hinweg.
     *
     * <p>Begrenzt, weil die Tabelle unbegrenzt waechst: eine ungebremste Abfrage
     * laedt irgendwann jede je getaetigte Buchung in den Speicher, nur damit die
     * Oberflaeche die obersten zwanzig zeigt.</p>
     */
    List<MiningTaxCredit> findTop200ByOrderByOccurredAtDesc();

    /**
     * Ob diese Buchung bereits gegengebucht wurde.
     *
     * <p>Die zweite Verteidigungslinie neben der eindeutigen Bedingung auf der
     * Spalte: der Dienst kann damit eine verstaendliche Meldung geben, statt den
     * Aufrufer in eine Verletzung der Datenbankbedingung laufen zu lassen.</p>
     */
    boolean existsByReversalOfCreditId(Long reversalOfCreditId);
}

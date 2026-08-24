package com.eve.own.auth.backend.domain.mining.repository;

import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MiningTaxInvoiceRepository extends JpaRepository<MiningTaxInvoice, Long> {

    List<MiningTaxInvoice> findByMainCharacterId(Long mainCharacterId);

    /**
     * Schreibt eine Monatsrechnung, sofern es sie nicht schon gibt.
     *
     * <p>Der Grund fuer das native {@code ON CONFLICT} statt eines
     * {@code save()}: die Tabelle traegt {@code UNIQUE(main_character_id, month)},
     * und ein {@code save()} auf eine noch nicht existierende Zeile ist ein
     * blankes {@code INSERT}. Zwei Laeufe, die sich ueberlappen - oder ein Lauf
     * neben einem Handgriff - liefen damit in eine
     * {@code DataIntegrityViolationException}. Genau das ist die Bedingung, die
     * hier ausgenutzt wird: sie deckt exakt dieses Spaltenpaar ab, das Aufloesen
     * kostet also nichts extra.</p>
     *
     * <p>{@code DO NOTHING} und nicht {@code DO UPDATE}: eine bestehende Rechnung
     * ist ein Beleg und wird nicht im Vorbeigehen ueberschrieben.</p>
     *
     * @return 1, wenn geschrieben wurde, 0 wenn es die Rechnung schon gab
     */
    @Modifying
    @Query(value = """
            INSERT INTO mining_tax_invoices (main_character_id, month, total_tax, details_json, frozen_at)
            VALUES (:accountId, :month, :totalTax, :detailsJson, :frozenAt)
            ON CONFLICT (main_character_id, month) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("accountId") Long accountId,
                       @Param("month") String month,
                       @Param("totalTax") BigDecimal totalTax,
                       @Param("detailsJson") String detailsJson,
                       @Param("frozenAt") Instant frozenAt);
}

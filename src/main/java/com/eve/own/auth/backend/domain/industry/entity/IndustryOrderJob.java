package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Ordnet einen Job einem Auftrag zu.
 *
 * <p>Diese Verbindung liefert ESI nicht - dort ist ein Job nur ein Job. Sie
 * entsteht automatisch, wenn Produkt und Bauort zu einem laufenden Auftrag
 * passen, und laesst sich von Hand berichtigen.</p>
 *
 * <p>Die Job-Nummer ist Primaerschluessel: ein Job zaehlt hoechstens auf einen
 * Auftrag ein. Eine Zuordnung von Hand wird von der Automatik nicht wieder
 * ueberschrieben - sonst nimmt das Werkzeug dem Nutzer im naechsten Durchlauf
 * seine Berichtigung wieder weg.</p>
 */
@Entity
@Table(name = "industry_order_jobs")
@Getter
@Setter
public class IndustryOrderJob {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** AUTO oder MANUAL. */
    @Column(name = "attributed_by", nullable = false, length = 8)
    private String attributedBy;

    @Column(name = "attributed_at", nullable = false)
    private Instant attributedAt;

    /** Wie viele Stueck dieser Job zum Auftrag beigetragen hat. */
    @Column(name = "units_produced", nullable = false)
    private Long unitsProduced = 0L;
}

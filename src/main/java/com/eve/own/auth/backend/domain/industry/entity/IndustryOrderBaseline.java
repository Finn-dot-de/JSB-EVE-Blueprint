package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Die Nullmessung: was bei Auftragsanlage bereits im Hangar lag.
 *
 * <p>Ohne sie zaehlt ein Raven, der schon vor dem Auftrag herumstand, als
 * Fortschritt - die Ueberschrift saehe beim allerersten Aufruf "1 / 50 fertig"
 * und waere schlicht falsch.</p>
 */
@Entity
@Table(name = "industry_order_baseline")
@Getter
@Setter
public class IndustryOrderBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(name = "quantity_at_start", nullable = false)
    private Long quantityAtStart;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}

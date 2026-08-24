package com.eve.own.auth.backend.domain.mining.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Steuersatz und Preisbasis je abbaubarem Typ.
 *
 * <p>Beide Felder sind {@link BigDecimal}, weil beide in die Steuerformel
 * eingehen und dort mit der abgebauten Menge multipliziert werden: ein Fehler in
 * der Preisbasis wird mit hunderttausenden Einheiten vervielfacht, und
 * {@code 10.0/100} ist in einem {@code double} nicht exakt - dieser Fehler
 * steckt dann in <em>jeder</em> Zeile <em>jeder</em> Rechnung.</p>
 *
 * <p>Der Satz bekommt eine engere Spalte als der Preis: er ist ein Faktor und
 * kein Betrag, der Wertebereich liegt zwischen 0 und 100.</p>
 */
@Entity
@Table(name = "mining_tax_rates")
@Getter @Setter
public class MiningTaxRate {

    @Id
    private Long typeId;

    private String typeName;

    /** "MOON", "ORE", "GAS" oder "ICE". */
    private String category;

    /** Prozent vom Jita-Kaufpreis, z.B. {@code 10.000} fuer 10 Prozent. */
    @Column(name = "tax_percentage", precision = 6, scale = 3)
    private BigDecimal taxPercentage;

    /** Der zuletzt bekannte Jita-Kaufpreis, gepflegt von {@code MiningPriceService}. */
    @Column(name = "current_jita_buy", precision = 20, scale = 2)
    private BigDecimal currentJitaBuy;
}

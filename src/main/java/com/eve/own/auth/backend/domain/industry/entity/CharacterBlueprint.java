package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine Blaupause im Besitz eines Charakters.
 *
 * <p>Noetig, weil ME und TE <em>nur</em> in ESI stehen und in keiner
 * Stammdatentabelle. Ohne diese Zeilen rechnet jeder Auftrag mit ME 0 und
 * weist einen deutlich zu hohen Materialbedarf aus.</p>
 *
 * <p>Ob es eine Kopie ist, wird an {@code runs != -1} erkannt und nicht an der
 * Stueckzahl: ein frisch gekaufter Stapel Originale hat eine positive
 * Stueckzahl, und eine Pruefung darauf haelt ihn faelschlich fuer eine Kopie -
 * ausgerechnet im haeufigsten Einstiegsfall.</p>
 */
@Entity
@Table(name = "character_blueprints")
@Getter
@Setter
public class CharacterBlueprint {

    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "location_flag", length = 64)
    private String locationFlag;

    @Column(nullable = false)
    private Integer quantity;

    /** Verbleibende Laeufe; -1 bedeutet Original. */
    @Column(nullable = false)
    private Integer runs;

    @Column(name = "material_efficiency", nullable = false)
    private Integer materialEfficiency = 0;

    @Column(name = "time_efficiency", nullable = false)
    private Integer timeEfficiency = 0;

    @Column(name = "is_copy", nullable = false)
    private Boolean copy = false;

    @Column(name = "updated_at")
    private Instant updatedAt;
}

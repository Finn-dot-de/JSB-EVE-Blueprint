package com.eve.own.auth.backend.domain.navigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein Register der Seitenleiste - der aufklappbare Ordner, unter dem mehrere
 * Menuepunkte zusammenstehen.
 *
 * <p>Frueher stand der Name als Text an jedem einzelnen Link, das Symbol war
 * fest verdrahtet und die Reihenfolge lag als Namensliste im Frontend. Damit
 * liess sich ein Register weder umbenennen noch verschieben, ohne den Code
 * anzufassen. Als eigene Zeile hat es einen Namen, ein Symbol und eine
 * Position, die sich alle pflegen lassen.</p>
 */
@Entity
@Table(name = "navigation_categories")
@Getter
@Setter
public class NavigationCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Font-Awesome-Klasse, z.B. {@code fa-solid fa-folder}. */
    private String icon;

    /**
     * Position in der obersten Ebene.
     *
     * <p>Geteilt mit den Links ohne Register: in der Seitenleiste stehen
     * einzelne Punkte und Ordner nebeneinander in einer Reihenfolge.</p>
     */
    @Column(name = "sort_order")
    private Integer sortOrder;
}

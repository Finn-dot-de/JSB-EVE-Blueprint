package com.eve.own.auth.backend.domain.navigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Ein Menuepunkt der Seitenleiste. */
@Entity
@Table(name = "navigation_links")
@Getter
@Setter
public class NavigationLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Beschriftung im Menue, z.B. "Ore Calculator". */
    private String label;

    /** Angular-Route oder vollstaendige Adresse eines externen Ziels. */
    private String url;

    /** Font-Awesome-Klasse, z.B. {@code fa-solid fa-gears}. */
    private String icon;

    /**
     * Das Register, unter dem der Punkt haengt - {@code null} heisst: direkt in
     * der obersten Ebene.
     *
     * <p>Bewusst die blanke ID statt einer JPA-Beziehung, wie es die uebrigen
     * Zuordnungen dieser Anwendung auch halten.</p>
     */
    @Column(name = "category_id")
    private Long categoryId;

    /** Rolle, die den Punkt sichtbar macht - {@code null} heisst: fuer alle. */
    private String requiredRole;

    /**
     * Position innerhalb des eigenen Registers, bei Punkten ohne Register
     * innerhalb der obersten Ebene.
     */
    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(columnDefinition = "boolean default true")
    private Boolean active = true;
}

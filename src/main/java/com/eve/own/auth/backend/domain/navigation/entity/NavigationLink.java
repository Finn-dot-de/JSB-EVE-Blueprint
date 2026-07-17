package com.eve.own.auth.backend.domain.navigation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "navigation_links")
@Getter @Setter
public class NavigationLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;      // z.B. "Ore Calculator"
    private String url;        // Angular Route oder externer Link
    private String icon;       // FontAwesome oder SVG Name
    private String category;   // "INDUSTRY", "TOOLS", "LINKS"
    private String requiredRole; // z.B. "ADMIN", "MEMBER" (Null für alle)

    @Column(columnDefinition = "boolean default true")
    private Boolean active = true;
}
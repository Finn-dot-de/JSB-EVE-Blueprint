package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "fleet_doctrines")
@Getter
@Setter
public class FleetDoctrine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String shipType;    // z.B. "Megathron"
    private Long shipTypeId;    // Die SDE TypeID für das Bild (z.B. 24694)
    private String name;        // z.B. "Armor Brawler V2"

    @Column(columnDefinition = "TEXT", nullable = false)
    private String eftString;   // Der komplette EFT-Text

    private String createdBy;   // Wer hat es erstellt?
    private Instant createdAt;
}
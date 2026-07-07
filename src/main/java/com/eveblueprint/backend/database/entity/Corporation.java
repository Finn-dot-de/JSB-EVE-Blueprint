package com.eveblueprint.backend.database.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "corporations")
@Getter
@Setter
public class Corporation {

    @Id
    @Column(name = "corporation_id")
    private Long id; // ID wird direkt von der ESI geliefert

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 10)
    private String ticker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alliance_id")
    private Alliance alliance;
}
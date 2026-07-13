package com.eve.own.auth.backend.database.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "alliances")
@Getter
@Setter
public class Alliance {

    @Id
    @Column(name = "alliance_id")
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 10)
    private String ticker;
}
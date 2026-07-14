package com.eve.own.auth.backend.domain.eve.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "eve_types")
@Getter @Setter
public class EveType {
    @Id
    @Column(name = "type_id")
    private Long typeId;

    private String name;

    @Column(name = "group_id")
    private Long groupId;

    private Double volume;
    private Double mass;
}
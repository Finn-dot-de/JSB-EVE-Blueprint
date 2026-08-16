package com.eve.buy.bot.backend.domain.eve.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Item-Typ aus der EVE-Statikdatenbank mit Name, Volumen und Gruppe. */
@Entity
@Table(name = "\"invTypes\"", schema = "evesde")
@Getter
@Setter
public class InvType {

    @Id
    @Column(name = "\"typeID\"")
    private Long typeId;

    @Column(name = "\"typeName\"")
    private String typeName;

    @Column(name = "\"groupID\"")
    private Long groupId;

    @Column(name = "volume")
    private Double volume;

    @Column(name = "mass")
    private Double mass;
}
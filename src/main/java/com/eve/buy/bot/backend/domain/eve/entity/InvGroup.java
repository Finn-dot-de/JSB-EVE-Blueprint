package com.eve.buy.bot.backend.domain.eve.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Item-Gruppe aus der EVE-Statikdatenbank; verbindet Typ und Kategorie. */
@Entity
@Table(name = "inv_groups", schema = "evesde")
@Getter @Setter
public class InvGroup {
    @Id
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "group_name")
    private String groupName;
}
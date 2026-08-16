package com.eve.buy.bot.backend.domain.eve.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Item-Kategorie aus der EVE-Statikdatenbank, Grundlage der Kategorie-Whitelist. */
@Entity
@Table(name = "inv_categories", schema = "evesde")
@Getter @Setter
public class InvCategory {
    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "category_name")
    private String categoryName;
}
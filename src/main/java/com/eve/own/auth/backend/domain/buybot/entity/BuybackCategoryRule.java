package com.eve.own.auth.backend.domain.buybot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "buyback_category_rules")
@Getter
@Setter
public class BuybackCategoryRule {

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "modifier")
    private Double modifier;
}
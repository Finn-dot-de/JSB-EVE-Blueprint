package com.eve.own.auth.backend.domain.buybot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "buyback_type_rules")
@Getter
@Setter
public class BuybackTypeRule {

    @Id
    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "modifier")
    private Double modifier;

    @Column(name = "is_blacklisted", nullable = false)
    private Boolean isBlacklisted = false;
}
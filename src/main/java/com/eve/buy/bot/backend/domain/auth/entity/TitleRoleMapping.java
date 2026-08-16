package com.eve.buy.bot.backend.domain.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Ordnet einem EVE-Corp-Titel eine Anwendungsrolle zu. */
@Entity
@Table(name = "title_role_mappings")
@Getter
@Setter
public class TitleRoleMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long titleId;
    private Long corporationId;
    private String roleName;
    private String titleName;
}
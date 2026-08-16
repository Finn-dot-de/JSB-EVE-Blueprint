package com.eve.buy.bot.backend.domain.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine im System bekannte Rolle.
 *
 * <p>Als besonders markierte Rollen werden von Hand vergeben und ueberleben einen
 * Rollen-Sync - sonst wuerde sich der Admin selbst aussperren.
 */
@Entity
@Table(name = "system_roles")
@Getter
@Setter
public class SystemRole {

    @Id
    private String roleName;

    private String description;

    private boolean isSpecial;
}
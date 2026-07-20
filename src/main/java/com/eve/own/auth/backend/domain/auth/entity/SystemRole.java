package com.eve.own.auth.backend.domain.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

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
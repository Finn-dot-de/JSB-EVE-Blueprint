package com.eve.own.auth.backend.domain.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
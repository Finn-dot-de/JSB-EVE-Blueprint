package com.eve.own.auth.backend.domain.eve.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
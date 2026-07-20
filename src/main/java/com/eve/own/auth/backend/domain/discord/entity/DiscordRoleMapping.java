package com.eve.own.auth.backend.domain.discord.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "discord_role_mappings")
@Getter
@Setter
public class DiscordRoleMapping {
    @Id
    private String authRole;

    private String discordRoleId;
    private String description;
}
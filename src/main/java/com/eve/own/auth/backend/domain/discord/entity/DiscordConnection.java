package com.eve.own.auth.backend.domain.discord.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "discord_connections")
@Getter
@Setter
public class DiscordConnection {
    @Id
    private Long characterId;

    private String discordUserId;
    private String discordUsername;

    // Wichtig für den "Join Server" Request
    private String accessToken;
    private String refreshToken;
}
package com.eve.own.auth.backend.domain.discord.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class DiscordBotService {
    private final RestClient botClient;
    private final String guildId;
    private final String clientId;
    private final String clientSecret;

    public DiscordBotService(RestClient.Builder builder,
                             @Value("${discord.bot-token}") String botToken,
                             @Value("${discord.server-id}") String guildId,
                             @Value("${discord.client-id}") String clientId,
                             @Value("${discord.client-secret}") String clientSecret) {
        this.guildId = guildId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        // Client für Bot-Befehle (mit Bot-Token)
        this.botClient = builder.baseUrl("https://discord.com/api/v10")
                .defaultHeader("Authorization", "Bot " + botToken)
                .build();
    }

    // --- DTOs für die Discord API Antworten ---
    public record DiscordTokenResponse(String access_token, String refresh_token, Integer expires_in) {}
    public record DiscordUserResponse(String id, String username) {}

    // 1. Code gegen Token tauschen
    public DiscordTokenResponse exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        return RestClient.create().post()
                .uri("https://discord.com/api/v10/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(DiscordTokenResponse.class);
    }

    // 2. Discord Profil des Nutzers laden
    public DiscordUserResponse getDiscordUserProfile(String userAccessToken) {
        return RestClient.create().get()
                .uri("https://discord.com/api/v10/users/@me")
                .header("Authorization", "Bearer " + userAccessToken)
                .retrieve()
                .body(DiscordUserResponse.class);
    }

    // 3. User auf den Server einladen (JETZT MIT NICKNAME)
    public void addMemberToServer(String discordUserId, String userAccessToken, List<String> discordRoleIds, String nickname) {
        Map<String, Object> body = new HashMap<>();
        body.put("access_token", userAccessToken);
        body.put("roles", discordRoleIds);
        if (nickname != null && !nickname.isBlank()) {
            body.put("nick", nickname.length() > 32 ? nickname.substring(0, 32) : nickname);
        }

        botClient.put()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // 4. Rollen und Nickname synchronisieren
    public void syncMemberData(String discordUserId, List<String> discordRoleIds, String nickname) {
        Map<String, Object> body = new HashMap<>();
        body.put("roles", discordRoleIds);
        if (nickname != null && !nickname.isBlank()) {
            body.put("nick", nickname.length() > 32 ? nickname.substring(0, 32) : nickname);
        }

        botClient.patch()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // 3. User auf den Server einladen
    public void addMemberToServer(String discordUserId, String userAccessToken, List<String> discordRoleIds) {
        Map<String, Object> body = Map.of(
                "access_token", userAccessToken,
                "roles", discordRoleIds
        );

        botClient.put()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // 4. Rollen synchronisieren
    public void syncMemberRoles(String discordUserId, List<String> discordRoleIds) {
        Map<String, Object> body = Map.of("roles", discordRoleIds);

        botClient.patch()
                .uri("/guilds/{guildId}/members/{userId}", guildId, discordUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
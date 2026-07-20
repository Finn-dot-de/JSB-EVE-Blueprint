package com.eve.own.auth.backend.domain.discord.controller;

import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/discord")
public class DiscordAuthController {

    private final String clientId;
    private final String redirectUri;
    private final String frontendUrl;

    private final DiscordBotService discordBotService;
    private final DiscordConnectionRepository connectionRepo;
    private final CharacterRepository characterRepo;
    private final DiscordRoleMappingRepository mappingRepo;
    private final SystemRoleRepository systemRoleRepo; // NEU

    public DiscordAuthController(@Value("${discord.client-id}") String clientId,
                                 @Value("${app.base.url}") String baseUrl,
                                 @Value("${app.frontend.url}") String frontendUrl,
                                 DiscordBotService discordBotService,
                                 DiscordConnectionRepository connectionRepo,
                                 CharacterRepository characterRepo,
                                 DiscordRoleMappingRepository mappingRepo,
                                 SystemRoleRepository systemRoleRepo) {
        this.clientId = clientId;
        this.redirectUri = baseUrl.endsWith("/") ? baseUrl + "api/discord/callback" : baseUrl + "/api/discord/callback";
        this.frontendUrl = frontendUrl;

        this.discordBotService = discordBotService;
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.systemRoleRepo = systemRoleRepo;
    }

    // ... Deine bestehenden Endpunkte (/login, /callback, /status) bleiben hier exakt gleich ...

    @GetMapping("/login")
    public ResponseEntity<Void> redirectToDiscord() {
        String scopes = "identify guilds.join";
        String discordLoginUrl = "https://discord.com/oauth2/authorize" +
                "?client_id=" + clientId +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(scopes, StandardCharsets.UTF_8);

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(discordLoginUrl)).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> discordCallback(@RequestParam("code") String code) {
        try {
            Long charId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            var tokenResp = discordBotService.exchangeCode(code, redirectUri);
            var userResp = discordBotService.getDiscordUserProfile(tokenResp.access_token());

            DiscordConnection conn = connectionRepo.findById(charId).orElse(new DiscordConnection());
            conn.setCharacterId(charId);
            conn.setDiscordUserId(userResp.id());
            conn.setDiscordUsername(userResp.username());
            conn.setAccessToken(tokenResp.access_token());
            conn.setRefreshToken(tokenResp.refresh_token());
            connectionRepo.save(conn);

            Character c = characterRepo.findById(charId).orElseThrow();
            List<String> expectedRoles = c.getRoles().stream()
                    .map(mappingRepo::findById)
                    .filter(java.util.Optional::isPresent)
                    .map(m -> m.get().getDiscordRoleId())
                    .toList();

            try {
                discordBotService.addMemberToServer(userResp.id(), tokenResp.access_token(), expectedRoles);
            } catch (Exception e) {
                discordBotService.syncMemberRoles(userResp.id(), expectedRoles);
            }

            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl + "/services?discord=success")).build();
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl + "/services?discord=error")).build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<java.util.Map<String, Boolean>> getConnectionStatus() {
        Long charId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean isConnected = connectionRepo.existsById(charId);
        return ResponseEntity.ok(java.util.Map.of("connected", isConnected));
    }

    // ========================================================
    // NEU: Admin Endpunkte für das Mapping
    // ========================================================
    @PreAuthorize("hasAnyRole('ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN', 'ROLE_A38')")
    @GetMapping("/mappings")
    public ResponseEntity<List<java.util.Map<String, String>>> getAllRolesWithMappings() {
        List<SystemRole> allRoles = systemRoleRepo.findAll();
        List<DiscordRoleMapping> mappings = mappingRepo.findAll();
        List<java.util.Map<String, String>> result = new java.util.ArrayList<>();

        // Standard-Rollen hinzufügen (da diese oft nicht in der SystemRole DB stehen)
        List<String> defaultRoles = java.util.List.of("ROLE_USER", "ROLE_MEMBER");
        for (String dr : defaultRoles) {
            String discordId = mappings.stream().filter(m -> m.getAuthRole().equals(dr)).map(DiscordRoleMapping::getDiscordRoleId).findFirst().orElse("");
            result.add(java.util.Map.of("authRole", dr, "discordRoleId", discordId, "description", "Basis-Recht für eingeloggte Piloten"));
        }

        for (SystemRole role : allRoles) {
            String discordId = mappings.stream()
                    .filter(m -> m.getAuthRole().equals(role.getRoleName()))
                    .map(DiscordRoleMapping::getDiscordRoleId)
                    .findFirst()
                    .orElse("");
            result.add(java.util.Map.of(
                    "authRole", role.getRoleName(),
                    "discordRoleId", discordId,
                    "description", role.getDescription() != null ? role.getDescription() : ""
            ));
        }
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAnyRole('ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN', 'ROLE_A38')")
    @PostMapping("/mappings")
    public ResponseEntity<Void> saveMapping(@RequestBody DiscordRoleMapping dto) {
        // Wenn das Feld leer ist, löschen wir das Mapping aus der Datenbank
        if (dto.getDiscordRoleId() == null || dto.getDiscordRoleId().isBlank()) {
            mappingRepo.deleteById(dto.getAuthRole());
        } else {
            mappingRepo.save(dto);
        }
        return ResponseEntity.ok().build();
    }
}
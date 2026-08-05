package com.eve.own.auth.backend.domain.discord.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
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
    private final SystemRoleRepository systemRoleRepo;
    private final TitleRoleMappingRepository titleRepo; // <-- NEU: Um Ingame-Titel-Rollen zu finden

    public DiscordAuthController(@Value("${discord.client-id}") String clientId,
                                 @Value("${app.base.url}") String baseUrl,
                                 @Value("${app.frontend.url}") String frontendUrl,
                                 DiscordBotService discordBotService,
                                 DiscordConnectionRepository connectionRepo,
                                 CharacterRepository characterRepo,
                                 DiscordRoleMappingRepository mappingRepo,
                                 SystemRoleRepository systemRoleRepo,
                                 TitleRoleMappingRepository titleRepo) { // <-- NEU
        this.clientId = clientId;
        this.redirectUri = baseUrl.endsWith("/") ? baseUrl + "api/discord/callback" : baseUrl + "/api/discord/callback";
        this.frontendUrl = frontendUrl;

        this.discordBotService = discordBotService;
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.systemRoleRepo = systemRoleRepo;
        this.titleRepo = titleRepo; // <-- NEU
    }

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
            Long charId = CurrentUser.characterId();
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

            Character mainChar = c.getMainCharacterId() != null
                    ? characterRepo.findById(c.getMainCharacterId()).orElse(c)
                    : c;
            String expectedNickname = mainChar.getName();

            List<String> expectedRoles = c.getRoles().stream()
                    .map(mappingRepo::findById)
                    .filter(Optional::isPresent)
                    .map(m -> m.get().getDiscordRoleId())
                    .toList();

            try {
                discordBotService.addMemberToServer(userResp.id(), tokenResp.access_token(), expectedRoles, expectedNickname);
            } catch (Exception e) {
                log.warn("Konnte User {} nicht zum Server hinzufügen: {}", userResp.username(), e.getMessage());
                try {
                    discordBotService.syncMemberData(userResp.id(), expectedRoles, expectedNickname);
                } catch (Exception ex) {
                    log.error("Konnte Daten für User {} nicht synchronisieren: {}", userResp.username(), ex.getMessage());
                }
            }

            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl + "/services?discord=success")).build();
        } catch (Exception e) {
            log.error("Genereller Fehler beim Discord-Callback", e);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl + "/services?discord=error")).build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getConnectionStatus() {
        Long charId = CurrentUser.characterId();
        boolean isConnected = connectionRepo.existsById(charId);
        return ResponseEntity.ok(Map.of("connected", isConnected));
    }

    // ========================================================
    // Admin Endpunkte für das Mapping (JETZT MIT ALLEN ROLLEN)
    // ========================================================
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @GetMapping("/mappings")
    public ResponseEntity<List<Map<String, String>>> getAllRolesWithMappings() {
        List<DiscordRoleMapping> mappings = mappingRepo.findAll();
        List<Map<String, String>> result = new ArrayList<>();

        Set<String> allAuthRoles = new HashSet<>();

        allAuthRoles.add("ROLE_USER");
        allAuthRoles.add("ROLE_MEMBER");

        systemRoleRepo.findAll().forEach(role -> allAuthRoles.add(role.getRoleName()));

        // Ingame-Titel-Rollen aus der Mapping-Tabelle hinzufügen
        titleRepo.findAll().forEach(titleMapping -> {
            if (titleMapping.getRoleName() != null && !titleMapping.getRoleName().isBlank()) {
                allAuthRoles.add(titleMapping.getRoleName());
            }
        });

        // 2. Mappings und Beschreibungen zuordnen
        for (String role : allAuthRoles) {
            String discordId = mappings.stream()
                    .filter(m -> m.getAuthRole().equals(role))
                    .map(DiscordRoleMapping::getDiscordRoleId)
                    .findFirst()
                    .orElse("");

            // Passende Beschreibung generieren
            String description;
            if (role.equals("ROLE_USER")) {
                description = "Basis-Recht für alle ESI-Logins";
            } else if (role.equals("ROLE_MEMBER")) {
                description = "Basis-Recht für Corp-Mitglieder";
            } else {
                var sysRole = systemRoleRepo.findById(role);
                if (sysRole.isPresent() && sysRole.get().getDescription() != null) {
                    description = sysRole.get().getDescription();
                } else {
                    description = "Automatisch generiert aus Ingame-Titel";
                }
            }

            result.add(Map.of(
                    "authRole", role,
                    "discordRoleId", discordId,
                    "description", description
            ));
        }

        // 3. Alphabetisch sortieren, sieht im Frontend aufgeräumter aus
        result.sort(Comparator.comparing(a -> a.get("authRole")));

        return ResponseEntity.ok(result);
    }

    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @PostMapping("/mappings")
    public ResponseEntity<Void> saveMapping(@RequestBody DiscordRoleMapping dto) {
        if (dto.getDiscordRoleId() == null || dto.getDiscordRoleId().isBlank()) {
            mappingRepo.deleteById(dto.getAuthRole());
        } else {
            mappingRepo.save(dto);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/disconnect")
    public ResponseEntity<Void> disconnectDiscord() {
        Long charId = CurrentUser.characterId();
        connectionRepo.findById(charId).ifPresent(conn -> {
            try {
                discordBotService.syncMemberData(conn.getDiscordUserId(), new ArrayList<>(), null);
            } catch (Exception e) {
                log.warn("Konnte Rollen beim Trennen für User {} nicht entfernen: {}", conn.getDiscordUserId(), e.getMessage());
            }
            connectionRepo.delete(conn);
        });

        return ResponseEntity.ok().build();
    }
}
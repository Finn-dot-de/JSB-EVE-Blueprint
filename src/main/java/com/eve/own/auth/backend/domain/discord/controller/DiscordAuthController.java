package com.eve.own.auth.backend.domain.discord.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.auth.service.RoleCatalogService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import com.eve.own.auth.backend.domain.discord.service.DiscordCharacterAudit;
import com.eve.own.auth.backend.domain.discord.service.DiscordRoleAudit;
import com.eve.own.auth.backend.domain.discord.service.DiscordRoleAuditService;
import com.eve.own.auth.backend.domain.discord.service.DiscordRoleSyncService;
import com.eve.own.auth.backend.domain.discord.service.DiscordSyncErgebnis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private final RoleCatalogService roleCatalogService;
    private final DiscordRoleAuditService discordRoleAuditService;
    private final DiscordRoleSyncService discordRoleSyncService;

    public DiscordAuthController(@Value("${discord.client-id}") String clientId,
                                 @Value("${app.base.url}") String baseUrl,
                                 @Value("${app.frontend.url}") String frontendUrl,
                                 DiscordBotService discordBotService,
                                 DiscordConnectionRepository connectionRepo,
                                 CharacterRepository characterRepo,
                                 DiscordRoleMappingRepository mappingRepo,
                                 RoleCatalogService roleCatalogService,
                                 DiscordRoleAuditService discordRoleAuditService,
                                 DiscordRoleSyncService discordRoleSyncService) {
        this.clientId = clientId;
        this.redirectUri = baseUrl.endsWith("/") ? baseUrl + "api/discord/callback" : baseUrl + "/api/discord/callback";
        this.frontendUrl = frontendUrl;

        this.discordBotService = discordBotService;
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.roleCatalogService = roleCatalogService;
        this.discordRoleAuditService = discordRoleAuditService;
        this.discordRoleSyncService = discordRoleSyncService;
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
    // Admin Endpunkte für das Mapping
    // ========================================================

    /**
     * Alle Auth-Rollen samt der bereits hinterlegten Discord-Rollen-ID.
     *
     * <p>Welche Rollen es gibt, beantwortet der {@link RoleCatalogService}. Diese
     * Methode hat die Liste frueher selbst zusammengesucht - dieselbe Arbeit, die
     * auch die Rollenverwaltung leistet, nur mit einem eigenen Satz Sonderfaelle.
     * Beide Seiten zeigen jetzt zwangslaeufig dieselben Rollen.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @GetMapping("/mappings")
    public ResponseEntity<List<Map<String, String>>> getAllRolesWithMappings() {
        // Ein leerer Text statt null: Map.of vertraegt keine null-Werte.
        Map<String, String> discordIdsByRole = mappingRepo.findAll().stream()
                .collect(Collectors.toMap(
                        DiscordRoleMapping::getAuthRole,
                        mapping -> Optional.ofNullable(mapping.getDiscordRoleId()).orElse(""),
                        (first, second) -> first));

        List<Map<String, String>> result = roleCatalogService.catalog().stream()
                .map(role -> Map.of(
                        "authRole", role.name(),
                        "discordRoleId", discordIdsByRole.getOrDefault(role.name(), ""),
                        "description", role.description()))
                .sorted(Comparator.comparing(entry -> entry.get("authRole")))
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Stellt fest, ob Discord traegt, was das Auth vorsieht - ohne etwas zu aendern.
     *
     * <p>Neben dem Zeitplan, nicht statt seiner. Der Zeitplan findet die
     * Abweichung auch dann, wenn niemand hinsieht; dieser Endpunkt beantwortet
     * die Frage, die unmittelbar nach jeder Aenderung an den Mappings kommt -
     * "hat es gewirkt?". Sie sechs Stunden lang unbeantwortet zu lassen heisst,
     * dass sie stattdessen von Hand in Discord nachgesehen wird.</p>
     *
     * <p>Dieselbe Berechtigung wie die Mappings darueber: Wer die Zuordnung
     * pflegt, ist der, der ihr Ergebnis pruefen muss.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @GetMapping("/audit")
    public ResponseEntity<List<DiscordRoleAudit>> pruefeRollen() {
        return ResponseEntity.ok(discordRoleAuditService.pruefeAlle());
    }

    /**
     * Dieselbe Pruefung, aufgeschluesselt je Charakter.
     *
     * <p>Gerechnet wird weiterhin je Discord-Konto - das muss so bleiben, sonst
     * faellt der Fall "zwei Charaktere, ein Konto" wieder auseinander. Gefragt
     * wird aber nach Charakteren: "Was hat Tom, und was fehlt ihm." Wer die
     * Kontosicht liest, muss die Zuordnung im Kopf machen; das tut man einmal
     * und danach nicht mehr.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @GetMapping("/audit/characters")
    public ResponseEntity<List<DiscordCharacterAudit>> pruefeCharaktere() {
        return ResponseEntity.ok(discordRoleAuditService.pruefeCharaktere());
    }

    /**
     * Die Gegenueberstellung fuer einen einzelnen Charakter.
     *
     * <p>Kostet einen Aufruf an Discord statt einen je Konto - gedacht fuer die
     * Ruecksicht nach einem angestossenen Abgleich. Die volle Uebersicht dafuer
     * neu zu laden, hiesse fuer eine Zeile jedes verknuepfte Konto erneut
     * abzufragen.</p>
     *
     * <p>404 nur, wenn es den Charakter nicht gibt. "Nicht verknuepft" ist eine
     * gueltige Antwort mit Inhalt, kein Fehler - sie nennt die Ursache.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @GetMapping("/audit/characters/{characterId}")
    public ResponseEntity<DiscordCharacterAudit> pruefeCharakter(@PathVariable Long characterId) {
        return discordRoleAuditService.pruefeCharakter(characterId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Fuehrt den Abgleich fuer einen Charakter sofort aus und meldet, was dabei herauskam.
     *
     * <p>Die Ursache "der Abgleich lief noch nicht" benennt eine Wartezeit von
     * bis zu dreissig Minuten. Ohne diesen Endpunkt waere sie eine Feststellung
     * ohne Handlungsmoeglichkeit - man saehe die Ursache und koennte nichts tun
     * als warten.</p>
     *
     * <p>POST und nicht GET: Der Aufruf aendert etwas in Discord. Als GET
     * wuerde ihn frueher oder spaeter jemand aus einem Browser-Tab heraus
     * wiederholen lassen.</p>
     *
     * <p>Dieselbe Berechtigung wie die Pruefung. Wer die Zuordnung pflegt, ist
     * der, der ihr Ergebnis durchsetzen koennen muss.</p>
     */
    @PreAuthorize(AccessRules.FLEET_STAFF_OR_LEADERSHIP)
    @PostMapping("/sync/{characterId}")
    public ResponseEntity<DiscordSyncErgebnis> stosseAbgleichAn(@PathVariable Long characterId) {
        return discordRoleSyncService.stosseAn(characterId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
                // Nur abnehmen, was dieses Auth vergeben hat. Frueher stand
                // hier ein leeres "roles"-Feld - bei Discord ein Vollersatz,
                // der dem Mitglied JEDE Rolle nahm, auch handvergebene. Und
                // ausloesen konnte das jeder Angemeldete fuer sich selbst.
                List<String> verwalteteRollen = mappingRepo.findAll().stream()
                        .map(m -> m.getDiscordRoleId())
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
                discordBotService.syncManagedRoles(conn.getDiscordUserId(),
                        verwalteteRollen, List.of(), null);
            } catch (Exception e) {
                log.warn("Konnte Rollen beim Trennen für User {} nicht entfernen: {}", conn.getDiscordUserId(), e.getMessage());
            }
            connectionRepo.delete(conn);
        });

        return ResponseEntity.ok().build();
    }
}
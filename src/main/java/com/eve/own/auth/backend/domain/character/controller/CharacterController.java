package com.eve.own.auth.backend.domain.character.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.service.AccountService;
import com.eve.own.auth.backend.domain.character.service.AltDetectionService;
import com.eve.own.auth.backend.domain.character.service.CorporationStatsService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Die Endpunkte rund um Charaktere, Accounts und Corp-Mitgliedschaft. */
@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final AccountService accountService;
    private final AltDetectionService altDetectionService;
    private final CorporationStatsService corporationStatsService;
    private final com.eve.own.auth.backend.domain.auth.service.TokenHealthService tokenHealth;
    private final com.eve.own.auth.backend.domain.assets.service.MyAssetService assetService;

    public CharacterController(AccountService accountService,
                               AltDetectionService altDetectionService,
                               CorporationStatsService corporationStatsService,
                               com.eve.own.auth.backend.domain.auth.service.TokenHealthService tokenHealth,
                               com.eve.own.auth.backend.domain.assets.service.MyAssetService assetService) {
        this.accountService = accountService;
        this.altDetectionService = altDetectionService;
        this.corporationStatsService = corporationStatsService;
        this.tokenHealth = tokenHealth;
        this.assetService = assetService;
    }

    /**
     * Welche eigenen Charaktere sich neu anmelden muessen.
     *
     * <p>Nur die des eigenen Kontos - fremde Anmeldeprobleme gehen niemanden
     * etwas an. Ohne diesen Endpunkt lebte die Information nur im Serverlog,
     * und der Spieler erfuhr erst dann davon, dass sein Charakter draussen ist,
     * wenn dessen Daten unbemerkt veralteten.</p>
     */
    @GetMapping("/token-health")
    public List<CharacterDtos.TokenHealthDto> tokenHealth() {
        java.util.Set<Long> eigene;
        try {
            eigene = assetService.ownCharacterIds(
                    assetService.resolveMainId(CurrentUser.characterId()));
        } catch (IllegalStateException e) {
            return List.of();
        }
        return tokenHealth.invalidTokens().stream()
                .filter(c -> eigene.contains(c.getId()))
                .map(c -> new CharacterDtos.TokenHealthDto(
                        c.getId(), c.getName(),
                        c.getTokenInvalidSince() == null ? null : c.getTokenInvalidSince().toString(),
                        c.getTokenInvalidReason()))
                .toList();
    }

    /** Die eigenen Charaktere, Main und Alts. */
    @GetMapping("/alts")
    public ResponseEntity<List<CharacterDtos.CharacterRefDto>> getMyCharacters() {
        return ResponseEntity.ok(accountService.charactersOfAccount(CurrentUser.characterId()));
    }

    /** Registrierte und nicht registrierte Mitglieder je betreuter Corporation. */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/corp-stats")
    public ResponseEntity<List<CharacterDtos.CorpStatsDto>> getCorporationStats() {
        return ResponseEntity.ok(corporationStatsService.statsForAllCorporations());
    }

    /** Bestimmt einen anderen eigenen Charakter zum Main. */
    @PostMapping("/set-main/{newMainId}")
    public ResponseEntity<Void> setMainCharacter(@PathVariable Long newMainId) {
        accountService.changeMainCharacter(CurrentUser.characterId(), newMainId);
        return ResponseEntity.ok().build();
    }

    /**
     * Welcher nicht registrierte Corp-Charakter zu welchem bekannten Konto
     * gehoeren koennte.
     *
     * <p>Der Aufruf rechnet je Corporation ein Kreuzprodukt aus nicht
     * registrierten Mitgliedern und Konten - bei den heutigen Zahlen rund 4.400
     * Paare, also Millisekunden. Die Laufzeit steckt in den ESI-Aufrufen, nicht
     * im Rechnen; die Grenze dagegen steht in
     * {@code AltDetectionTuning.MAX_PAIRS_PER_CORPORATION}.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/alt-suggestions")
    public ResponseEntity<List<CharacterDtos.AltSuggestionDto>> getAltSuggestions() {
        return ResponseEntity.ok(altDetectionService.findProbableAlts());
    }

    /**
     * Bestaetigt einen Vorschlag - als Vormerkung, nicht als Zuordnung.
     *
     * <p>Der Endpunkt schreibt <b>nichts</b> nach
     * {@code characters.main_character_id}. Er haelt fest, dass die Fuehrung den
     * Verdacht fuer richtig haelt; zugeordnet wird der Charakter erst, wenn er
     * sich unter "Alt hinzufuegen" selbst per EVE SSO anmeldet - der bestehende
     * Weg, auf dem CCP die Eigentuemerschaft beweist. Die Begruendung steht in
     * {@code AltLinkProposal}.</p>
     *
     * <p>Die Rechtepruefung steht zusaetzlich im Dienst und nicht nur hier: die
     * Annotation deckt genau diesen einen Einstiegspunkt ab.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @PostMapping("/alt-suggestions/confirm")
    public ResponseEntity<CharacterDtos.AltLinkResultDto> confirmAltSuggestion(
            @RequestBody CharacterDtos.AltLinkRequest request) {
        return ResponseEntity.ok(altDetectionService.confirmAltSuggestion(
                CurrentUser.characterId(), request.unauthedCharId(), request.mainId()));
    }

    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/accounts")
    public ResponseEntity<List<CharacterDtos.AdminAccountDto>> getAllAccounts() {
        return ResponseEntity.ok(accountService.allAccounts());
    }
}

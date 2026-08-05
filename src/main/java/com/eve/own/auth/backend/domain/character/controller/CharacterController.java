package com.eve.own.auth.backend.domain.character.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.service.AccountService;
import com.eve.own.auth.backend.domain.character.service.CorporationStatsService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Die Endpunkte rund um Charaktere, Accounts und Corp-Mitgliedschaft. */
@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final AccountService accountService;
    private final CorporationStatsService corporationStatsService;

    public CharacterController(AccountService accountService,
                               CorporationStatsService corporationStatsService) {
        this.accountService = accountService;
        this.corporationStatsService = corporationStatsService;
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

    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/admin/accounts")
    public ResponseEntity<List<CharacterDtos.AdminAccountDto>> getAllAccounts() {
        return ResponseEntity.ok(accountService.allAccounts());
    }
}

package com.eve.own.auth.backend.domain.auth.scheduler;

import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.*;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
import com.eve.own.auth.backend.domain.character.service.AssetSyncService;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AccountSyncScheduler {

    private final AuthService authService;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CharacterStatsRepository statsRepo;
    private final AssetSyncService assetSyncService;
    private final InvTypeRepository invTypeRepo;
    private final CorporationRepository corpRepo;
    private final TitleRoleMappingRepository titleRepo;
    private final SystemRoleRepository systemRoleRepo;

    private static final Long MY_MAIN_CORP_ID = 98378388L;

    public AccountSyncScheduler(AuthService authService, EsiService esiService,
                                CharacterRepository characterRepo, CharacterStatsRepository statsRepo,
                                AssetSyncService assetSyncService, InvTypeRepository invTypeRepo,
                                CorporationRepository corpRepo, TitleRoleMappingRepository titleRepo,
                                SystemRoleRepository systemRoleRepo) {
        this.authService = authService;
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.statsRepo = statsRepo;
        this.assetSyncService = assetSyncService;
        this.invTypeRepo = invTypeRepo;
        this.corpRepo = corpRepo;
        this.titleRepo = titleRepo;
        this.systemRoleRepo = systemRoleRepo;
    }

    @Scheduled(fixedRate = 600000) // Alle 10 Minuten
    public void syncAllAccountData() {
        log.info("Starte Account-Sync...");
        List<Character> allChars = characterRepo.findAllWithCorporation();

        for (Character c : allChars) {
            try {
                processSingleCharacter(c);
            } catch (Exception e) {
                log.error("Sync fehlgeschlagen für Charakter {}: {}", c.getId(), e.getMessage());
            }
        }
        log.info("Account-Sync abgeschlossen.");
    }

    // --- Haupt-Ablauf für einen einzelnen Charakter ---
    private void processSingleCharacter(Character c) {
        // 0. Leaver-Check
        boolean isStillMember = performLeaverCheck(c);
        if (!isStillMember) {
            return; // Abbruch für diesen Charakter, da er die Corp verlassen hat
        }

        // 1. Faction Sync
        syncCorporationFaction(c);

        // Token besorgen für die restlichen privaten Endpunkte
        String token = authService.getValidAccessToken(c);

        // 2-6. Einzelne Sync-Schritte aufrufen
        syncStats(c, token);
        syncLoyaltyPoints(c, token);
        syncAssets(c, token);
        syncActivities(c, token);
        syncTitlesAndRoles(c, token);
    }

    // ==========================================
    // HELFER-METHODEN FÜR DIE EINZELNEN SCHRITTE
    // ==========================================

    private boolean performLeaverCheck(Character c) {
        var charPublicInfo = esiService.getCharacter(c.getId(), null).data();
        if (charPublicInfo == null) return true; // Fallback, falls ESI spinnt

        Long currentCorpId = charPublicInfo.corporation_id();

        // 1. Hat der Charakter die Corp gewechselt?
        if (!currentCorpId.equals(c.getCorporation().getId())) {
            log.warn("Charakter {} hat die Corp gewechselt! Neu: {}", c.getName(), currentCorpId);
            Corporation newCorp = corpRepo.findById(currentCorpId).orElseGet(() -> {
                Corporation createdCorp = new Corporation();
                createdCorp.setId(currentCorpId);
                try {
                    var corpData = esiService.getCorporationInfo(currentCorpId);
                    if (corpData != null) {
                        createdCorp.setName(corpData.name());
                        createdCorp.setTicker(corpData.ticker());
                        createdCorp.setFactionId(corpData.faction_id());
                    }
                } catch (Exception ex) {
                    createdCorp.setName("Unknown Corp");
                    createdCorp.setTicker("UNK");
                }
                return corpRepo.save(createdCorp);
            });
            c.setCorporation(newCorp);
            characterRepo.save(c);
        }

        boolean isMain = c.getMainCharacterId() == null || c.getMainCharacterId().equals(c.getId());

        if (isMain && !currentCorpId.equals(MY_MAIN_CORP_ID)) {
            c.setRoles(new java.util.HashSet<>());
            characterRepo.save(c);
            log.info("Sicherheits-Kick: Main-Charakter {} hat die Main-Corp verlassen. Alle Rechte entzogen.", c.getName());
            return false;
        }

        return true;
    }

    private void syncCorporationFaction(Character c) {
        if (c.getCorporation() != null) {
            try {
                var corpInfo = esiService.getCorporationInfo(c.getCorporation().getId());
                if (corpInfo != null && corpInfo.faction_id() != null) {
                    c.getCorporation().setFactionId(corpInfo.faction_id());
                    corpRepo.save(c.getCorporation());
                }
            } catch (Exception e) {
                log.warn("Konnte Faction-Daten für Corp {} nicht laden.", c.getCorporation().getName());
            }
        }
    }

    private void syncStats(Character c, String token) {
        CharacterStats stats = statsRepo.findById(c.getId()).orElse(new CharacterStats());
        stats.setCharacterId(c.getId());
        stats.setLastUpdated(Instant.now());

        var walletResp = esiService.getWalletBalance(c.getId(), token, stats.getWalletEtag());
        if (walletResp.data() != null) {
            stats.setWalletBalance(walletResp.data());
            stats.setWalletEtag(walletResp.etag());
        }

        var skillResp = esiService.getSkills(c.getId(), token, stats.getSkillsEtag());
        if (skillResp.data() != null) {
            stats.setSkillPoints(skillResp.data().total_sp());
            stats.setSkillsEtag(skillResp.etag());
        }

        statsRepo.save(stats);
    }

    private void syncLoyaltyPoints(Character c, String token) {
        var lpResp = esiService.getLoyaltyPoints(c.getId(), token, null);
        if (lpResp.data() != null) {
            List<CharacterLp> mappedLps = Arrays.stream(lpResp.data()).map(el -> {
                CharacterLp lp = new CharacterLp();
                lp.setCharacterId(c.getId());
                lp.setCorporationId(el.corporation_id());
                lp.setLoyaltyPoints(el.loyalty_points());
                return lp;
            }).collect(Collectors.toList());

            assetSyncService.replaceCharacterLp(c.getId(), mappedLps);
        }
    }

    private void syncAssets(Character c, String token) {
        var esiAssets = esiService.getAllAssets(c.getId(), token);
        if (!esiAssets.isEmpty()) {
            List<CharacterAsset> mappedAssets = esiAssets.stream().map(ea -> {
                CharacterAsset a = new CharacterAsset();
                a.setItemId(ea.item_id());
                a.setCharacterId(c.getId());
                a.setTypeId(ea.type_id());
                a.setLocationId(ea.location_id());
                a.setQuantity(ea.quantity() != null ? ea.quantity() : 1);
                return a;
            }).collect(Collectors.toList());

            assetSyncService.replaceCharacterAssets(c.getId(), mappedAssets);
        }
    }

    private void syncActivities(Character c, String token) {
        java.util.List<CharacterActivity> newActivities = new java.util.ArrayList<>();
        Instant now = Instant.now();

        // Mining Ledger
        var miningResp = esiService.getMiningLedger(c.getId(), token, null);
        if (miningResp.data() != null && miningResp.data().length > 0) {
            java.util.Set<Long> minedTypeIds = Arrays.stream(miningResp.data())
                    .map(EsiService.EsiMiningResponse::type_id)
                    .collect(Collectors.toSet());

            java.util.List<InvType> sdeTypes = invTypeRepo.findAllById(minedTypeIds);
            java.util.Map<Long, Double> typeVolumes = sdeTypes.stream()
                    .collect(Collectors.toMap(InvType::getTypeId, InvType::getVolume));

            double totalVolumeM3 = 0.0;
            for (var m : miningResp.data()) {
                double itemVolume = typeVolumes.getOrDefault(m.type_id(), 0.0);
                totalVolumeM3 += m.quantity() * itemVolume;
            }

            CharacterActivity miningActivity = new CharacterActivity();
            miningActivity.setCharacterId(c.getId());
            miningActivity.setActivityType("MINING_VOLUME");
            miningActivity.setValue(totalVolumeM3);
            miningActivity.setTimestamp(now);
            newActivities.add(miningActivity);
        }

        // Wallet Journal
        var journalResp = esiService.getWalletJournal(c.getId(), token, null);
        if (journalResp.data() != null) {
            double totalBounty = 0.0;
            long bountyTicks = 0;

            for (var j : journalResp.data()) {
                if ("bounty_prizes".equals(j.ref_type()) && j.amount() != null) {
                    totalBounty += j.amount();
                    bountyTicks++;
                }
            }

            CharacterActivity pveActivity = new CharacterActivity();
            pveActivity.setCharacterId(c.getId());
            pveActivity.setActivityType("PVE_ISK");
            pveActivity.setValue(totalBounty);
            pveActivity.setTimestamp(now);
            newActivities.add(pveActivity);

            CharacterActivity ratKillsActivity = new CharacterActivity();
            ratKillsActivity.setCharacterId(c.getId());
            ratKillsActivity.setActivityType("RAT_KILLS");
            ratKillsActivity.setValue((double) bountyTicks);
            ratKillsActivity.setTimestamp(now);
            newActivities.add(ratKillsActivity);
        }

        if (!newActivities.isEmpty()) {
            assetSyncService.replaceCharacterActivities(c.getId(), newActivities);
        }
    }

    private void syncTitlesAndRoles(Character c, String token) {
        var titlesResp = esiService.getCharacterTitles(c.getId(), token, null);
        java.util.Set<String> calculatedRoles = new java.util.HashSet<>();

        calculatedRoles.add("ROLE_USER");

        if (c.getCorporation().getId().equals(MY_MAIN_CORP_ID)) {
            calculatedRoles.add("ROLE_MEMBER");
            calculatedRoles.add("ROLE_MARAUDERS_ASSOCIATED");
        }

        // =========================================================
        // NEU: Spezial-Rollen sichern, bevor alles neu berechnet wird!
        // =========================================================
        List<String> specialRolesInDb = systemRoleRepo.findByIsSpecialTrue().stream()
                .map(SystemRole::getRoleName)
                .toList();

        // Welche dieser Special Roles hat der Charakter gerade?
        java.util.Set<String> retainedSpecialRoles = c.getRoles().stream()
                .filter(specialRolesInDb::contains)
                .collect(Collectors.toSet());
        // =========================================================

        if (titlesResp.data() != null && titlesResp.data().length > 0) {
            List<TitleRoleMapping> existingMappings = titleRepo.findByCorporationId(c.getCorporation().getId());
            for (var esiTitle : titlesResp.data()) {
                String cleanName = esiTitle.name().replaceAll("<[^>]*>", "");
                var existingOpt = existingMappings.stream()
                        .filter(m -> m.getTitleId().equals(esiTitle.title_id()))
                        .findFirst();

                if (existingOpt.isEmpty()) {
                    String autoRole = "ROLE_" + cleanName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
                    TitleRoleMapping newMapping = new TitleRoleMapping();
                    newMapping.setCorporationId(c.getCorporation().getId());
                    newMapping.setTitleId(esiTitle.title_id());
                    newMapping.setTitleName(cleanName);
                    newMapping.setRoleName(autoRole);
                    titleRepo.save(newMapping);
                    existingMappings.add(newMapping);
                    calculatedRoles.add(autoRole);
                } else {
                    TitleRoleMapping existing = existingOpt.get();
                    if (!cleanName.equals(existing.getTitleName())) {
                        existing.setTitleName(cleanName);
                        titleRepo.save(existing);
                    }
                    if (existing.getRoleName() != null && !existing.getRoleName().isBlank()) {
                        calculatedRoles.add(existing.getRoleName());
                    }
                }
            }
        }

        // =========================================================
        // NEU: Die zwischengeparkten Spezial-Rollen wieder hinzufügen!
        // =========================================================
        calculatedRoles.addAll(retainedSpecialRoles);

        c.setRoles(calculatedRoles);
        characterRepo.save(c);
        log.info("Rollen für {}: {}", c.getName(), calculatedRoles);
    }
}
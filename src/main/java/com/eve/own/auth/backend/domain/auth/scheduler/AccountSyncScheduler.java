package com.eve.own.auth.backend.domain.auth.scheduler;

import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.*;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterSkillRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
import com.eve.own.auth.backend.domain.character.service.AssetSyncService;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AccountSyncScheduler {

    private final AuthService authService;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CharacterStatsRepository statsRepo;
    private final CharacterSkillRepository skillRepo;
    private final AssetSyncService assetSyncService;
    private final InvTypeRepository invTypeRepo;
    private final CorporationRepository corpRepo;
    private final TitleRoleMappingRepository titleRepo;
    private final SystemRoleRepository systemRoleRepo;
    private final MiningTaxRateRepository taxRateRepo;
    private final com.eve.own.auth.backend.domain.assets.service.AssetLocationService assetLocationService;

    private final Long mainCorpId;
    private final String altCorpIdsStr;

    public AccountSyncScheduler(AuthService authService, EsiService esiService,
                                CharacterRepository characterRepo, CharacterStatsRepository statsRepo,
                                CharacterSkillRepository skillRepo,
                                AssetSyncService assetSyncService, InvTypeRepository invTypeRepo,
                                CorporationRepository corpRepo, TitleRoleMappingRepository titleRepo,
                                SystemRoleRepository systemRoleRepo, MiningTaxRateRepository taxRateRepo,
                                com.eve.own.auth.backend.domain.assets.service.AssetLocationService assetLocationService,
                                @Value("${eve.sso.allowed-corp-id}") Long mainCorpId,
                                @Value("${eve.alt-corp-ids:}") String altCorpIdsStr) {
        this.authService = authService;
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.statsRepo = statsRepo;
        this.skillRepo = skillRepo;
        this.assetSyncService = assetSyncService;
        this.invTypeRepo = invTypeRepo;
        this.corpRepo = corpRepo;
        this.titleRepo = titleRepo;
        this.systemRoleRepo = systemRoleRepo;
        this.taxRateRepo = taxRateRepo;
        this.assetLocationService = assetLocationService;
        this.mainCorpId = mainCorpId;
        this.altCorpIdsStr = altCorpIdsStr;
    }

    private List<Long> getAllowedCorps() {
        List<Long> allowedCorps = new ArrayList<>();
        allowedCorps.add(mainCorpId);
        if (altCorpIdsStr != null && !altCorpIdsStr.isBlank()) {
            Arrays.stream(altCorpIdsStr.split(","))
                    .map(String::trim)
                    .map(Long::valueOf)
                    .forEach(allowedCorps::add);
        }
        return allowedCorps;
    }

    @Scheduled(fixedRate = 3600000)
    public void updateJitaPrices() {
        log.info("Aktualisiere Jita Preise für Steuern...");
        List<MiningTaxRate> rates = taxRateRepo.findAll();
        if (rates.isEmpty()) return;

        List<Long> ids = rates.stream().map(MiningTaxRate::getTypeId).toList();
        var prices = esiService.getFuzzworkPrices(ids);
        List<MiningTaxRate> zeroPriceRates = new ArrayList<>();

        if (prices != null && !prices.isEmpty()) {
            for (MiningTaxRate rate : rates) {
                var priceData = prices.get(String.valueOf(rate.getTypeId()));
                Double currentPrice = 0.0;
                if (priceData != null) {
                    if (priceData.buy() != null && priceData.buy().max() != null && priceData.buy().max() > 0) {
                        currentPrice = priceData.buy().max();
                    } else if (priceData.sell() != null && priceData.sell().min() != null && priceData.sell().min() > 0) {
                        currentPrice = priceData.sell().min();
                    }
                }
                if (currentPrice > 0) {
                    rate.setCurrentJitaBuy(currentPrice);
                } else {
                    zeroPriceRates.add(rate);
                }
            }
        } else {
            zeroPriceRates.addAll(rates);
        }

        if (!zeroPriceRates.isEmpty()) {
            Map<Long, MiningTaxRate> compressedIdToRateMap = new HashMap<>();
            List<Long> compressedIds = new ArrayList<>();
            for (MiningTaxRate rate : zeroPriceRates) {
                String name = rate.getTypeName();
                Optional<InvType> compOpt = invTypeRepo.findByTypeNameIgnoreCase("Compressed " + name);
                if (compOpt.isEmpty()) {
                    compOpt = invTypeRepo.findByTypeNameIgnoreCase("Batch Compressed " + name);
                }
                if (compOpt.isPresent()) {
                    Long compId = compOpt.get().getTypeId();
                    compressedIds.add(compId);
                    compressedIdToRateMap.put(compId, rate);
                }
            }
            if (!compressedIds.isEmpty()) {
                var compPrices = esiService.getFuzzworkPrices(compressedIds);
                if (compPrices != null) {
                    for (Long compId : compressedIds) {
                        var priceData = compPrices.get(String.valueOf(compId));
                        if (priceData != null) {
                            Double currentCompPrice = 0.0;
                            if (priceData.buy() != null && priceData.buy().max() != null && priceData.buy().max() > 0) {
                                currentCompPrice = priceData.buy().max();
                            } else if (priceData.sell() != null && priceData.sell().min() != null && priceData.sell().min() > 0) {
                                currentCompPrice = priceData.sell().min();
                            }
                            if (currentCompPrice > 0) {
                                MiningTaxRate rate = compressedIdToRateMap.get(compId);
                                rate.setCurrentJitaBuy(currentCompPrice);
                            }
                        }
                    }
                }
            }
        }
        taxRateRepo.saveAll(rates);
        log.info("Jita Preise erfolgreich synchronisiert (inkl. Compressed-Fallback 1:1).");
    }

    @Scheduled(fixedRate = 600000)
    public void syncAllAccountData() {
        log.info("Starte Account-Sync...");
        List<Character> allChars = characterRepo.findAllWithCorporation();

        for (Character c : allChars) {
            try {
                processSingleCharacter(c);

                Thread.sleep(150);

            } catch (org.springframework.web.client.RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();

                if (statusCode == 420) {
                    log.warn("ESI Rate Limit (420) erreicht bei Charakter {}. Error-Bucket voll, pausiere für 60 Sekunden...", c.getName());
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else if (statusCode == 401 || statusCode == 403) {
                    log.warn("Auth-Fehler ({}) bei Charakter {}. Eventuell Token abgelaufen oder Rechte fehlen.", statusCode, c.getName());
                } else {
                    log.error("ESI API Fehler bei Charakter {}: {} - {}", c.getName(), statusCode, e.getResponseBodyAsString());
                }
            } catch (Exception e) {
                log.error("Genereller Sync-Fehler für Charakter {}: {}", c.getId(), e.getMessage());
            }
        }
        log.info("Account-Sync abgeschlossen.");
    }

    private void processSingleCharacter(Character c) {
        boolean isStillMember = performLeaverCheck(c);
        if (!isStillMember) return;

        syncCorporationFaction(c);
        String token = authService.getValidAccessToken(c);
        syncStats(c, token);
        syncLoyaltyPoints(c, token);
        syncAssets(c, token);
        syncActivities(c, token);
        syncTitlesAndRoles(c, token);
    }

    private boolean performLeaverCheck(Character c) {
        var charPublicInfo = esiService.getCharacter(c.getId()).data();
        if (charPublicInfo == null) return true;

        Long currentCorpId = charPublicInfo.corporation_id();

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

        // NEU: Downgrade auf ROLE_GUEST statt komplettem Rauswurf, wenn er in keiner zugelassenen Corp ist
        if (isMain && !getAllowedCorps().contains(currentCorpId)) {
            Set<String> guestRoles = new HashSet<>();
            guestRoles.add("ROLE_GUEST");
            c.setRoles(guestRoles);
            characterRepo.save(c);

            log.info("Sicherheits-Kick: Main-Charakter {} ist nicht in einer autorisierten Corp (ESI sagt: {}). Rechte auf ROLE_GUEST gesetzt.", c.getName(), currentCorpId);
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

        var walletResp = esiService.getWalletBalance(c.getId(), token);
        if (walletResp.hasData()) {
            stats.setWalletBalance(walletResp.data());
        }

        var skillResp = esiService.getSkills(c.getId(), token);
        if (skillResp.hasData()) {
            stats.setSkillPoints(skillResp.data().total_sp());
        }
        statsRepo.save(stats);

        // Der gleiche Response traegt auch die Einzel-Skills - kein zweiter Call noetig.
        syncSkills(c, skillResp);
    }

    /**
     * Spiegelt die Einzel-Skills fuer den Doktrin-Skillcheck.
     *
     * <p>Ein 304 bedeutet nur dann "nichts zu tun", wenn zu dem Charakter bereits
     * Skills in der Datenbank liegen. Beim ersten Lauf nach dem Deployment ist der
     * ETag-Cache naemlich schon gefuellt, die Tabelle aber noch leer - ohne diese
     * Pruefung bliebe der Charakter dauerhaft ohne Skill-Daten.</p>
     */
    private void syncSkills(Character c, EsiResponse<EsiService.SkillResponse> skillResp) {
        if (skillResp.notModified() && skillRepo.existsByCharacterId(c.getId())) {
            log.debug("Skills von {} unveraendert, Neuschreiben uebersprungen.", c.getName());
            return;
        }
        if (!skillResp.hasData() || skillResp.data().skills() == null) return;

        List<CharacterSkill> mapped = Arrays.stream(skillResp.data().skills())
                .filter(s -> s.skill_id() != null)
                .map(s -> {
                    CharacterSkill cs = new CharacterSkill();
                    cs.setCharacterId(c.getId());
                    cs.setSkillTypeId(s.skill_id());
                    // ESI liefert active_skill_level bei Alpha-Accounts niedriger als trained.
                    cs.setActiveLevel(s.active_skill_level() != null ? s.active_skill_level() : 0);
                    cs.setTrainedLevel(s.trained_skill_level());
                    cs.setSkillpoints(s.skillpoints_in_skill());
                    return cs;
                })
                .collect(Collectors.toList());

        assetSyncService.replaceCharacterSkills(c.getId(), mapped);
    }

    private void syncLoyaltyPoints(Character c, String token) {
        var lpResp = esiService.getLoyaltyPoints(c.getId(), token);
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
        var assetResponse = esiService.getAllAssets(c.getId(), token);

        // Alle Seiten mit 304 beantwortet: der Hangar ist unveraendert, das
        // komplette Loeschen-und-Neuschreiben kann entfallen.
        if (assetResponse.notModified()) {
            log.debug("Assets von {} unveraendert, Neuschreiben uebersprungen.", c.getName());
            return;
        }

        List<EsiService.EsiAssetResponse> esiAssets = assetResponse.dataOr(List.of());
        if (esiAssets.isEmpty()) return;

        // item_id -> location_id, damit wir die Container-Kette hochlaufen koennen.
        // Beispiel: Modul liegt in Container, Container liegt in Schiff, Schiff steht in Citadel.
        Map<Long, Long> itemToLocation = new HashMap<>();
        for (var ea : esiAssets) {
            if (ea.item_id() != null) itemToLocation.put(ea.item_id(), ea.location_id());
        }

        List<CharacterAsset> mappedAssets = esiAssets.stream().map(ea -> {
            CharacterAsset a = new CharacterAsset();
            a.setItemId(ea.item_id());
            a.setCharacterId(c.getId());
            a.setTypeId(ea.type_id());
            a.setLocationId(ea.location_id());
            a.setRootLocationId(assetLocationService.resolveRootLocation(itemToLocation, ea.location_id()));
            a.setLocationFlag(ea.location_flag());
            a.setLocationType(ea.location_type());
            a.setSingleton(ea.is_singleton());
            a.setBlueprintCopy(ea.is_blueprint_copy());
            a.setQuantity(ea.quantity() != null ? ea.quantity() : 1);
            return a;
        }).collect(Collectors.toList());

        assetSyncService.replaceCharacterAssets(c.getId(), mappedAssets);
    }

    private void syncActivities(Character c, String token) {
        java.util.List<CharacterActivity> newActivities = new java.util.ArrayList<>();
        Instant now = Instant.now();

        var miningResp = esiService.getMiningLedger(c.getId(), token);
        if (miningResp.data() != null && miningResp.data().length > 0) {
            List<CharacterMining> miningList = Arrays.stream(miningResp.data()).map(m -> {
                CharacterMining cm = new CharacterMining();
                cm.setCharacterId(c.getId());
                cm.setDate(m.date());
                cm.setTypeId(m.type_id());
                cm.setQuantity(m.quantity());
                return cm;
            }).toList();
            assetSyncService.mergeCharacterMining(c.getId(), miningList);

            java.util.Set<Long> minedTypeIds = Arrays.stream(miningResp.data()).map(EsiService.EsiMiningResponse::type_id).collect(Collectors.toSet());
            java.util.List<InvType> sdeTypes = invTypeRepo.findAllById(minedTypeIds);
            java.util.Map<Long, Double> typeVolumes = sdeTypes.stream().collect(Collectors.toMap(InvType::getTypeId, InvType::getVolume));

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

        var journalResp = esiService.getWalletJournal(c.getId(), token);
        if (journalResp.data() != null) {
            double totalBounty = 0.0;
            long bountyTicks = 0;

            for (var j : journalResp.data()) {
                if ("bounty_prizes".equals(j.ref_type()) && j.amount() != null) {
                    totalBounty += j.amount();
                    bountyTicks++;
                }

                if ("player_donation".equals(j.ref_type()) && j.amount() != null && j.amount() < 0) {
                    if (j.second_party_id() != null && j.second_party_id().equals(mainCorpId)) {
                        if (j.reason() != null) {
                            String r = j.reason().toLowerCase();
                            if (r.contains("steuer") || r.contains("mining") || r.contains("tax")) {
                                CharacterActivity taxPayment = new CharacterActivity();
                                taxPayment.setCharacterId(c.getId());
                                taxPayment.setActivityType("TAX_PAYMENT");
                                taxPayment.setValue(Math.abs(j.amount()));
                                taxPayment.setTimestamp(Instant.parse(j.date()));
                                newActivities.add(taxPayment);
                            }
                        }
                    }
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
            assetSyncService.mergeCharacterActivities(c.getId(), newActivities);
        }
    }

    private void syncTitlesAndRoles(Character c, String token) {
        var titlesResp = esiService.getCharacterTitles(c.getId(), token);
        Set<String> calculatedRoles = new HashSet<>();

        // NEU: Rolle entsprechend der Corporation (Main, Alt oder Extern)
        if (getAllowedCorps().contains(c.getCorporation().getId())) {
            calculatedRoles.add("ROLE_USER");
            calculatedRoles.add("ROLE_MEMBER");
            if (c.getCorporation().getId().equals(mainCorpId)) {
                calculatedRoles.add("ROLE_MARAUDERS_ASSOCIATED");
            }
        } else {
            calculatedRoles.add("ROLE_GUEST");
        }

        List<String> specialRolesInDb = systemRoleRepo.findByIsSpecialTrue().stream().map(SystemRole::getRoleName).toList();
        Set<String> retainedSpecialRoles = c.getRoles().stream().filter(specialRolesInDb::contains).collect(Collectors.toSet());

        if (titlesResp.data() != null && titlesResp.data().length > 0) {
            List<TitleRoleMapping> existingMappings = titleRepo.findByCorporationId(c.getCorporation().getId());

            for (var esiTitle : titlesResp.data()) {
                String cleanName = esiTitle.name().replaceAll("<[^>]*>", "");
                var existingOpt = existingMappings.stream().filter(m -> m.getTitleId().equals(esiTitle.title_id())).findFirst();

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
        calculatedRoles.addAll(retainedSpecialRoles);
        c.setRoles(calculatedRoles);
        characterRepo.save(c);

        log.info("Rollen für {}: {}", c.getName(), calculatedRoles);
    }
}
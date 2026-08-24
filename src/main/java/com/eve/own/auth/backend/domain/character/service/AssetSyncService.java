package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.entity.CharacterSkill;
import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.domain.character.repository.CorporationAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class AssetSyncService {

    private final CharacterAssetRepository assetRepo;
    private final CharacterLpRepository lpRepo;
    private final CharacterActivityRepository activityRepo;
    private final CharacterMiningRepository miningRepo;
    private final CharacterSkillRepository skillRepo;
    private final CorporationAssetRepository corpAssetRepo;

    public AssetSyncService(CharacterAssetRepository assetRepo, CharacterLpRepository lpRepo,
                            CharacterActivityRepository activityRepo, CharacterMiningRepository miningRepo,
                            CharacterSkillRepository skillRepo, CorporationAssetRepository corpAssetRepo) {
        this.assetRepo = assetRepo;
        this.lpRepo = lpRepo;
        this.activityRepo = activityRepo;
        this.miningRepo = miningRepo;
        this.skillRepo = skillRepo;
        this.corpAssetRepo = corpAssetRepo;
    }

    @Transactional
    public void replaceCharacterSkills(Long characterId, List<CharacterSkill> skills) {
        skillRepo.deleteByCharacterId(characterId);
        skillRepo.saveAll(skills);
        log.info("Skill-Snapshot für Charakter {} aktualisiert: {} Skills.", characterId, skills.size());
    }
    
    @Transactional
    public void replaceCharacterAssets(Long characterId, List<CharacterAsset> newAssets) {
        assetRepo.deleteByCharacterId(characterId);
        assetRepo.saveAll(newAssets);
        log.info("Asset-Snapshot für Charakter {} aktualisiert: {} Items.", characterId, newAssets.size());
    }

    @Transactional
    public void replaceCorporationAssets(Long corporationId, List<CorporationAsset> newAssets) {
        corpAssetRepo.deleteByCorporationId(corporationId);
        corpAssetRepo.saveAll(newAssets);
        log.info("Asset-Snapshot für Corporation {} aktualisiert: {} Items.", corporationId, newAssets.size());
    }

    @Transactional
    public void replaceCharacterLp(Long characterId, List<CharacterLp> newLpList) {
        lpRepo.deleteByCharacterId(characterId);
        lpRepo.saveAll(newLpList);
        log.info("LP-Snapshot für Charakter {} aktualisiert: {} Fraktionen.", characterId, newLpList.size());
    }

    @Transactional
    public void mergeCharacterMining(Long characterId, List<CharacterMining> newMiningList) {
        if (newMiningList == null || newMiningList.isEmpty()) return;

        // 1. Was haben wir schon in der Datenbank?
        List<CharacterMining> existingList = miningRepo.findByCharacterId(characterId);
        Map<String, CharacterMining> existingMap = new HashMap<>();
        for (CharacterMining m : existingList) {
            existingMap.put(m.getDate() + "_" + m.getTypeId(), m);
        }

        // 2. Ersetzen oder Ergänzen (Merge)
        List<CharacterMining> toSave = new ArrayList<>();
        for (CharacterMining newM : newMiningList) {
            String key = newM.getDate() + "_" + newM.getTypeId();
            CharacterMining ex = existingMap.get(key);
            if (ex != null) {
                // ESI Update für den Tag (falls der User heute noch weiter geminert hat)
                ex.setQuantity(newM.getQuantity());
                toSave.add(ex);
            } else {
                toSave.add(newM);
            }
        }
        miningRepo.saveAll(toSave);
        log.info("Mining-Ledger für Charakter {} gemerged: {} Einträge.", characterId, toSave.size());
    }

    @Transactional
    public void mergeCharacterActivities(Long characterId, List<CharacterActivity> newActivities) {
        activityRepo.deleteRollingActivitiesByCharacterId(characterId);

        if (newActivities == null || newActivities.isEmpty()) return;

        List<CharacterActivity> existingTaxes = activityRepo.findByCharacterId(characterId).stream()
                .filter(a -> "TAX_PAYMENT".equals(a.getActivityType()))
                .toList();

        List<CharacterActivity> toSave = new ArrayList<>();
        for (CharacterActivity act : newActivities) {
            if ("TAX_PAYMENT".equals(act.getActivityType())) {
                // Exakter Vergleich statt "Differenz kleiner als ein Cent": beide
                // Betraege liegen seit der Umstellung auf numeric(20,2) auf zwei
                // Nachkommastellen gerundet vor, eine Toleranz hat also nichts
                // mehr abzufangen. compareTo und nicht equals - BigDecimal.equals
                // haelt 2.5 und 2.50 fuer verschieden.
                boolean exists = existingTaxes.stream().anyMatch(ex ->
                        ex.getTimestamp() != null && ex.getTimestamp().equals(act.getTimestamp()) &&
                                ex.getValue() != null && act.getValue() != null
                                && ex.getValue().compareTo(act.getValue()) == 0);
                if (!exists) {
                    toSave.add(act);
                }
            } else {
                toSave.add(act);
            }
        }

        if (!toSave.isEmpty()) {
            activityRepo.saveAll(toSave);
            log.info("Aktivitäten für Charakter {} gesynct: {} Einträge.", characterId, toSave.size());
        }
    }

}
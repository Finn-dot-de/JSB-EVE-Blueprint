package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.entity.CharacterSkill;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class AssetSyncService {

    private final CharacterAssetRepository assetRepo;
    private final CharacterLpRepository lpRepo;
    private final CharacterActivityRepository activityRepo;
    private final CharacterMiningRepository miningRepo;
    private final CharacterSkillRepository skillRepo;

    public AssetSyncService(CharacterAssetRepository assetRepo, CharacterLpRepository lpRepo,
                            CharacterActivityRepository activityRepo, CharacterMiningRepository miningRepo,
                            CharacterSkillRepository skillRepo) {
        this.assetRepo = assetRepo;
        this.lpRepo = lpRepo;
        this.activityRepo = activityRepo;
        this.miningRepo = miningRepo;
        this.skillRepo = skillRepo;
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
        java.util.Map<String, CharacterMining> existingMap = new java.util.HashMap<>();
        for (CharacterMining m : existingList) {
            existingMap.put(m.getDate() + "_" + m.getTypeId(), m);
        }

        // 2. Ersetzen oder Ergänzen (Merge)
        List<CharacterMining> toSave = new java.util.ArrayList<>();
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

        List<CharacterActivity> toSave = new java.util.ArrayList<>();
        for (CharacterActivity act : newActivities) {
            if ("TAX_PAYMENT".equals(act.getActivityType())) {
                boolean exists = existingTaxes.stream().anyMatch(ex ->
                        ex.getTimestamp() != null && ex.getTimestamp().equals(act.getTimestamp()) &&
                                ex.getValue() != null && Math.abs(ex.getValue() - act.getValue()) < 0.01);
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
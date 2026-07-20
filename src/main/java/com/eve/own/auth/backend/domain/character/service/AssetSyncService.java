package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
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

    public AssetSyncService(CharacterAssetRepository assetRepo, CharacterLpRepository lpRepo, CharacterActivityRepository activityRepo) {
        this.assetRepo = assetRepo;
        this.lpRepo = lpRepo;
        this.activityRepo = activityRepo;
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
    public void replaceCharacterActivities(Long characterId, List<CharacterActivity> activities) {
        activityRepo.deleteByCharacterId(characterId);
        activityRepo.saveAll(activities);
        log.info("Aktivitäten-Snapshot für Charakter {} aktualisiert: {} Einträge.", characterId, activities.size());
    }
}
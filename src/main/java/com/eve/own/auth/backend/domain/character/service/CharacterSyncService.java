package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.auth.service.CharacterRoleService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import com.eve.own.auth.backend.domain.character.entity.CharacterSkill;
import com.eve.own.auth.backend.domain.character.entity.CharacterStats;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterSkillRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spiegelt saemtliche ESI-Daten eines einzelnen Charakters.
 *
 * <p>Die Reihenfolge ist nicht beliebig: zuerst steht die Frage, ob der Charakter
 * ueberhaupt noch betreut wird. Erst danach lohnt es sich, Token zu erneuern und
 * Daten zu holen.</p>
 */
@Slf4j
@Service
public class CharacterSyncService {

    /** ESI meldet bei Alpha-Accounts kein aktives Level - dann gilt "nicht trainiert". */
    private static final int UNTRAINED_LEVEL = 0;

    private final AuthService authService;
    private final EsiService esiService;
    private final CharacterStatsRepository statsRepo;
    private final CharacterSkillRepository skillRepo;
    private final CharacterAssetRepository assetRepo;
    private final AssetSyncService assetSyncService;
    private final AssetNameResolver assetNameResolver;
    private final AssetMapper assetMapper;
    private final CharacterMembershipService membershipService;
    private final CharacterActivitySyncService activitySyncService;
    private final CharacterRoleService roleService;

    public CharacterSyncService(AuthService authService,
                                EsiService esiService,
                                CharacterStatsRepository statsRepo,
                                CharacterSkillRepository skillRepo,
                                CharacterAssetRepository assetRepo,
                                AssetSyncService assetSyncService,
                                AssetNameResolver assetNameResolver,
                                AssetMapper assetMapper,
                                CharacterMembershipService membershipService,
                                CharacterActivitySyncService activitySyncService,
                                CharacterRoleService roleService) {
        this.authService = authService;
        this.esiService = esiService;
        this.statsRepo = statsRepo;
        this.skillRepo = skillRepo;
        this.assetRepo = assetRepo;
        this.assetSyncService = assetSyncService;
        this.assetNameResolver = assetNameResolver;
        this.assetMapper = assetMapper;
        this.membershipService = membershipService;
        this.activitySyncService = activitySyncService;
        this.roleService = roleService;
    }

    /** Der vollstaendige Durchlauf fuer einen Charakter. */
    public void sync(Character character) {
        if (!membershipService.verifyMembership(character)) {
            return;
        }
        membershipService.refreshCorporationFaction(character);

        String token = authService.getValidAccessToken(character);
        syncStatsAndSkills(character, token);
        syncLoyaltyPoints(character, token);
        syncAssets(character, token);
        activitySyncService.sync(character, token);
        roleService.applyRoles(character, token);
    }

    /**
     * Wallet und Skillpunkte.
     *
     * <p>Der Skill-Aufruf liefert die Einzel-Skills gleich mit, deshalb wird die
     * Antwort direkt weitergereicht statt ein zweites Mal abgefragt.</p>
     */
    private void syncStatsAndSkills(Character character, String token) {
        CharacterStats stats = statsRepo.findById(character.getId()).orElseGet(CharacterStats::new);
        stats.setCharacterId(character.getId());
        stats.setLastUpdated(Instant.now());

        var wallet = esiService.getWalletBalance(character.getId(), token);
        if (wallet.hasData()) {
            stats.setWalletBalance(wallet.data());
        }

        var skills = esiService.getSkills(character.getId(), token);
        if (skills.hasData()) {
            stats.setSkillPoints(skills.data().total_sp());
        }
        statsRepo.save(stats);

        syncSkills(character, skills);
    }

    /**
     * Spiegelt die Einzel-Skills fuer den Doktrin-Skillcheck.
     *
     * <p>Ein 304 bedeutet nur dann "nichts zu tun", wenn zu dem Charakter bereits
     * Skills in der Datenbank liegen. Beim ersten Lauf nach einem Deployment ist
     * der ETag-Cache naemlich schon gefuellt, die Tabelle aber noch leer - ohne
     * diese Pruefung bliebe der Charakter dauerhaft ohne Skill-Daten.</p>
     */
    private void syncSkills(Character character, EsiResponse<EsiService.SkillResponse> response) {
        if (response.notModified() && skillRepo.existsByCharacterId(character.getId())) {
            log.debug("Skills von {} unveraendert, Neuschreiben uebersprungen.", character.getName());
            return;
        }
        if (!response.hasData() || response.data().skills() == null) {
            return;
        }

        List<CharacterSkill> skills = Arrays.stream(response.data().skills())
                .filter(entry -> entry.skill_id() != null)
                .map(entry -> toSkill(character.getId(), entry))
                .toList();

        assetSyncService.replaceCharacterSkills(character.getId(), skills);
    }

    private static CharacterSkill toSkill(Long characterId, EsiService.EsiSkillEntry entry) {
        CharacterSkill skill = new CharacterSkill();
        skill.setCharacterId(characterId);
        skill.setSkillTypeId(entry.skill_id());
        skill.setActiveLevel(entry.active_skill_level() != null ? entry.active_skill_level() : UNTRAINED_LEVEL);
        skill.setTrainedLevel(entry.trained_skill_level());
        skill.setSkillpoints(entry.skillpoints_in_skill());
        return skill;
    }

    private void syncLoyaltyPoints(Character character, String token) {
        var response = esiService.getLoyaltyPoints(character.getId(), token);
        if (response.data() == null) {
            return;
        }
        List<CharacterLp> loyaltyPoints = Arrays.stream(response.data())
                .map(entry -> toLoyaltyPoints(character.getId(), entry))
                .toList();
        assetSyncService.replaceCharacterLp(character.getId(), loyaltyPoints);
    }

    private static CharacterLp toLoyaltyPoints(Long characterId, EsiService.EsiLpResponse entry) {
        CharacterLp loyaltyPoints = new CharacterLp();
        loyaltyPoints.setCharacterId(characterId);
        loyaltyPoints.setCorporationId(entry.corporation_id());
        loyaltyPoints.setLoyaltyPoints(entry.loyalty_points());
        return loyaltyPoints;
    }

    /**
     * Spiegelt den persoenlichen Hangar.
     *
     * <p>Antworten alle Seiten mit 304, ist der Hangar unveraendert und das
     * Loeschen-und-Neuschreiben kann entfallen. Ausnahme: solange zusammengebaute
     * Items ohne abgefragten Namen liegen, muss einmal durchgelaufen werden -
     * sonst blieben die Custom-Namen nach einem Deployment dauerhaft leer.</p>
     */
    private void syncAssets(Character character, String token) {
        var response = esiService.getAllAssets(character.getId(), token);

        if (response.notModified() && !assetRepo.hasPendingCustomNames(character.getId())) {
            log.debug("Assets von {} unveraendert, Neuschreiben uebersprungen.", character.getName());
            return;
        }

        List<EsiService.EsiAssetResponse> esiAssets = response.dataOr(List.of());
        if (esiAssets.isEmpty()) {
            return;
        }

        Map<Long, String> customNames = assetNameResolver.resolve(esiAssets, character.getName(),
                batch -> esiService.getAssetNames(character.getId(), token, batch));

        List<CharacterAsset> assets =
                assetMapper.toCharacterAssets(character.getId(), esiAssets, customNames);
        assetSyncService.replaceCharacterAssets(character.getId(), assets);
    }
}

package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationAssetRepository;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Spiegelt die Corp-Hangars einer Corporation.
 *
 * <p>ESI verlangt fuer diesen Endpunkt neben dem Scope die Ingame-Rolle Director.
 * Welcher unserer Charaktere sie besitzt, ist nicht sicher bekannt: die Rollen in
 * der Datenbank stammen aus Corp-<em>Titeln</em>, nicht aus den echten Corp-Rollen.
 * Deshalb werden die aussichtsreichsten Kandidaten der Reihe nach durchprobiert,
 * bis einer durchkommt.</p>
 */
@Slf4j
@Service
public class CorporationAssetSyncService {

    private final AuthService authService;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationAssetRepository corpAssetRepo;
    private final AssetSyncService assetSyncService;
    private final AssetNameResolver assetNameResolver;
    private final AssetMapper assetMapper;

    public CorporationAssetSyncService(AuthService authService,
                                       EsiService esiService,
                                       CharacterRepository characterRepo,
                                       CorporationAssetRepository corpAssetRepo,
                                       AssetSyncService assetSyncService,
                                       AssetNameResolver assetNameResolver,
                                       AssetMapper assetMapper) {
        this.authService = authService;
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.corpAssetRepo = corpAssetRepo;
        this.assetSyncService = assetSyncService;
        this.assetNameResolver = assetNameResolver;
        this.assetMapper = assetMapper;
    }

    public void sync(Long corporationId) {
        List<Character> candidates = directorCandidates(corporationId);
        if (candidates.isEmpty()) {
            log.debug("Keine Charaktere mit Token in Corporation {} - Corp-Assets uebersprungen.", corporationId);
            return;
        }

        for (Character candidate : candidates) {
            if (trySync(corporationId, candidate)) {
                return;
            }
        }
        log.info("Kein Charakter mit Director-Rechten fuer Corporation {} - Corp-Assets bleiben leer.",
                corporationId);
    }

    /**
     * Ein Versuch mit einem Kandidaten.
     *
     * @return true, wenn die Corporation abgehandelt ist - entweder erfolgreich
     *     gespiegelt oder nachweislich unveraendert
     */
    private boolean trySync(Long corporationId, Character candidate) {
        try {
            String token = authService.getValidAccessToken(candidate);
            if (token == null) {
                return false;
            }

            EsiResponse<List<EsiService.EsiAssetResponse>> response =
                    esiService.getAllCorporationAssets(corporationId, token);

            // Unveraendert heisst nur dann "nichts zu tun", wenn auch keine
            // Custom-Namen mehr ausstehen. Sonst blieben sie nach dem ersten
            // Deployment dauerhaft leer, weil der ETag-Cache bereits gefuellt ist.
            if (response.notModified() && !corpAssetRepo.hasPendingCustomNames(corporationId)) {
                log.debug("Corp-Assets von {} unveraendert, Neuschreiben uebersprungen.", corporationId);
                return true;
            }

            List<EsiService.EsiAssetResponse> esiAssets = response.dataOr(List.of());
            if (esiAssets.isEmpty()) {
                return true;
            }

            Map<Long, String> customNames = assetNameResolver.resolve(
                    esiAssets, "Corporation " + corporationId,
                    batch -> esiService.getCorporationAssetNames(corporationId, token, batch));

            List<CorporationAsset> assets =
                    assetMapper.toCorporationAssets(corporationId, esiAssets, customNames);
            assetSyncService.replaceCorporationAssets(corporationId, assets);
            return true;

        } catch (RestClientResponseException e) {
            if (EsiHttpStatus.isErrorLimited(e)) {
                throw e;
            }
            if (EsiHttpStatus.isForbidden(e)) {
                log.debug("{} hat keine Director-Rechte in Corp {}, naechster Kandidat.",
                        candidate.getName(), corporationId);
                return false;
            }
            log.warn("Corp-Assets fuer {} ueber {} nicht abrufbar: {}",
                    corporationId, candidate.getName(), e.getStatusCode());
            return false;
        } catch (Exception e) {
            log.warn("Corp-Assets fuer {} ueber {} fehlgeschlagen: {}",
                    corporationId, candidate.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Die Charaktere der Corporation, die als Token-Geber in Frage kommen -
     * der aussichtsreichste zuerst.
     *
     * <p>Charaktere ohne jede Fuehrungsrolle bleiben aussen vor: ESI antwortet
     * ihnen ohnehin mit 403 und wir verbrennen nur Fehler-Budget.</p>
     */
    private List<Character> directorCandidates(Long corporationId) {
        return characterRepo.findAllWithCorporation().stream()
                .filter(character -> character.getCorporation() != null
                        && corporationId.equals(character.getCorporation().getId()))
                .filter(character -> character.getRefreshToken() != null)
                .filter(character -> directorRank(character) > 0)
                .sorted(Comparator.comparingInt(CorporationAssetSyncService::directorRank).reversed())
                .toList();
    }

    /** Wie aussichtsreich ein Charakter als Director-Token-Geber ist; 0 = chancenlos. */
    private static int directorRank(Character character) {
        if (character.hasRole(SystemRoles.CEO)) {
            return 3;
        }
        if (character.hasRole(SystemRoles.DIRECTOR)) {
            return 2;
        }
        if (character.hasRole(SystemRoles.IT_ADMIN)) {
            return 1;
        }
        return 0;
    }
}

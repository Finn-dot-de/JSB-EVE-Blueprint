package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.domain.character.repository.CorporationAssetRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spiegelt die Corp-Hangars einer Corporation.
 *
 * <p>ESI verlangt fuer diesen Endpunkt neben dem Scope die Ingame-Rolle Director.
 * Welcher unserer Charaktere sie besitzt, ist nicht sicher bekannt: die Rollen in
 * der Datenbank stammen aus Corp-<em>Titeln</em>, nicht aus den echten Corp-Rollen.
 * Deshalb werden die aussichtsreichsten Kandidaten der Reihe nach durchprobiert,
 * bis einer durchkommt.</p>
 *
 * <p>Das Durchprobieren steht seit der Zusammenfuehrung im
 * {@link DirectorTokenProvider}. Es war vorher zweimal ausgeschrieben - hier
 * richtig, in der Titelabfrage falsch. Genau daran laesst sich sehen, warum eine
 * zweite Kopie kein Luxusproblem ist: die Fehlerbehebung erreichte nur eine der
 * beiden Stellen.</p>
 */
@Slf4j
@Service
public class CorporationAssetSyncService {

    /** Der Scope, den dieser Endpunkt verlangt - nur zur Fehlerdeutung im Protokoll. */
    private static final String CORP_ASSETS_SCOPE = "esi-assets.read_corporation_assets.v1";

    private final DirectorTokenProvider directorTokens;
    private final EsiService esiService;
    private final CorporationAssetRepository corpAssetRepo;
    private final AssetSyncService assetSyncService;
    private final AssetNameResolver assetNameResolver;
    private final AssetMapper assetMapper;

    public CorporationAssetSyncService(DirectorTokenProvider directorTokens,
                                       EsiService esiService,
                                       CorporationAssetRepository corpAssetRepo,
                                       AssetSyncService assetSyncService,
                                       AssetNameResolver assetNameResolver,
                                       AssetMapper assetMapper) {
        this.directorTokens = directorTokens;
        this.esiService = esiService;
        this.corpAssetRepo = corpAssetRepo;
        this.assetSyncService = assetSyncService;
        this.assetNameResolver = assetNameResolver;
        this.assetMapper = assetMapper;
    }

    public void sync(Long corporationId) {
        DirectorTokenProvider.DirectorAttempt<Boolean> attempt = directorTokens.attempt(
                corporationId, CORP_ASSETS_SCOPE, token -> syncWith(corporationId, token));

        if (attempt.succeeded()) {
            return;
        }
        if (attempt.noCandidateTried()) {
            log.debug("Keine Charaktere mit Token in Corporation {} - Corp-Assets uebersprungen.", corporationId);
            return;
        }
        log.info("Kein Charakter mit Director-Rechten fuer Corporation {} - Corp-Assets bleiben leer.",
                corporationId);
    }

    /**
     * Ein Versuch mit dem Token eines Kandidaten.
     *
     * <p>Kommt diese Methode ohne Ausnahme zurueck, ist die Corporation
     * abgehandelt - entweder erfolgreich gespiegelt oder nachweislich
     * unveraendert. Ein Fehlschlag meldet sich als Ausnahme; der
     * {@link DirectorTokenProvider} nimmt dann den naechsten Kandidaten.</p>
     */
    private Boolean syncWith(Long corporationId, String token) {
        EsiResponse<List<EsiService.EsiAssetResponse>> response =
                esiService.getAllCorporationAssets(corporationId, token);

        // Unveraendert heisst nur dann "nichts zu tun", wenn auch keine
        // Custom-Namen mehr ausstehen. Sonst blieben sie nach dem ersten
        // Deployment dauerhaft leer, weil der ETag-Cache bereits gefuellt ist.
        if (response.notModified() && !corpAssetRepo.hasPendingCustomNames(corporationId)) {
            log.debug("Corp-Assets von {} unveraendert, Neuschreiben uebersprungen.", corporationId);
            return Boolean.TRUE;
        }

        List<EsiService.EsiAssetResponse> esiAssets = response.dataOr(List.of());
        if (esiAssets.isEmpty()) {
            return Boolean.TRUE;
        }

        Map<Long, String> customNames = assetNameResolver.resolve(
                esiAssets, "Corporation " + corporationId,
                batch -> esiService.getCorporationAssetNames(corporationId, token, batch));

        List<CorporationAsset> assets =
                assetMapper.toCorporationAssets(corporationId, esiAssets, customNames);
        assetSyncService.replaceCorporationAssets(corporationId, assets);
        return Boolean.TRUE;
    }
}

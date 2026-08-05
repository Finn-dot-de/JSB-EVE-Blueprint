package com.eve.own.auth.backend.domain.assets.service;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import jakarta.persistence.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository.lng;
import static com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository.str;

/**
 * Selbstauskunft fuer Mitglieder: die gleiche Suche wie im Asset-Audit der
 * Direktoren, aber hart auf den eigenen Account begrenzt.
 *
 * <p><b>Sicherheitsprinzip:</b> der Scope wird ausschliesslich aus dem
 * authentifizierten Charakter abgeleitet und in {@link #scoped} auf jede Anfrage
 * aufgezwungen. Was der Client an {@code mainId}, {@code characterId} oder
 * {@code corporationId} mitschickt, wird ueberschrieben bzw. gegen die eigenen
 * Charaktere geprueft. Ein manipulierter Request kann damit keine fremden
 * Bestaende sichtbar machen.</p>
 */
@Slf4j
@Service
public class MyAssetService {

    private final AssetQueryRepository queryRepo;
    private final CharacterRepository characterRepo;
    private final AssetAnalyticsService analyticsService;

    public MyAssetService(AssetQueryRepository queryRepo,
                          CharacterRepository characterRepo,
                          AssetAnalyticsService analyticsService) {
        this.queryRepo = queryRepo;
        this.characterRepo = characterRepo;
        this.analyticsService = analyticsService;
    }

    // ==================================================================
    // Scope-Ermittlung
    // ==================================================================

    /**
     * Der Account des eingeloggten Charakters: dessen Main, oder er selbst,
     * wenn er keinem Main zugeordnet ist.
     */
    @Transactional(readOnly = true)
    public Long resolveMainId(Long characterId) {
        Character c = characterRepo.findById(characterId)
                .orElseThrow(() -> new IllegalStateException("Charakter " + characterId + " ist nicht registriert."));
        return c.getMainCharacterId() != null ? c.getMainCharacterId() : c.getId();
    }

    /** Alle Charaktere des Accounts - Grundlage fuer die Pruefung der characterId. */
    @Transactional(readOnly = true)
    public Set<Long> ownCharacterIds(Long mainId) {
        return queryRepo.charactersOfMain(mainId).stream()
                .map(t -> lng(t, "id"))
                .collect(Collectors.toSet());
    }

    /**
     * Erzwingt den Account-Scope auf einer eingehenden Suchanfrage.
     *
     * <ul>
     *   <li>{@code mainId} wird immer auf den eigenen Account gesetzt.</li>
     *   <li>{@code characterId} bleibt nur erhalten, wenn es ein eigener
     *       Charakter ist - sonst wird es verworfen statt die Anfrage
     *       abzulehnen, damit ein veralteter Filter im Frontend nicht
     *       zu einer Fehlermeldung fuehrt.</li>
     *   <li>{@code corporationId} wird verworfen: eine Corp-weite Auswertung
     *       ist genau das, was diese Sicht nicht leisten soll.</li>
     *   <li>{@code ownerType} wird auf CHARACTER festgenagelt. Die Bestaende der
     *       Corp-Hangars gehoeren ins Director-Audit, nicht in die
     *       Selbstauskunft - der Account-Filter allein wuerde sie zwar auch
     *       ausschliessen, aber diese Zeile macht es unmissverstaendlich.</li>
     * </ul>
     */
    private AssetDtos.AssetSearchRequest scoped(AssetDtos.AssetSearchRequest req, Long mainId,
                                                Set<Long> ownCharacterIds) {
        Long characterId = req.characterId() != null && ownCharacterIds.contains(req.characterId())
                ? req.characterId()
                : null;

        if (req.characterId() != null && characterId == null) {
            log.debug("Fremde characterId {} in der Mitglieder-Suche verworfen (Account {}).",
                    req.characterId(), mainId);
        }

        return new AssetDtos.AssetSearchRequest(
                req.q(), req.typeId(), req.groupId(), req.categoryId(),
                characterId,
                mainId,
                null,
                req.locationId(), req.regionName(), req.locationFlag(),
                req.minQuantity(), req.minValue(), req.shipsOnly(),
                "CHARACTER",
                req.sort(), req.direction(), req.page(), req.size(), req.grouped());
    }

    // ==================================================================
    // Suche
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.PageDto<AssetDtos.AssetRowDto> search(Long characterId, AssetDtos.AssetSearchRequest req) {
        Long mainId = resolveMainId(characterId);
        return queryRepo.search(scoped(req, mainId, ownCharacterIds(mainId)));
    }

    @Transactional(readOnly = true)
    public AssetDtos.PageDto<AssetDtos.AssetStackDto> searchGrouped(Long characterId, AssetDtos.AssetSearchRequest req) {
        Long mainId = resolveMainId(characterId);
        return queryRepo.searchGrouped(scoped(req, mainId, ownCharacterIds(mainId)));
    }

    @Transactional(readOnly = true)
    public String exportCsv(Long characterId, AssetDtos.AssetSearchRequest req) {
        Long mainId = resolveMainId(characterId);
        return analyticsService.exportCsv(scoped(req, mainId, ownCharacterIds(mainId)));
    }

    // ==================================================================
    // Uebersicht
    // ==================================================================

    /**
     * Kennzahlen des eigenen Accounts. Nutzt dieselbe Auswertung wie die
     * Member-Detailansicht der Direktoren - nur eben mit der selbst ermittelten
     * mainId statt einer aus dem Request.
     */
    @Transactional(readOnly = true)
    public AssetDtos.MemberAssetDetailDto summary(Long characterId) {
        return analyticsService.memberDetail(resolveMainId(characterId));
    }

    // ==================================================================
    // Filter-Optionen & Typeahead
    // ==================================================================

    @Transactional(readOnly = true)
    public AssetDtos.MyFilterOptionsDto filterOptions(Long characterId, Long categoryId) {
        Long mainId = resolveMainId(characterId);
        return new AssetDtos.MyFilterOptionsDto(
                mapIdNames(queryRepo.distinctCategoriesForMain(mainId)),
                mapIdNames(queryRepo.distinctGroupsForMain(mainId, categoryId)),
                mapIdNames(queryRepo.distinctLocationsForMain(mainId)),
                queryRepo.distinctRegionsForMain(mainId),
                queryRepo.distinctLocationFlagsForMain(mainId),
                mapIdNames(queryRepo.charactersOfMain(mainId))
        );
    }

    @Transactional(readOnly = true)
    public List<AssetDtos.TypeSuggestionDto> suggestTypes(Long characterId, String term, int limit) {
        Long mainId = resolveMainId(characterId);
        return queryRepo.suggestTypesForMain(mainId, term, limit).stream()
                .map(t -> new AssetDtos.TypeSuggestionDto(
                        lng(t, "typeId"), str(t, "typeName"), str(t, "groupName"),
                        "https://images.evetech.net/types/" + lng(t, "typeId") + "/icon?size=64",
                        lng(t, "quantity")))
                .toList();
    }

    private List<AssetDtos.IdNameDto> mapIdNames(List<Tuple> rows) {
        return rows.stream()
                .map(t -> new AssetDtos.IdNameDto(lng(t, "id"), str(t, "name")))
                .filter(d -> d.name() != null)
                .toList();
    }
}

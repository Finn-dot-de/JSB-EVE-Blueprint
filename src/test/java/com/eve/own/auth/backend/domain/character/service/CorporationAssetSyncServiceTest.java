package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationAssetRepository;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/**
 * Der Corp-Hangar verlangt ein Token mit Ingame-Director-Rechten. Wer die hat,
 * ist nicht sicher bekannt - deshalb werden Kandidaten durchprobiert.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Spiegeln der Corp-Hangars")
class CorporationAssetSyncServiceTest {

    private static final Long CORPORATION_ID = 98000001L;

    @Mock private AuthService authService;
    @Mock private EsiService esiService;
    @Mock private CharacterRepository characterRepo;
    @Mock private CorporationAssetRepository corpAssetRepo;
    @Mock private AssetSyncService assetSyncService;
    @Mock private AssetNameResolver assetNameResolver;
    @Mock private AssetMapper assetMapper;

    private CorporationAssetSyncService service;

    @BeforeEach
    void setUp() {
        service = new CorporationAssetSyncService(authService, esiService, characterRepo,
                corpAssetRepo, assetSyncService, assetNameResolver, assetMapper);

        when(authService.getValidAccessToken(any())).thenReturn("token");
        when(assetNameResolver.resolve(anyList(), anyString(), any())).thenReturn(Map.of());
        when(assetMapper.toCorporationAssets(anyLong(), anyList(), any()))
                .thenReturn(List.of(new CorporationAsset()));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(List.of(asset()), null, null));
    }

    private static EsiService.EsiAssetResponse asset() {
        return new EsiService.EsiAssetResponse(1L, 587L, 60003760L, 1, true,
                "CorpSAG1", "station", false);
    }

    private static Character candidate(Long id, String name, String... roles) {
        Corporation corporation = new Corporation();
        corporation.setId(CORPORATION_ID);

        Character character = new Character();
        character.setId(id);
        character.setName(name);
        character.setCorporation(corporation);
        character.setRefreshToken("refresh");
        character.setRoles(Set.of(roles));
        return character;
    }

    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException("Fehler", status, "", HttpHeaders.EMPTY, null, null);
    }

    @Test
    @DisplayName("spiegelt den Hangar ueber einen Director")
    void syncsViaDirector() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.DIRECTOR)));

        service.sync(CORPORATION_ID);

        verify(assetSyncService).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("probiert die aussichtsreichsten Kandidaten zuerst")
    void triesMostPromisingFirst() {
        Character itAdmin = candidate(1L, "Technik", SystemRoles.IT_ADMIN);
        Character ceo = candidate(2L, "CEO", SystemRoles.CEO);
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(itAdmin, ceo));

        service.sync(CORPORATION_ID);

        // Der CEO ist der aussichtsreichste - er kommt zuerst dran und reicht.
        verify(authService).getValidAccessToken(ceo);
        verify(authService, never()).getValidAccessToken(itAdmin);
    }

    @Test
    @DisplayName("geht bei einer Absage zum naechsten Kandidaten")
    void movesToNextCandidateOnForbidden() {
        Character ceo = candidate(1L, "CEO", SystemRoles.CEO);
        Character director = candidate(2L, "Director", SystemRoles.DIRECTOR);
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(ceo, director));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenThrow(httpError(403))
                .thenReturn(EsiResponse.changed(List.of(asset()), null, null));

        service.sync(CORPORATION_ID);

        verify(esiService, times(2)).getAllCorporationAssets(anyLong(), anyString());
        verify(assetSyncService).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("fragt Charaktere ohne Fuehrungsrolle gar nicht erst")
    void skipsHopelessCandidates() {
        // Ohne Rolle antwortet ESI ohnehin mit 403 - das kostet nur Fehler-Budget.
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Mitglied", SystemRoles.MEMBER)));

        service.sync(CORPORATION_ID);

        verify(authService, never()).getValidAccessToken(any());
        verify(assetSyncService, never()).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("uebergeht Charaktere ohne hinterlegtes Token")
    void skipsCandidatesWithoutToken() {
        Character withoutToken = candidate(1L, "Chefin", SystemRoles.CEO);
        withoutToken.setRefreshToken(null);
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(withoutToken));

        service.sync(CORPORATION_ID);

        verify(authService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("uebergeht Charaktere anderer Corporations")
    void skipsCharactersOfOtherCorporations() {
        Character foreign = candidate(1L, "Fremd", SystemRoles.CEO);
        foreign.getCorporation().setId(99999999L);
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(foreign));

        service.sync(CORPORATION_ID);

        verify(authService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("ueberspringt das Neuschreiben bei unveraendertem Hangar")
    void skipsRewriteWhenUnchanged() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.CEO)));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenReturn(EsiResponse.unchanged(List.of(asset()), null, null));
        when(corpAssetRepo.hasPendingCustomNames(CORPORATION_ID)).thenReturn(false);

        service.sync(CORPORATION_ID);

        verify(assetSyncService, never()).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("laeuft trotz 304 durch, solange Custom-Namen ausstehen")
    void writesDespite304WhenNamesArePending() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.CEO)));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenReturn(EsiResponse.unchanged(List.of(asset()), null, null));
        when(corpAssetRepo.hasPendingCustomNames(CORPORATION_ID)).thenReturn(true);

        service.sync(CORPORATION_ID);

        verify(assetSyncService).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("reicht das aufgebrauchte Fehler-Budget nach oben durch")
    void propagatesErrorLimit() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.CEO)));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenThrow(httpError(EsiHttpStatus.ERROR_LIMITED));

        assertThatThrownBy(() -> service.sync(CORPORATION_ID))
                .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    @DisplayName("laeuft weiter, wenn ein Kandidat unerwartet scheitert")
    void survivesUnexpectedFailure() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.CEO)));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("kaputt"));

        service.sync(CORPORATION_ID);

        verify(assetSyncService, never()).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("macht nichts, wenn die Corporation gar keine Kandidaten hat")
    void doesNothingWithoutCandidates() {
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of());

        service.sync(CORPORATION_ID);

        verify(authService, never()).getValidAccessToken(any());
    }

    @Test
    @DisplayName("schreibt bei leerem Hangar nichts")
    void skipsEmptyHangar() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.CEO)));
        when(esiService.getAllCorporationAssets(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(List.of(), null, null));

        service.sync(CORPORATION_ID);

        verify(assetSyncService, never()).replaceCorporationAssets(anyLong(), anyList());
    }

    @Test
    @DisplayName("bricht ab, wenn kein Token zu bekommen ist")
    void handlesMissingToken() {
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(1L, "Chefin", SystemRoles.CEO)));
        when(authService.getValidAccessToken(any())).thenReturn(null);

        service.sync(CORPORATION_ID);

        verify(esiService, never()).getAllCorporationAssets(anyLong(), anyString());
    }
}

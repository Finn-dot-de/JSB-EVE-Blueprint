package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests der Bestandszaehlung.
 *
 * <p>Die Besitzliste ist eine grosse ESI-Abfrage. Die Tests halten fest, dass nur Injectors
 * gezaehlt werden, dass ueber alle Hangar-Standorte hinweg addiert wird und dass ein
 * Fehlschlag eine erklaerende Antwort statt einer Ausnahme liefert.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InjectorInventoryService")
class InjectorInventoryServiceTest {

    private static final long CHARACTER = 2118431553L;
    private static final long INJECTOR = MarketService.LARGE_SKILL_INJECTOR_TYPE_ID;
    private static final long TRITANIUM = 34L;

    @Mock private AuthService authService;
    @Mock private CharacterRepository characterRepo;
    @Mock private EsiService esiService;

    private InjectorInventoryService service;

    @BeforeEach
    void setUp() {
        service = new InjectorInventoryService(authService, characterRepo, esiService);

        Character character = new Character();
        character.setId(CHARACTER);
        character.setName("Testpilot");
        lenient().when(characterRepo.findById(CHARACTER)).thenReturn(Optional.of(character));
        lenient().when(authService.getValidAccessToken(any())).thenReturn("token");
        lenient().when(authService.tokenHasScope(anyString(), anyString())).thenReturn(true);
    }

    /**
     * Baut eine Position der Besitzliste.
     *
     * @param typeId   Item-Typ
     * @param quantity Stueckzahl
     * @return die Position
     */
    private EsiService.EsiAssetResponse position(long typeId, int quantity) {
        return new EsiService.EsiAssetResponse(1L, typeId, 60003760L, quantity, false);
    }

    @Test
    @DisplayName("zaehlt nur Skill Injectors, keine anderen Items")
    void countsOnlyInjectors() {
        when(esiService.getAllAssets(anyLong(), anyString())).thenReturn(List.of(
                position(INJECTOR, 7),
                position(TRITANIUM, 1_000_000),
                position(620L, 3)));

        InjectorInventoryService.InjectorStock stock = service.getStock(CHARACTER);

        assertThat(stock.available()).isTrue();
        assertThat(stock.quantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("addiert Stapel aus verschiedenen Hangars")
    void addsUpStacksAcrossLocations() {
        when(esiService.getAllAssets(anyLong(), anyString())).thenReturn(List.of(
                position(INJECTOR, 5),
                position(INJECTOR, 12),
                position(INJECTOR, 1)));

        assertThat(service.getStock(CHARACTER).quantity()).isEqualTo(18);
    }

    @Test
    @DisplayName("meldet null Injectors, wenn keine im Besitz sind")
    void reportsZeroWhenNoneOwned() {
        when(esiService.getAllAssets(anyLong(), anyString())).thenReturn(List.of(position(TRITANIUM, 500)));

        InjectorInventoryService.InjectorStock stock = service.getStock(CHARACTER);

        assertThat(stock.available()).isTrue();
        assertThat(stock.quantity()).isZero();
    }

    @Test
    @DisplayName("erklaert den fehlenden Scope, statt ESI erst antworten zu lassen")
    void explainsMissingScope() {
        when(authService.tokenHasScope("token", InjectorInventoryService.ASSETS_SCOPE)).thenReturn(false);

        InjectorInventoryService.InjectorStock stock = service.getStock(CHARACTER);

        assertThat(stock.available()).isFalse();
        assertThat(stock.quantity()).isNull();
        assertThat(stock.hint()).contains("neu an");
        verify(esiService, never()).getAllAssets(anyLong(), anyString());
    }

    @Test
    @DisplayName("liefert eine Begruendung, wenn ESI nicht antwortet")
    void explainsFailingEsi() {
        when(esiService.getAllAssets(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("ESI down"));

        InjectorInventoryService.InjectorStock stock = service.getStock(CHARACTER);

        assertThat(stock.available()).isFalse();
        assertThat(stock.hint()).contains("nicht abrufbar");
    }

    @Test
    @DisplayName("meldet einen nicht verknuepften Charakter")
    void reportsUnknownCharacter() {
        when(characterRepo.findById(999L)).thenReturn(Optional.empty());

        InjectorInventoryService.InjectorStock stock = service.getStock(999L);

        assertThat(stock.available()).isFalse();
        assertThat(stock.hint()).contains("nicht verknuepft");
    }

    @Test
    @DisplayName("fragt die Besitzliste nicht bei jedem Aufruf neu ab")
    void cachesTheResult() {
        when(esiService.getAllAssets(anyLong(), anyString())).thenReturn(List.of(position(INJECTOR, 4)));

        service.getStock(CHARACTER);
        service.getStock(CHARACTER);
        service.getStock(CHARACTER);

        verify(esiService, times(1)).getAllAssets(anyLong(), anyString());
    }

    @Test
    @DisplayName("fragt nach dem Verwerfen wieder frisch ab")
    void refetchesAfterInvalidate() {
        when(esiService.getAllAssets(anyLong(), anyString())).thenReturn(List.of(position(INJECTOR, 4)));

        service.getStock(CHARACTER);
        service.invalidate(CHARACTER);
        service.getStock(CHARACTER);

        verify(esiService, times(2)).getAllAssets(anyLong(), anyString());
    }
}

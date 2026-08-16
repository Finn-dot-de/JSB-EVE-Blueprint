package com.eve.own.auth.backend.domain.character.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.service.CharacterSyncService;
import com.eve.own.auth.backend.domain.character.service.CorporationAssetSyncService;
import com.eve.own.auth.backend.domain.mining.scheduler.MiningPriceScheduler;
import com.eve.own.auth.backend.domain.mining.service.MiningPriceService;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/**
 * Die Zeitgeber selbst enthalten keine Fachlogik - sie entscheiden nur, wann
 * gearbeitet wird und was bei Fehlern geschieht. Genau das wird geprueft.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Zeitgesteuerte Ablaeufe")
class SchedulerTest {

    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException("Fehler", status, "", HttpHeaders.EMPTY, null, null);
    }

    private static Character character(Long id, String name) {
        Character character = new Character();
        character.setId(id);
        character.setName(name);
        return character;
    }

    @Nested
    @DisplayName("Charakter-Sync")
    class CharacterSync {

        @Mock private CharacterRepository characterRepo;
        @Mock private CharacterSyncService syncService;

        private CharacterSyncScheduler scheduler() {
            return new CharacterSyncScheduler(characterRepo, syncService);
        }

        @Test
        @DisplayName("laeuft alle Charaktere durch")
        void syncsEveryCharacter() {
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(character(1L, "A"), character(2L, "B")));

            scheduler().syncAllCharacters();

            verify(syncService, times(2)).sync(any());
        }

        @Test
        @DisplayName("laesst einen Fehler bei einem Charakter die uebrigen nicht aufhalten")
        void oneFailureDoesNotStopTheRest() {
            Character first = character(1L, "A");
            Character second = character(2L, "B");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(first, second));
            doThrow(new RuntimeException("kaputt")).when(syncService).sync(first);

            scheduler().syncAllCharacters();

            verify(syncService).sync(second);
        }

        @Test
        @DisplayName("laeuft nach einem Auth-Fehler mit dem naechsten Charakter weiter")
        void continuesAfterAuthError() {
            Character first = character(1L, "A");
            Character second = character(2L, "B");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(first, second));
            doThrow(httpError(403)).when(syncService).sync(first);

            scheduler().syncAllCharacters();

            verify(syncService).sync(second);
        }

        @Test
        @DisplayName("laeuft auch nach anderen ESI-Fehlern weiter")
        void continuesAfterOtherEsiErrors() {
            Character first = character(1L, "A");
            Character second = character(2L, "B");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(first, second));
            doThrow(httpError(500)).when(syncService).sync(first);

            scheduler().syncAllCharacters();

            verify(syncService).sync(second);
        }

        @Test
        @DisplayName("pausiert beim aufgebrauchten Fehler-Budget")
        void pausesOnErrorLimit() {
            Character first = character(1L, "A");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(first));
            doThrow(httpError(EsiHttpStatus.ERROR_LIMITED)).when(syncService).sync(first);

            long start = System.nanoTime();
            Thread worker = new Thread(() -> scheduler().syncAllCharacters());
            worker.start();
            // Die Zwangspause laeuft; ein Abbruch beendet den Durchlauf sofort.
            worker.interrupt();
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThat(worker.isAlive()).isFalse();
            assertThat(System.nanoTime() - start).isLessThan(60_000_000_000L);
        }

        @Test
        @DisplayName("kommt ohne Charaktere zurecht")
        void survivesEmptyDatabase() {
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of());

            scheduler().syncAllCharacters();

            verify(syncService, times(0)).sync(any());
        }
    }

    @Nested
    @DisplayName("Corp-Asset-Sync")
    class CorporationAssets {

        @Mock private CorporationAssetSyncService syncService;

        private CorporationAssetScheduler scheduler(String altCorporationIds) {
            return new CorporationAssetScheduler(
                    new CorporationScope(98000001L, altCorporationIds), syncService);
        }

        @Test
        @DisplayName("spiegelt jede betreute Corporation")
        void syncsEveryCorporation() {
            scheduler("98000002").syncCorporationAssets();

            verify(syncService).sync(98000001L);
            verify(syncService).sync(98000002L);
        }

        @Test
        @DisplayName("laesst einen Fehler bei einer Corporation die uebrigen nicht aufhalten")
        void oneFailureDoesNotStopTheRest() {
            doThrow(new RuntimeException("kaputt")).when(syncService).sync(98000001L);

            scheduler("98000002").syncCorporationAssets();

            verify(syncService).sync(98000002L);
        }
    }

    @Nested
    @DisplayName("Jita-Preise")
    class MiningPrices {

        @Mock private MiningPriceService priceService;

        @Test
        @DisplayName("stoesst den Preisabgleich an")
        void triggersPriceRefresh() {
            new MiningPriceScheduler(priceService).refreshJitaPrices();

            verify(priceService).refreshJitaPrices();
        }

        @Test
        @DisplayName("faengt einen Fehlschlag ab, damit der Zeitgeber weiterlaeuft")
        void survivesFailure() {
            doThrow(new RuntimeException("Markt weg")).when(priceService).refreshJitaPrices();

            new MiningPriceScheduler(priceService).refreshJitaPrices();

            verify(priceService).refreshJitaPrices();
        }
    }
}

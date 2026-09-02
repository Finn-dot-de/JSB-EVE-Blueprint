package com.eve.own.auth.backend.domain.character.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.service.AltSourceRetentionService;
import com.eve.own.auth.backend.domain.character.service.CharacterSyncService;
import com.eve.own.auth.backend.domain.character.service.ContactSyncService;
import com.eve.own.auth.backend.domain.character.service.CorporationAssetSyncService;
import com.eve.own.auth.backend.domain.character.service.MailCountSyncService;
import com.eve.own.auth.backend.domain.character.service.MemberPresenceSyncService;
import com.eve.own.auth.backend.domain.assets.scheduler.AssetPriceScheduler;
import com.eve.own.auth.backend.domain.industry.service.IndustrySyncService;
import com.eve.own.auth.backend.domain.market.MarketPriceScheduler;
import com.eve.own.auth.backend.domain.market.MarketSnapshot;
import com.eve.own.auth.backend.domain.market.MarketSnapshotService;
import com.eve.own.auth.backend.domain.market.MarketSnapshotUnavailableException;
import com.eve.own.auth.backend.domain.market.StationPrice;
import com.eve.own.auth.backend.domain.mining.service.MiningPriceService;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * Der Takt der drei Erfassungen, die eigene ESI-Aufrufe kosten.
     *
     * <p>Geprueft wird hier nur, was der Zeitgeber entscheidet: wen er fragt,
     * wen er auslaesst und was ein Fehlschlag anrichten darf. Was erfasst wird,
     * steht in den Diensten und wird dort geprueft.</p>
     */
    @Nested
    @DisplayName("Erfassung der Alt-Datenquellen")
    class AltSources {

        @Mock private CharacterRepository characterRepo;
        @Mock private AuthService authService;
        @Mock private MemberPresenceSyncService presenceSync;
        @Mock private ContactSyncService contactSync;
        @Mock private MailCountSyncService mailSync;

        private AltSourceScheduler scheduler(String altCorporationIds) {
            return new AltSourceScheduler(new CorporationScope(98000001L, altCorporationIds),
                    characterRepo, authService, presenceSync, contactSync, mailSync);
        }

        private Character mitToken(Long id, String name) {
            Character character = character(id, name);
            character.setRefreshToken("refresh");
            return character;
        }

        @Test
        @DisplayName("zeichnet die Anwesenheit je betreuter Corporation auf")
        void recordsPresenceForEveryCorporation() {
            scheduler("98000002").recordMemberPresence();

            // Ein Aufruf je Corporation - und er deckt auch die unregistrierten
            // Mitglieder ab. Das ist der ganze Grund fuer diese Quelle.
            verify(presenceSync).sync(98000001L);
            verify(presenceSync).sync(98000002L);
        }

        @Test
        @DisplayName("laesst einen Fehler bei einer Corporation die uebrigen nicht aufhalten")
        void oneCorporationFailureDoesNotStopTheRest() {
            doThrow(new RuntimeException("kaputt")).when(presenceSync).sync(98000001L);

            scheduler("98000002").recordMemberPresence();

            verify(presenceSync).sync(98000002L);
        }

        @Test
        @DisplayName("bricht den Anwesenheitslauf beim aufgebrauchten Fehler-Budget ab")
        void stopsPresenceRunOnErrorLimit() {
            doThrow(httpError(EsiHttpStatus.ERROR_LIMITED)).when(presenceSync).sync(98000001L);

            scheduler("98000002").recordMemberPresence();

            // Ohne diese Zeile liefe der Lauf weiter und verlaengerte das
            // Zeitfenster, in dem CCP uns aussperrt - jeder Versuch kostet
            // erneut Budget. Das Projekt ist bei Discord schon einmal in ein
            // Rate-Limit gelaufen.
            verify(presenceSync, never()).sync(98000002L);
        }

        @Test
        @DisplayName("holt Kontakte und Mail-Zaehlung mit einem Token je Charakter")
        void syncsContactsAndMailWithOneToken() {
            Character pilot = mitToken(1L, "A");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(pilot));
            when(authService.getValidAccessToken(pilot)).thenReturn("token");

            scheduler("").syncContactsAndMail();

            // Beide Quellen im selben Lauf: sie brauchen dasselbe Token, und ein
            // zweiter Lauf haette nur einen zweiten SSO-Rundlauf gekostet.
            verify(contactSync).sync(pilot, "token");
            verify(mailSync).sync(pilot, "token");
        }

        @Test
        @DisplayName("ueberspringt Charaktere ohne Token und mit dauerhaft ungueltigem Token")
        void skipsCharactersWithoutUsableToken() {
            Character ohneToken = character(1L, "Ohne");
            Character kaputt = mitToken(2L, "Kaputt");
            kaputt.setTokenInvalidSince(Instant.now());
            Character gut = mitToken(3L, "Gut");
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(ohneToken, kaputt, gut));
            when(authService.getValidAccessToken(gut)).thenReturn("token");

            scheduler("").syncContactsAndMail();

            // Sie zu fragen kostet einen SSO-Rundlauf und endet sicher im selben
            // Fehlschlag, den der Vermerk bereits festhaelt.
            verify(contactSync, never()).sync(eq(ohneToken), any());
            verify(contactSync, never()).sync(eq(kaputt), any());
            verify(contactSync).sync(gut, "token");
        }

        @Test
        @DisplayName("laesst einen Fehler bei einem Charakter die uebrigen nicht aufhalten")
        void oneCharacterFailureDoesNotStopTheRest() {
            Character erster = mitToken(1L, "A");
            Character zweiter = mitToken(2L, "B");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(erster, zweiter));
            when(authService.getValidAccessToken(any())).thenReturn("token");
            doThrow(httpError(500)).when(contactSync).sync(eq(erster), any());

            scheduler("").syncContactsAndMail();

            verify(contactSync).sync(zweiter, "token");
        }

        @Test
        @DisplayName("holt gar nichts, wenn kein Token zu bekommen ist")
        void doesNothingWithoutAToken() {
            Character pilot = mitToken(1L, "A");
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(pilot));
            when(authService.getValidAccessToken(pilot)).thenReturn(null);

            scheduler("").syncContactsAndMail();

            verify(contactSync, never()).sync(any(), any());
            verify(mailSync, never()).sync(any(), any());
        }
    }

    /**
     * Der Loeschlauf. Er ist der Teil, der aus einer Aufbewahrungsfrist mehr
     * macht als einen Satz im Javadoc.
     */
    @Nested
    @DisplayName("Loeschlauf der Aufbewahrungsfristen")
    class AltSourceRetention {

        @Mock private AltSourceRetentionService retentionService;

        @Test
        @DisplayName("raeumt beide anwachsenden Tabellen auf")
        void purgesBothGrowingTables() {
            new AltSourceRetentionScheduler(retentionService).purgeExpiredRecords();

            verify(retentionService).purgePresence();
            verify(retentionService).purgeIskTransfers();
        }

        @Test
        @DisplayName("raeumt die zweite Tabelle auch dann auf, wenn es bei der ersten hakte")
        void purgesTheSecondEvenIfTheFirstFailed() {
            doThrow(new RuntimeException("kaputt")).when(retentionService).purgePresence();

            new AltSourceRetentionScheduler(retentionService).purgeExpiredRecords();

            // Ohne diese Zeile wuerde ein Fehler in der einen Tabelle die Frist
            // der anderen stillschweigend aussetzen - und niemand saehe es, weil
            // ein Loeschlauf, der nichts loescht, genauso aussieht wie einer,
            // der nichts zu loeschen fand.
            verify(retentionService).purgeIskTransfers();
        }
    }

    /**
     * Der Zeitgeber der Jita-Preise. Frueher gab es hier drei - einen fuer die
     * Asset-Preise, einen fuer die Industriepreise, einen fuer die
     * Mining-Steuersaetze, jeder mit eigenem Weg ans Netz. Seit der Umstellung
     * auf den Regionsabzug ist es einer: 411 Seiten, die dreimal zu holen
     * niemandem genuetzt haette.
     */
    @Nested
    @DisplayName("Jita-Preise")
    class MarketPrices {

        @Mock private MarketSnapshotService snapshotService;
        @Mock private AssetPriceScheduler assetPrices;
        @Mock private IndustrySyncService industrySync;
        @Mock private MiningPriceService miningPrices;

        private MarketPriceScheduler scheduler() {
            return new MarketPriceScheduler(snapshotService, assetPrices, industrySync, miningPrices);
        }

        private MarketSnapshot abzug() {
            return new MarketSnapshot(Map.of(34L, new StationPrice(3.77, 3.85)),
                    60_003_760L, Instant.now());
        }

        @Test
        @DisplayName("stoesst den Preisabgleich an")
        void triggersPriceRefresh() {
            MarketSnapshot abzug = abzug();
            when(snapshotService.pull()).thenReturn(abzug);

            scheduler().refreshMarketPrices();

            // Ein Abzug, drei Verbraucher - und alle bekommen DENSELBEN. Holte
            // sich hier noch jemand seine Preise selbst, waeren es 1.233 statt
            // 411 Seiten je Stunde.
            verify(snapshotService, times(1)).pull();
            verify(assetPrices).updateAssetPrices(abzug);
            verify(industrySync).syncIndustryPrices(abzug);
            verify(miningPrices).refreshJitaPrices(abzug);
        }

        @Test
        @DisplayName("faengt einen Fehlschlag ab, damit der Zeitgeber weiterlaeuft")
        void survivesFailure() {
            when(snapshotService.pull()).thenThrow(new RuntimeException("Markt weg"));

            scheduler().refreshMarketPrices();

            verify(snapshotService).pull();
        }

        @Test
        @DisplayName("schreibt nichts, wenn der Abzug abgebrochen ist")
        void abgebrochenerAbzugSchreibtNichts() {
            when(snapshotService.pull())
                    .thenThrow(new MarketSnapshotUnavailableException("Seite 250 von 411 nicht abrufbar"));

            scheduler().refreshMarketPrices();

            // Ohne diese Zeile schriebe ein halber Markt die alten Preise
            // ueber: die fehlenden Seiten saehen aus wie Typen ohne Angebot.
            // Ein halber Markt ist schlimmer als ein alter.
            verify(assetPrices, never()).updateAssetPrices(any());
            verify(industrySync, never()).syncIndustryPrices(any());
            verify(miningPrices, never()).refreshJitaPrices(any());
        }
    }
}

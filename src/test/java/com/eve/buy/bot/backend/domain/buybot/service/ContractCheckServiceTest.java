package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import com.eve.buy.bot.backend.domain.buybot.entity.ContractCheck;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.ContractCheckRepository;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests der Vertragsprüfung.
 *
 * <p>Diese Prüfung entscheidet, ob ein Ankauf angenommen oder abgelehnt wird. Die Tests
 * halten fest, welcher Befund welchen Fehler auslöst und dass eine gescheiterte Meldung
 * beim nächsten Lauf nachgeholt wird.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContractCheckService")
class ContractCheckServiceTest {

    private static final long CHECK_CHARACTER = 2118431553L;
    private static final long ISSUER = 90000001L;
    private static final long STATION = 60003760L;
    private static final long CONTRACT = 234567890L;
    private static final long TRITANIUM = 34L;

    @Mock private BuybackConfigRepository configRepo;
    @Mock private BuybackLocationRepository locationRepo;
    @Mock private ContractCheckRepository checkRepo;
    @Mock private BuybackCalculationService calculationService;
    @Mock private CharacterRepository characterRepo;
    @Mock private AuthService authService;
    @Mock private EsiService esiService;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;

    private ContractCheckService service;
    private BuybackConfig config;

    @BeforeEach
    void setUp() {
        service = new ContractCheckService(configRepo, locationRepo, checkRepo, calculationService,
                characterRepo, authService, esiService, notificationService, auditService);

        config = new BuybackConfig();
        config.setContractCheckEnabled(true);
        config.setContractCheckCharacterId(CHECK_CHARACTER);
        config.setPriceTolerancePercent(1.0);
        config.setNotifyTarget("DISCORD");
        config.setDiscordWebhookUrl("https://discord.example/webhook");
        config.setNotifyOnOk(true);

        Character checkCharacter = new Character();
        checkCharacter.setId(CHECK_CHARACTER);
        checkCharacter.setName("Prüfer");

        Character issuer = new Character();
        issuer.setId(ISSUER);
        issuer.setName("Verkäufer");

        BuybackLocation location = new BuybackLocation();
        location.setId(1L);
        location.setName("Teststation");
        location.setStationId(STATION);

        lenient().when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        lenient().when(characterRepo.findById(CHECK_CHARACTER)).thenReturn(Optional.of(checkCharacter));
        lenient().when(characterRepo.findById(ISSUER)).thenReturn(Optional.of(issuer));
        lenient().when(locationRepo.findAll()).thenReturn(List.of(location));
        lenient().when(authService.getValidAccessToken(any())).thenReturn("token");
        lenient().when(checkRepo.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(checkRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(notificationService.sendDiscord(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(NotificationService.NotifyResult.ok());
    }

    @Test
    @DisplayName("prüft nicht, solange die Automatik ausgeschaltet ist")
    void doesNothingWhileDisabled() {
        config.setContractCheckEnabled(false);

        ContractCheckService.RunResult result = service.run(false);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("deaktiviert");
        verify(esiService, never()).getAllCharacterContracts(anyLong(), anyString());
    }

    @Test
    @DisplayName("meldet einen fehlenden Prüf-Charakter statt stillschweigend nichts zu tun")
    void reportsMissingCheckCharacter() {
        config.setContractCheckCharacterId(null);

        ContractCheckService.RunResult result = service.run(true);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Prüf-Charakter");
    }

    @Test
    @DisplayName("nimmt einen passenden Vertrag ohne Beanstandung an")
    void acceptsMatchingContract() {
        givenContract(STATION, 1000.0);
        givenPricedItems(pricedOk(1000.0));

        ContractCheckService.RunResult result = service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("OK");
        assertThat(saved.getFindingCodes()).isEmpty();
        assertThat(result.notified()).isEqualTo(1);
    }

    @Test
    @DisplayName("lehnt einen Vertrag an einem nicht konfigurierten Ort ab")
    void rejectsContractAtWrongLocation() {
        givenContract(99999999L, 1000.0);
        givenPricedItems(pricedOk(1000.0));
        lenient().when(esiService.getStation(anyLong())).thenReturn(null);

        service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("REJECT");
        assertThat(saved.getFindingCodes()).contains(ContractCheckService.F_WRONG_LOCATION);
    }

    @Test
    @DisplayName("lehnt ab, wenn der geforderte Preis über der Toleranz liegt")
    void rejectsPriceAboveTolerance() {
        givenContract(STATION, 1100.0); // 10 % über dem berechneten Preis
        givenPricedItems(pricedOk(1000.0));

        service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("REJECT");
        assertThat(saved.getFindingCodes()).contains(ContractCheckService.F_PRICE_TOO_HIGH);
        assertThat(saved.getDeviationPercent()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("akzeptiert eine Abweichung innerhalb der Toleranz")
    void acceptsPriceWithinTolerance() {
        givenContract(STATION, 1005.0); // 0,5 % bei 1 % Toleranz
        givenPricedItems(pricedOk(1000.0));

        service.run(true);

        assertThat(capturedCheck().getVerdict()).isEqualTo("OK");
    }

    @Test
    @DisplayName("meldet einen zu niedrigen Preis als Hinweis, nicht als Ablehnung")
    void flagsPriceBelowToleranceAsWarning() {
        givenContract(STATION, 900.0);
        givenPricedItems(pricedOk(1000.0));

        service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("WARN");
        assertThat(saved.getFindingCodes()).contains(ContractCheckService.F_PRICE_TOO_LOW);
    }

    @Test
    @DisplayName("lehnt einen Vertrag mit gesperrten Items ab")
    void rejectsContractWithBlockedItems() {
        givenContract(STATION, 1000.0);
        ParsedItemDto blocked = new ParsedItemDto();
        blocked.setRawName("Veldspar");
        blocked.setStatusCode(BuybackCalculationService.STATUS_BLOCKED);
        blocked.setTotalPrice(0.0);
        givenPricedItems(pricedOk(1000.0), blocked);

        service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("REJECT");
        assertThat(saved.getFindingCodes()).contains(ContractCheckService.F_BLOCKED_ITEMS);
        assertThat(saved.getFindings()).contains("Veldspar");
    }

    @Test
    @DisplayName("lehnt ab, wenn der Vertrag Items von uns zurückfordert")
    void rejectsContractRequestingItemsBack() {
        givenContract(STATION, 1000.0);
        when(esiService.getContractItems(anyLong(), anyLong(), anyString())).thenReturn(List.of(
                new EsiService.EsiContractItemResponse(1L, TRITANIUM, 100L, true, false, null),
                new EsiService.EsiContractItemResponse(2L, 35L, 5L, false, false, null)));
        givenPricedItems(pricedOk(1000.0));

        service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("REJECT");
        assertThat(saved.getFindingCodes()).contains(ContractCheckService.F_REQUESTED_ITEMS);
    }

    @Test
    @DisplayName("lehnt alles ab, was kein Item-Exchange ist")
    void rejectsNonItemExchangeContracts() {
        givenContract(STATION, 1000.0, "auction");
        givenPricedItems(pricedOk(1000.0));

        service.run(true);

        ContractCheck saved = capturedCheck();
        assertThat(saved.getVerdict()).isEqualTo("REJECT");
        assertThat(saved.getFindingCodes()).contains(ContractCheckService.F_WRONG_TYPE);
    }

    @Test
    @DisplayName("meldet einen bereits gemeldeten Vertrag kein zweites Mal")
    void doesNotNotifyTwice() {
        givenContract(STATION, 1000.0);
        ContractCheck existing = new ContractCheck();
        existing.setContractId(CONTRACT);
        existing.setVerdict("REJECT");
        existing.setNotified(true);
        when(checkRepo.findById(CONTRACT)).thenReturn(Optional.of(existing));

        ContractCheckService.RunResult result = service.run(true);

        assertThat(result.checked()).isZero();
        assertThat(result.notified()).isZero();
        verify(notificationService, never()).sendDiscord(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("holt eine gescheiterte Meldung beim nächsten Lauf nach")
    void retriesFailedNotification() {
        givenContract(STATION, 1000.0);
        ContractCheck existing = new ContractCheck();
        existing.setContractId(CONTRACT);
        existing.setVerdict("REJECT");
        existing.setNotified(false);
        existing.setIssuerName("Verkäufer");
        existing.setNotifyError("ESI war nicht erreichbar");
        when(checkRepo.findById(CONTRACT)).thenReturn(Optional.of(existing));

        ContractCheckService.RunResult result = service.run(true);

        assertThat(result.notified()).isEqualTo(1);
        assertThat(existing.getNotified()).isTrue();
        assertThat(existing.getNotifyError()).isNull();
        assertThat(existing.getNotifyAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("hält den Grund fest, wenn die Meldung erneut scheitert")
    void keepsReasonWhenNotificationFailsAgain() {
        givenContract(STATION, 1000.0);
        givenPricedItems(pricedOk(1000.0));
        when(notificationService.sendDiscord(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(NotificationService.NotifyResult.fail("HTTP 404"));

        ContractCheckService.RunResult result = service.run(true);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("HTTP 404");
        assertThat(capturedCheck().getNotifyError()).contains("HTTP 404");
    }

    @Test
    @DisplayName("meldet fehlerfreie Verträge nicht, wenn das abgeschaltet ist")
    void skipsNotificationForCleanContractsWhenDisabled() {
        config.setNotifyOnOk(false);
        givenContract(STATION, 1000.0);
        givenPricedItems(pricedOk(1000.0));

        ContractCheckService.RunResult result = service.run(true);

        assertThat(capturedCheck().getVerdict()).isEqualTo("OK");
        assertThat(result.notified()).isZero();
        verify(notificationService, never()).sendDiscord(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ==========================================
    // Hilfsmittel
    // ==========================================

    private void givenContract(long startLocationId, double price) {
        givenContract(startLocationId, price, "item_exchange");
    }

    private void givenContract(long startLocationId, double price, String type) {
        EsiService.EsiContractResponse contract = new EsiService.EsiContractResponse(
                CONTRACT, ISSUER, 98000001L, CHECK_CHARACTER, null,
                "personal", "outstanding", type, "Ankauf",
                price, 0.0, 0.0, 0.0, 100.0,
                0, startLocationId, null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(3, ChronoUnit.DAYS),
                false);

        when(esiService.getAllCharacterContracts(CHECK_CHARACTER, "token")).thenReturn(List.of(contract));
        lenient().when(esiService.getContractItems(anyLong(), anyLong(), anyString())).thenReturn(List.of(
                new EsiService.EsiContractItemResponse(1L, TRITANIUM, 100L, true, false, null)));
    }

    private void givenPricedItems(ParsedItemDto... items) {
        when(calculationService.calculateForTypeIds(any(), anyLong()))
                .thenReturn(new java.util.ArrayList<>(List.of(items)));
    }

    private ParsedItemDto pricedOk(double totalPrice) {
        ParsedItemDto item = new ParsedItemDto();
        item.setTypeId(TRITANIUM);
        item.setRawName("Tritanium");
        item.setVolumeEach(0.01);
        item.setStatusCode(BuybackCalculationService.STATUS_OK);
        item.setTotalPrice(totalPrice);
        item.addQuantity(100);
        return item;
    }

    private ContractCheck capturedCheck() {
        ArgumentCaptor<ContractCheck> captor = ArgumentCaptor.forClass(ContractCheck.class);
        verify(checkRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    /** Stellt sicher, dass der Test die Preis-Engine mit den Vertragspositionen füttert. */
    @Test
    @DisplayName("übergibt die Vertragspositionen an dieselbe Preis-Engine wie die Website")
    void usesSamePricingEngineAsWebsite() {
        givenContract(STATION, 1000.0);
        givenPricedItems(pricedOk(1000.0));

        service.run(true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(calculationService).calculateForTypeIds(captor.capture(), anyLong());
        assertThat(captor.getValue()).containsEntry(TRITANIUM, 100L);
    }
}

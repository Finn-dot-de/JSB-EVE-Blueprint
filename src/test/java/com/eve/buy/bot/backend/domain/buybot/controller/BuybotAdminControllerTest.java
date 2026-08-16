package com.eve.buy.bot.backend.domain.buybot.controller;

import com.eve.buy.bot.backend.audit.AuditService;
import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.buybot.dto.ReprocessMaterialProjection;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackTypeRule;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackCategoryRuleRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackTypeRuleRepository;
import com.eve.buy.bot.backend.domain.buybot.service.ContractCheckService;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.domain.eve.entity.InvType;
import com.eve.buy.bot.backend.domain.eve.repository.InvCategoryRepository;
import com.eve.buy.bot.backend.domain.eve.repository.InvTypeRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests der Admin-Schnittstelle.
 *
 * <p>Schwerpunkt ist die Rueckmeldung, ob ein Item ueberhaupt verwertbar ist. Ohne die
 * wundert sich ein Admin, warum das gesetzte Reprocessing-Haekchen bei Mineralien den Preis
 * nicht aendert.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BuybotAdminController")
class BuybotAdminControllerTest {

    private static final long TRITANIUM = 34L;
    private static final long VELDSPAR = 1230L;

    @Mock private BuybackConfigRepository configRepo;
    @Mock private BuybackLocationRepository locationRepo;
    @Mock private BuybackCategoryRuleRepository categoryRuleRepo;
    @Mock private BuybackTypeRuleRepository typeRuleRepo;
    @Mock private InvTypeRepository invTypeRepo;
    @Mock private InvCategoryRepository invCategoryRepo;
    @Mock private AuthService authService;
    @Mock private CharacterRepository characterRepo;
    @Mock private EsiService esiService;
    @Mock private ContractCheckService contractCheckService;
    @Mock private AuditService auditService;

    private BuybotAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new BuybotAdminController(configRepo, locationRepo, categoryRuleRepo, typeRuleRepo,
                invTypeRepo, invCategoryRepo, authService, characterRepo, esiService,
                contractCheckService, auditService);
    }

    /**
     * Legt eine Einzelitem-Regel an.
     *
     * @param typeId    Type-ID
     * @param reprocess ob das Reprocessing-Häkchen gesetzt ist
     * @return die Regel
     */
    private BuybackTypeRule regel(long typeId, boolean reprocess) {
        BuybackTypeRule rule = new BuybackTypeRule();
        rule.setTypeId(typeId);
        rule.setModifier(90.0);
        rule.setIsBlacklisted(false);
        rule.setUseReprocessedValue(reprocess);
        return rule;
    }

    /**
     * Hinterlegt einen Itemnamen in der Statikdatenbank.
     *
     * @param typeId Type-ID
     * @param name   Anzeigename
     */
    private void bekannterName(long typeId, String name) {
        InvType type = new InvType();
        type.setTypeId(typeId);
        type.setTypeName(name);
        lenient().when(invTypeRepo.findById(typeId)).thenReturn(Optional.of(type));
    }

    @Test
    @DisplayName("markiert ein Endprodukt als nicht verwertbar")
    void marksEndProductAsNotReprocessable() {
        when(typeRuleRepo.findAll()).thenReturn(List.of(regel(TRITANIUM, true), regel(VELDSPAR, true)));
        bekannterName(TRITANIUM, "Tritanium");
        bekannterName(VELDSPAR, "Veldspar");

        // Nur Veldspar liefert eine Ausbeute - Tritanium ist selbst das Endprodukt
        when(invTypeRepo.findReprocessMaterials(Set.of(TRITANIUM, VELDSPAR)))
                .thenReturn(List.of(new Yield(VELDSPAR, TRITANIUM, 400L, 100)));

        List<BuybotAdminController.TypeRuleDto> rules = controller.getTypeRules().getBody();

        assertThat(rules).isNotNull();
        assertThat(rules).anySatisfy(rule -> {
            assertThat(rule.typeName()).isEqualTo("Veldspar");
            assertThat(rule.reprocessable()).isTrue();
        });
        assertThat(rules).anySatisfy(rule -> {
            assertThat(rule.typeName()).isEqualTo("Tritanium");
            assertThat(rule.reprocessable()).isFalse();
        });
    }

    @Test
    @DisplayName("fragt die Ausbeute nicht ab, wenn es keine Regeln gibt")
    void doesNotQueryYieldsWithoutRules() {
        when(typeRuleRepo.findAll()).thenReturn(List.of());

        List<BuybotAdminController.TypeRuleDto> rules = controller.getTypeRules().getBody();

        assertThat(rules).isEmpty();
        // Eine Abfrage mit leerer Liste wuerde als IN () auf der Datenbank scheitern
        verify(invTypeRepo, never()).findReprocessMaterials(any());
    }

    @Test
    @DisplayName("gibt auch ohne gesetztes Häkchen zurück, ob ein Item verwertbar wäre")
    void reportsReprocessabilityRegardlessOfFlag() {
        when(typeRuleRepo.findAll()).thenReturn(List.of(regel(VELDSPAR, false)));
        bekannterName(VELDSPAR, "Veldspar");
        when(invTypeRepo.findReprocessMaterials(Set.of(VELDSPAR)))
                .thenReturn(List.of(new Yield(VELDSPAR, TRITANIUM, 400L, 100)));

        List<BuybotAdminController.TypeRuleDto> rules = controller.getTypeRules().getBody();

        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().useReprocessedValue()).isFalse();
        assertThat(rules.getFirst().reprocessable()).isTrue();
    }

    @Test
    @DisplayName("nennt ein unbekanntes Item beim Namen, statt abzubrechen")
    void handlesUnknownItemName() {
        when(typeRuleRepo.findAll()).thenReturn(List.of(regel(999999L, false)));
        lenient().when(invTypeRepo.findById(anyLong())).thenReturn(Optional.empty());
        when(invTypeRepo.findReprocessMaterials(Set.of(999999L))).thenReturn(List.of());

        List<BuybotAdminController.TypeRuleDto> rules = controller.getTypeRules().getBody();

        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().typeName()).isEqualTo("Unknown Item");
        assertThat(rules.getFirst().reprocessable()).isFalse();
    }

    /**
     * Testdoppel für eine Zeile der Reprocessing-Ausbeute.
     *
     * @param typeId         das zu verwertende Item
     * @param materialTypeId das gewonnene Material
     * @param quantity       Menge je Portion
     * @param portionSize    Portionsgröße
     */
    private record Yield(Long typeId, Long materialTypeId, Long quantity, Integer portionSize)
            implements ReprocessMaterialProjection {

        @Override
        public Long getTypeId() {
            return typeId;
        }

        @Override
        public Long getMaterialTypeId() {
            return materialTypeId;
        }

        @Override
        public Long getQuantity() {
            return quantity;
        }

        @Override
        public Integer getPortionSize() {
            return portionSize;
        }
    }
}

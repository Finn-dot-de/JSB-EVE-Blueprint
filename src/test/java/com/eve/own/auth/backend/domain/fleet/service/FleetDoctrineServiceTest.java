package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Pflege der Doktrin-Fittings")
class FleetDoctrineServiceTest {

    private static final Long CREATOR_ID = 1000L;

    @Mock private FleetDoctrineRepository doctrineRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private InvTypeRepository invTypeRepo;

    private FleetDoctrineService service;

    @BeforeEach
    void setUp() {
        service = new FleetDoctrineService(doctrineRepo, characterRepo, invTypeRepo);

        Character creator = new Character();
        creator.setId(CREATOR_ID);
        creator.setName("Flottenchef");
        when(characterRepo.findById(CREATOR_ID)).thenReturn(Optional.of(creator));
        when(doctrineRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(invTypeRepo.findByTypeNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private static FleetDoctrineService.DoctrineCommand command(String doctrineName, String shipType) {
        return new FleetDoctrineService.DoctrineCommand(doctrineName, shipType, "Standard-Fit", "[Nestor, Fit]");
    }

    @Test
    @DisplayName("legt ein Fitting mit Ersteller und Zeitpunkt an")
    void createsDoctrine() {
        FleetDoctrine created = service.create(CREATOR_ID, command("Armor-Doktrin", "Nestor"));

        assertThat(created.getDoctrineName()).isEqualTo("Armor-Doktrin");
        assertThat(created.getShipType()).isEqualTo("Nestor");
        assertThat(created.getCreatedBy()).isEqualTo("Flottenchef");
        assertThat(created.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("loest den Schiffstyp gegen die SDE auf")
    void resolvesShipTypeId() {
        InvType nestor = new InvType();
        nestor.setTypeId(33472L);
        when(invTypeRepo.findByTypeNameIgnoreCase("Nestor")).thenReturn(Optional.of(nestor));

        assertThat(service.create(CREATOR_ID, command("Armor", "Nestor")).getShipTypeId())
                .isEqualTo(33472L);
    }

    @Test
    @DisplayName("sammelt Fittings ohne Doktrin unter einem Auffangnamen")
    void groupsUnnamedDoctrines() {
        assertThat(service.create(CREATOR_ID, command(null, "Nestor")).getDoctrineName())
                .isEqualTo("Ungruppiert");
        assertThat(service.create(CREATOR_ID, command("   ", "Nestor")).getDoctrineName())
                .isEqualTo("Ungruppiert");
    }

    @Test
    @DisplayName("schreibt beim Aendern auch die Typ-ID nach")
    void updatesShipTypeIdOnChange() {
        FleetDoctrine existing = new FleetDoctrine();
        existing.setId(5L);
        existing.setShipType("Nestor");
        existing.setShipTypeId(33472L);
        when(doctrineRepo.findById(5L)).thenReturn(Optional.of(existing));

        InvType guardian = new InvType();
        guardian.setTypeId(11987L);
        when(invTypeRepo.findByTypeNameIgnoreCase("Guardian")).thenReturn(Optional.of(guardian));

        FleetDoctrine updated = service.update(5L, command("Armor", "Guardian"));

        assertThat(updated.getShipType()).isEqualTo("Guardian");
        assertThat(updated.getShipTypeId()).isEqualTo(11987L);
    }

    @Test
    @DisplayName("kommt mit einem Fitting ohne Schiffsnamen zurecht")
    void toleratesMissingShipType() {
        assertThat(service.create(CREATOR_ID, command("Armor", null)).getShipTypeId()).isNull();
    }

    @Test
    @DisplayName("weist ein unbekanntes Fitting beim Aendern ab")
    void rejectsUnknownDoctrine() {
        when(doctrineRepo.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(404L, command("Armor", "Nestor")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("weist einen unbekannten Ersteller ab")
    void rejectsUnknownCreator() {
        when(characterRepo.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(404L, command("Armor", "Nestor")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reicht Lesen und Loeschen durch")
    void delegatesReadAndDelete() {
        FleetDoctrine doctrine = new FleetDoctrine();
        when(doctrineRepo.findAll()).thenReturn(List.of(doctrine));

        assertThat(service.findAll()).containsExactly(doctrine);

        service.delete(5L);
        verify(doctrineRepo).deleteById(5L);
    }
}

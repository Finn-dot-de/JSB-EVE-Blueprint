package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.IndustryStructure;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.SystemInfo;
import com.eve.own.auth.backend.domain.industry.repository.IndustryStructureRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Die Bauortsuche und die daraus abgeleiteten Empfehlungen. */
class IndustryStructureServiceTest {

    private IndustryStructureRepository structureRepo;
    private IndustryQueryRepository queryRepo;
    private MyAssetService assetService;
    private IndustryStructureService service;

    @BeforeEach
    void setUp() {
        structureRepo = Mockito.mock(IndustryStructureRepository.class);
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        assetService = Mockito.mock(MyAssetService.class);
        service = new IndustryStructureService(structureRepo, queryRepo, assetService);
    }

    private static IndustryStructure struktur(String typName, boolean diensteBekannt) {
        IndustryStructure s = new IndustryStructure();
        s.setStructureId(1000L);
        s.setName("MA Werft");
        s.setSystemName("Jita");
        s.setSolarSystemId(30000142L);
        s.setTypeName(typName);
        s.setSource(diensteBekannt ? "CORP" : "PUBLIC");
        s.setServicesKnown(diensteBekannt);
        s.setManufacturingOnline(true);
        return s;
    }

    @Test
    @DisplayName("sagt bei einer Tatara, dass dort nicht gefertigt werden kann")
    void tataraKannNichtFertigen() {
        when(structureRepo.search(any(), any())).thenReturn(List.of(struktur("Tatara", true)));

        List<IndustryDtos.LocationDto> treffer = service.search(1L, "tatara", 10);

        assertThat(treffer).singleElement().satisfies(t -> {
            // Der Punkt aus dem Wunsch: eine Tatara ist eine Refinery. Reaktionen
            // und Wiederaufbereitung ja, Fertigung nein.
            assertThat(t.hints()).anyMatch(h -> h.contains("Reaktionen"));
            assertThat(t.hints()).anyMatch(h -> h.contains("Fertigung geht hier NICHT"));
        });
    }

    @Test
    @DisplayName("nennt den Sotiyo als einzigen Ort für Titanen")
    void sotiyoFuerTitanen() {
        when(structureRepo.search(any(), any())).thenReturn(List.of(struktur("Sotiyo", true)));

        assertThat(service.search(1L, "sotiyo", 10)).singleElement()
                .satisfies(t -> assertThat(t.hints()).anyMatch(h -> h.contains("Titanen")));
    }

    @Test
    @DisplayName("gibt bei fremden Strukturen ehrlich zu, dass die Dienste unbekannt sind")
    void unbekannteDiensteWerdenNichtGeraten() {
        IndustryStructure fremd = struktur("Fortizar", false);
        when(structureRepo.search(any(), any())).thenReturn(List.of(fremd));

        List<IndustryDtos.LocationDto> treffer = service.search(1L, "fortizar", 10);

        assertThat(treffer).singleElement().satisfies(t -> {
            assertThat(t.servicesKnown()).isFalse();
            // Obwohl manufacturingOnline am Datensatz true steht: ohne bekannte
            // Dienste darf daraus keine Zusage werden.
            assertThat(t.manufacturing()).isFalse();
            assertThat(t.hints()).anyMatch(h -> h.contains("unbekannt"));
        });
    }

    @Test
    @DisplayName("liefert ohne Suchbegriff nichts")
    void leereSucheLiefertNichts() {
        assertThat(service.search(1L, null, 10)).isEmpty();
        assertThat(service.search(1L, "   ", 10)).isEmpty();
    }

    @Test
    @DisplayName("erkennt Reaktionsdienste trotz ihrer verschiedenen Namen")
    void erkenntReaktionsdienste() {
        when(queryRepo.typeName(35836L)).thenReturn(Optional.of("Tatara"));
        when(queryRepo.systemInfo(anyLong()))
                .thenReturn(Optional.of(new SystemInfo(30000142L, "Jita", 0.946, "The Forge")));

        // CCP benennt die Reaktionsdienste nach Art. Ein Vergleich auf Gleichheit
        // mit "reactions" fände keinen einzigen davon.
        service.upsertCorpStructure(1000L, 35836L, 30000142L, 98000001L,
                List.of("Composite Reactions", "Reprocessing"), null);

        ArgumentCaptor<IndustryStructure> captor = ArgumentCaptor.captor();
        verify(structureRepo).save(captor.capture());
        IndustryStructure gespeichert = captor.getValue();

        assertThat(gespeichert.getReactionsOnline()).isTrue();
        assertThat(gespeichert.getReprocessingOnline()).isTrue();
        assertThat(gespeichert.getManufacturingOnline()).isFalse();
        assertThat(gespeichert.getServicesKnown()).isTrue();
        assertThat(gespeichert.getTypeName()).isEqualTo("Tatara");
        assertThat(gespeichert.getSystemName()).isEqualTo("Jita");
    }

    @Test
    @DisplayName("wertet eine Struktur ohne Dienste nicht als fertigungsfähig")
    void ohneDiensteKeineFertigung() {
        IndustryStructure fremd = struktur("Astrahus", false);

        assertThat(IndustryStructureService.canManufacture(fremd)).isFalse();

        IndustryStructure eigen = struktur("Raitaru", true);
        assertThat(IndustryStructureService.canManufacture(eigen)).isTrue();
    }
}

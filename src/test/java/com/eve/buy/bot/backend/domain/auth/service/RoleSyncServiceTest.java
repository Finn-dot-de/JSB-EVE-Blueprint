package com.eve.buy.bot.backend.domain.auth.service;

import com.eve.buy.bot.backend.domain.auth.entity.SystemRole;
import com.eve.buy.bot.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.buy.bot.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.entity.Corporation;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests der Rollenvergabe.
 *
 * <p>Hier entscheidet sich, wer die Ankaufspreise aendern darf. Zwei Fehler waeren
 * gleichermassen schlimm: dass niemand an das Admin-Panel kommt und die Anlage nicht
 * einrichtbar ist, oder dass jemand hineinkommt, der es nicht soll.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleSyncService")
class RoleSyncServiceTest {

    private static final long ALLOWED_CORP = 98378388L;

    @Mock private EsiService esiService;
    @Mock private CharacterRepository characterRepo;
    @Mock private TitleRoleMappingRepository titleRepo;
    @Mock private SystemRoleRepository systemRoleRepo;

    private RoleSyncService service;

    @BeforeEach
    void setUp() {
        service = new RoleSyncService(esiService, characterRepo, titleRepo, systemRoleRepo);
        ReflectionTestUtils.setField(service, "allowedCorpId", ALLOWED_CORP);
        lenient().when(characterRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(systemRoleRepo.findByIsSpecialTrue()).thenReturn(List.of());
        lenient().when(esiService.getCharacterTitles(anyLong(), anyString(), any()))
                .thenReturn(new EsiService.EsiResponse<>(new EsiService.EsiTitleResponse[0], null));
    }

    /**
     * Setzt die Admin-Liste wie aus der Konfiguration und laesst sie einlesen.
     *
     * @param liste kommaseparierte Namen und IDs
     */
    private void gegebeneAdminListe(String liste) {
        ReflectionTestUtils.setField(service, "adminCharactersRaw", liste);
        service.parseAdminCharacters();
    }

    /**
     * Baut einen Charakter in der freigegebenen Corporation.
     *
     * @param id   Charakter-ID
     * @param name Anzeigename
     * @return der Charakter
     */
    private Character charakter(long id, String name) {
        Corporation corp = new Corporation();
        corp.setId(ALLOWED_CORP);
        corp.setName("Testcorp");

        Character character = new Character();
        character.setId(id);
        character.setName(name);
        character.setCorporation(corp);
        character.setRoles(new HashSet<>());
        return character;
    }

    @Test
    @DisplayName("macht den konfigurierten Charakter zum Administrator")
    void grantsAdminToConfiguredName() {
        gegebeneAdminListe("Konsti Miner");

        Character gespeichert = service.syncRoles(charakter(90000001L, "Konsti Miner"), "token");

        assertThat(gespeichert.getRoles()).contains(RoleSyncService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("erkennt den Namen unabhaengig von Gross- und Kleinschreibung")
    void matchesNameCaseInsensitively() {
        gegebeneAdminListe("konsti miner");

        Character gespeichert = service.syncRoles(charakter(90000001L, "KONSTI MINER"), "token");

        assertThat(gespeichert.getRoles()).contains(RoleSyncService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("akzeptiert auch eine Charakter-ID in der Liste")
    void matchesCharacterId() {
        gegebeneAdminListe("90000001");

        Character gespeichert = service.syncRoles(charakter(90000001L, "Egal Wie"), "token");

        assertThat(gespeichert.getRoles()).contains(RoleSyncService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("versteht mehrere Eintraege mit Namen und IDs gemischt")
    void handlesMixedList() {
        gegebeneAdminListe(" Konsti Miner , 90000002 ,Zweiter Admin ");

        assertThat(service.isConfiguredAdmin(charakter(1L, "Konsti Miner"))).isTrue();
        assertThat(service.isConfiguredAdmin(charakter(90000002L, "Irgendwer"))).isTrue();
        assertThat(service.isConfiguredAdmin(charakter(2L, "Zweiter Admin"))).isTrue();
        assertThat(service.isConfiguredAdmin(charakter(3L, "Fremder"))).isFalse();
    }

    @Test
    @DisplayName("gibt niemandem die Admin-Rolle, der nicht in der Liste steht")
    void deniesAdminToEveryoneElse() {
        gegebeneAdminListe("Konsti Miner");

        Character gespeichert = service.syncRoles(charakter(90000009L, "Fremder Pilot"), "token");

        assertThat(gespeichert.getRoles())
                .contains(RoleSyncService.ROLE_USER, RoleSyncService.ROLE_MEMBER)
                .doesNotContain(RoleSyncService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("vergibt bei leerer Liste ueberhaupt keine Admin-Rolle")
    void grantsNoAdminWithoutConfiguration() {
        gegebeneAdminListe("");

        assertThat(service.isConfiguredAdmin(charakter(90000001L, "Konsti Miner"))).isFalse();
    }

    @Test
    @DisplayName("gibt Mitgliedsrolle nur Charakteren der freigegebenen Corporation")
    void grantsMemberOnlyForAllowedCorp() {
        gegebeneAdminListe("");

        Character fremd = charakter(90000003L, "Anderer");
        fremd.getCorporation().setId(11111111L);

        Character gespeichert = service.syncRoles(fremd, "token");

        assertThat(gespeichert.getRoles())
                .contains(RoleSyncService.ROLE_USER)
                .doesNotContain(RoleSyncService.ROLE_MEMBER);
    }

    @Test
    @DisplayName("behaelt eine von Hand vergebene Sonderrolle beim Neuberechnen")
    void keepsManuallyGrantedSpecialRole() {
        gegebeneAdminListe("");
        SystemRole besondere = new SystemRole();
        besondere.setRoleName(RoleSyncService.ROLE_ADMIN);
        besondere.setSpecial(true);
        when(systemRoleRepo.findByIsSpecialTrue()).thenReturn(List.of(besondere));

        Character vorhanden = charakter(90000004L, "Handverlesen");
        vorhanden.setRoles(new HashSet<>(Set.of(RoleSyncService.ROLE_ADMIN)));

        Character gespeichert = service.syncRoles(vorhanden, "token");

        assertThat(gespeichert.getRoles()).contains(RoleSyncService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("traegt die Admin-Rolle beim Start als besondere Rolle ein")
    void registersAdminRoleOnStartup() {
        when(systemRoleRepo.existsById(RoleSyncService.ROLE_ADMIN)).thenReturn(false);

        service.ensureAdminRoleIsKnown();

        verify(systemRoleRepo).save(org.mockito.ArgumentMatchers.argThat(rolle ->
                RoleSyncService.ROLE_ADMIN.equals(rolle.getRoleName()) && rolle.isSpecial()));
    }

    @Test
    @DisplayName("traegt sie nicht doppelt ein, wenn sie schon bekannt ist")
    void doesNotRegisterAdminRoleTwice() {
        when(systemRoleRepo.existsById(RoleSyncService.ROLE_ADMIN)).thenReturn(true);

        service.ensureAdminRoleIsKnown();

        verify(systemRoleRepo, never()).save(any());
    }

    @Test
    @DisplayName("leitet aus einem EVE-Titel eine Rolle ab")
    void derivesRoleFromCorpTitle() {
        gegebeneAdminListe("");
        when(esiService.getCharacterTitles(anyLong(), anyString(), any())).thenReturn(
                new EsiService.EsiResponse<>(new EsiService.EsiTitleResponse[]{
                        new EsiService.EsiTitleResponse(7L, "Fleet Commander")}, null));
        when(titleRepo.findByCorporationId(ALLOWED_CORP)).thenReturn(new java.util.ArrayList<>());

        Character gespeichert = service.syncRoles(charakter(90000005L, "Pilot"), "token");

        assertThat(gespeichert.getRoles()).contains("ROLE_FLEET_COMMANDER");
    }

    @Test
    @DisplayName("bricht nicht ab, wenn ESI die Titel nicht liefert")
    void survivesFailingTitleLookup() {
        gegebeneAdminListe("Konsti Miner");
        when(esiService.getCharacterTitles(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("ESI antwortet nicht"));

        Character gespeichert = service.syncRoles(charakter(90000001L, "Konsti Miner"), "token");

        assertThat(gespeichert.getRoles())
                .contains(RoleSyncService.ROLE_USER, RoleSyncService.ROLE_ADMIN);
    }
}

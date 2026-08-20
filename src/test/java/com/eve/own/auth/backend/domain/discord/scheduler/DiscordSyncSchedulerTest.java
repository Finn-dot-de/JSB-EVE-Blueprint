package com.eve.own.auth.backend.domain.discord.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import com.eve.own.auth.backend.domain.discord.service.DiscordSyncStand;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Der Zeitplan darf sich nicht selbst ueberholen.
 *
 * <p>Im Produktionslog folgte auf "Discord Role Sync abgeschlossen"
 * unmittelbar wieder "Starte Discord Role Sync..." - kein Abstand, keine
 * Pause. Zwei Ursachen lagen uebereinander: ein Intervall von drei Minuten
 * statt dreissig ({@code 1_800_00} - ein fehlender Unterstrich) und
 * {@code fixedRate}, das den Abstand von Start zu Start misst und einen zu
 * langen Lauf sofort nachholt.</p>
 */
class DiscordSyncSchedulerTest {

    private static final String KONTO = "777";
    private static final String ROLLE = "1000";

    private DiscordConnectionRepository connectionRepo;
    private CharacterRepository characterRepo;
    private DiscordRoleMappingRepository mappingRepo;
    private DiscordBotService bot;
    private DiscordSyncScheduler zeitplan;

    @BeforeEach
    void setUp() {
        connectionRepo = mock(DiscordConnectionRepository.class);
        characterRepo = mock(CharacterRepository.class);
        mappingRepo = mock(DiscordRoleMappingRepository.class);
        bot = mock(DiscordBotService.class);
        zeitplan = new DiscordSyncScheduler(connectionRepo, characterRepo, mappingRepo, bot,
                new DiscordSyncStand());
    }

    @Test
    @DisplayName("misst den Abstand vom Ende des letzten Laufs und betraegt dreissig Minuten")
    void abstandStattTaktschlag() throws NoSuchMethodException {
        // Die Zusicherung sitzt in einer Annotation, also wird sie dort
        // geprueft. Ohne diesen Test faellt beides stumm zurueck: fixedRate
        // holt einen zu langen Lauf sofort nach, und aus 1_800_000 werden
        // durch einen fehlenden Unterstrich wieder drei Minuten - beim Lesen
        // sieht man den Unterschied nicht.
        Method lauf = DiscordSyncScheduler.class.getMethod("syncDiscordRoles");
        Scheduled plan = lauf.getAnnotation(Scheduled.class);

        assertThat(plan).isNotNull();
        assertThat(plan.fixedRate()).as("fixedRate startet sofort nach - siehe Log").isEqualTo(-1L);
        assertThat(plan.fixedDelay()).isEqualTo(30L * 60L * 1000L);
    }

    @Test
    @DisplayName("laesst keinen zweiten Lauf beginnen, solange einer laeuft")
    void keineUeberlappung() {
        // Die Reihe hat acht Faeden. Zwei gleichzeitige Durchlaeufe
        // verdoppelten die Aufrufe genau an der Stelle, an der Discord ohnehin
        // schon bremst. Hier ausgeloest, indem der erste Lauf beim Laden der
        // Konten einen zweiten anstoesst.
        when(connectionRepo.findAll()).thenAnswer(aufruf -> {
            zeitplan.syncDiscordRoles();
            return List.of();
        });

        zeitplan.syncDiscordRoles();

        // Der geschachtelte Lauf ist gar nicht erst bis zum Laden gekommen.
        verify(connectionRepo, times(1)).findAll();
    }

    @Test
    @DisplayName("gibt den Riegel auch nach einem Fehler wieder frei")
    void riegelBleibtNichtHaengen() {
        // Ein Riegel, der nach einer Ausnahme haengen bleibt, legt den Abgleich
        // fuer immer still - und zwar lautlos, denn der Zeitplan ruft weiter
        // auf und kehrt sofort zurueck.
        // doThrow/doReturn statt when(...): Ein zweites when() auf denselben
        // Aufruf wuerde ihn ausfuehren - und damit die erste Ausnahme
        // ausloesen, mitten in der Testvorbereitung.
        doThrow(new IllegalStateException("Datenbank weg")).when(connectionRepo).findAll();

        try {
            zeitplan.syncDiscordRoles();
        } catch (RuntimeException erwartet) {
            // Der Fehler gehoert nach oben; hier zaehlt nur, was danach geht.
        }
        doReturn(List.of()).when(connectionRepo).findAll();
        zeitplan.syncDiscordRoles();

        verify(connectionRepo, times(2)).findAll();
    }

    @Test
    @DisplayName("gibt dem Bot den Anlass ZEITPLAN mit")
    void laufMeldetSeinenAnlass() {
        // Davon haengt die Daempfung ab: Eine mit 403 abgelehnte Rolle wird im
        // Zeitplan nicht bei jedem Lauf erneut versucht, beim Anstoss von Hand
        // dagegen sofort. Ohne den Anlass waere die Ruhezeit entweder ueberall
        // oder nirgends - und "ueberall" hiesse, dass der Knopf nach einer
        // behobenen Rangfolge nichts mehr bewirkt.
        DiscordConnection verbindung = new DiscordConnection();
        verbindung.setCharacterId(1L);
        verbindung.setDiscordUserId(KONTO);
        when(connectionRepo.findAll()).thenReturn(List.of(verbindung));

        DiscordRoleMapping zuordnung = new DiscordRoleMapping();
        zuordnung.setAuthRole("ROLE_MITGLIED");
        zuordnung.setDiscordRoleId(ROLLE);
        when(mappingRepo.findAll()).thenReturn(List.of(zuordnung));
        when(mappingRepo.findById("ROLE_MITGLIED")).thenReturn(Optional.of(zuordnung));

        Character tom = new Character();
        tom.setId(1L);
        tom.setName("Tom");
        tom.setRoles(Set.of("ROLE_MITGLIED"));
        when(characterRepo.findById(1L)).thenReturn(Optional.of(tom));

        zeitplan.syncDiscordRoles();

        verify(bot).syncManagedRoles(eq(KONTO), any(), any(), anyString(),
                eq(DiscordBotService.Anlass.ZEITPLAN));
    }
}

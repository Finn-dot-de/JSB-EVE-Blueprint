package com.eve.own.auth.backend.domain.auth.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.service.TokenHealthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Die Discord-Meldung ueber abgelaufene Anmeldungen.
 *
 * <p>Das Auth zeigt denselben Hinweis - aber nur dem, der es oeffnet. Wer sein
 * Konto laufen laesst, erfaehrt dort nie, dass seine Zahlen stehengeblieben
 * sind.</p>
 */
class TokenAlertSchedulerTest {

    private static final long MAIN = 100L;
    private static final long ALT = 200L;

    private TokenHealthService tokenHealth;
    private CharacterRepository characterRepo;
    private DiscordConnectionRepository discordRepo;
    private DiscordBotService discordBot;
    private TokenAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        tokenHealth = Mockito.mock(TokenHealthService.class);
        characterRepo = Mockito.mock(CharacterRepository.class);
        discordRepo = Mockito.mock(DiscordConnectionRepository.class);
        discordBot = Mockito.mock(DiscordBotService.class);
        scheduler = new TokenAlertScheduler(tokenHealth, characterRepo, discordRepo, discordBot);

        when(discordBot.sendDirectMessage(anyString(), anyString())).thenReturn(true);
    }

    private Character charakter(long id, String name, Long mainId, Instant benachrichtigt) {
        Character c = new Character();
        c.setId(id);
        c.setName(name);
        c.setMainCharacterId(mainId);
        c.setTokenInvalidSince(Instant.parse("2026-08-08T12:00:00Z"));
        c.setTokenInvalidNotifiedAt(benachrichtigt);
        return c;
    }

    private void mitDiscord(long characterId, String discordId) {
        DiscordConnection v = new DiscordConnection();
        v.setCharacterId(characterId);
        v.setDiscordUserId(discordId);
        when(discordRepo.findById(characterId)).thenReturn(Optional.of(v));
    }

    @Test
    @DisplayName("schickt die Nachricht an den Main und nennt den betroffenen Alt")
    void meldungGehtAnDenMainUndNenntDenAlt() {
        // Der betroffene Charakter ist oft ein Alt, der nie mit Discord
        // verknüpft wurde. Die Verknüpfung hängt am Hauptcharakter - dorthin
        // geht die Nachricht, und sie muss den Alt benennen, sonst weiß
        // niemand, welcher gemeint ist.
        when(tokenHealth.invalidTokens())
                .thenReturn(List.of(charakter(ALT, "Rat Izia", MAIN, null)));
        when(characterRepo.findById(MAIN))
                .thenReturn(Optional.of(charakter(MAIN, "Comander-Video", null, null)));
        mitDiscord(MAIN, "discord-123");

        scheduler.meldeAbgelaufeneAnmeldungen();

        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(discordBot).sendDirectMessage(eq("discord-123"), text.capture());
        org.assertj.core.api.Assertions.assertThat(text.getValue()).contains("Rat Izia");
    }

    @Test
    @DisplayName("meldet jeden Vorfall genau einmal")
    void keineWiederholung() {
        // Der Zeitplan läuft stündlich. Ohne diese Sperre bekäme der Spieler
        // stündlich dieselbe Nachricht und hätte den Bot nach einem halben Tag
        // stummgeschaltet.
        when(tokenHealth.invalidTokens()).thenReturn(
                List.of(charakter(ALT, "Rat Izia", MAIN, Instant.parse("2026-08-08T13:00:00Z"))));

        scheduler.meldeAbgelaufeneAnmeldungen();

        verify(discordBot, never()).sendDirectMessage(anyString(), anyString());
        verify(tokenHealth, never()).markNotified(any());
    }

    @Test
    @DisplayName("vermerkt auch dann, wenn kein Discord verknüpft ist")
    void ohneDiscordTrotzdemVermerken() {
        // Sonst löst derselbe Charakter stündlich einen erfolglosen Versuch aus.
        when(tokenHealth.invalidTokens())
                .thenReturn(List.of(charakter(ALT, "Rat Izia", MAIN, null)));
        when(discordRepo.findById(MAIN)).thenReturn(Optional.empty());

        scheduler.meldeAbgelaufeneAnmeldungen();

        verify(discordBot, never()).sendDirectMessage(anyString(), anyString());
        verify(tokenHealth).markNotified(ALT);
    }

    @Test
    @DisplayName("nennt den Main beim Namen, wenn er selbst betroffen ist")
    void mainSelbstBetroffen() {
        when(tokenHealth.invalidTokens())
                .thenReturn(List.of(charakter(MAIN, "Comander-Video", null, null)));
        when(characterRepo.findById(MAIN))
                .thenReturn(Optional.of(charakter(MAIN, "Comander-Video", null, null)));
        mitDiscord(MAIN, "discord-123");

        scheduler.meldeAbgelaufeneAnmeldungen();

        ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(discordBot).sendDirectMessage(eq("discord-123"), text.capture());
        org.assertj.core.api.Assertions.assertThat(text.getValue()).contains("Comander-Video");
    }
}

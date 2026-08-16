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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests des Meldewegs.
 *
 * <p>Der wichtigste Fall ist der stille Fehlschlag: schlägt eine Meldung fehl, muss der
 * Grund zurückkommen. Genau daran ist die Vertragsprüfung im Betrieb einmal gescheitert.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

    private static final long SENDER = 2118431553L;

    @Mock private EsiService esiService;
    @Mock private AuthService authService;
    @Mock private CharacterRepository characterRepo;
    @Mock private RestClient.Builder restClientBuilder;
    @Mock private RestClient restClient;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        // Der Webhook-Client wird hier nicht gebraucht; ein echter wuerde nur eine
        // Netzwerkverbindung aufbauen, die der Test gar nicht benutzt.
        lenient().when(restClientBuilder.build()).thenReturn(restClient);
        service = new NotificationService(restClientBuilder, esiService, authService,
                characterRepo, new ObjectMapper());

        Character sender = new Character();
        sender.setId(SENDER);
        sender.setName("Prüfer");
        lenient().when(characterRepo.findById(SENDER)).thenReturn(Optional.of(sender));
    }

    @Test
    @DisplayName("verschickt die Mail, wenn der Token den Mail-Scope enthält")
    void sendsMailWhenScopeIsPresent() {
        when(authService.getValidAccessToken(any())).thenReturn(tokenWithScopes(NotificationService.MAIL_SCOPE));

        NotificationService.NotifyResult result = service.sendEveMail(SENDER, null, "Betreff", "Text");

        assertThat(result.sent()).isTrue();
        assertThat(result.error()).isNull();
        verify(esiService).sendMail(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("nennt den fehlenden Scope, statt ESI erst antworten zu lassen")
    void namesMissingScopeInsteadOfFailingLate() {
        when(authService.getValidAccessToken(any())).thenReturn(tokenWithScopes("publicData"));

        NotificationService.NotifyResult result = service.sendEveMail(SENDER, null, "Betreff", "Text");

        assertThat(result.sent()).isFalse();
        assertThat(result.error()).contains(NotificationService.MAIL_SCOPE).contains("Prüfer");
        verify(esiService, never()).sendMail(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("schickt die Mail an den Prüf-Charakter, wenn kein Empfänger gesetzt ist")
    void fallsBackToSenderAsRecipient() {
        when(authService.getValidAccessToken(any())).thenReturn(tokenWithScopes(NotificationService.MAIL_SCOPE));

        service.sendEveMail(SENDER, 0L, "Betreff", "Text");

        org.mockito.ArgumentCaptor<EsiService.EsiMailRequest> mail =
                org.mockito.ArgumentCaptor.forClass(EsiService.EsiMailRequest.class);
        verify(esiService).sendMail(anyLong(), anyString(), mail.capture());
        assertThat(mail.getValue().recipients().getFirst().recipient_id()).isEqualTo(SENDER);
    }

    @Test
    @DisplayName("wandelt Zeilenumbrüche in das Format der EVE-Mail")
    void convertsLineBreaksForEveMail() {
        when(authService.getValidAccessToken(any())).thenReturn(tokenWithScopes(NotificationService.MAIL_SCOPE));

        service.sendEveMail(SENDER, null, "Betreff", "Zeile 1\nZeile 2");

        org.mockito.ArgumentCaptor<EsiService.EsiMailRequest> mail =
                org.mockito.ArgumentCaptor.forClass(EsiService.EsiMailRequest.class);
        verify(esiService).sendMail(anyLong(), anyString(), mail.capture());
        assertThat(mail.getValue().body()).isEqualTo("Zeile 1<br>Zeile 2");
    }

    @Test
    @DisplayName("gibt den Grund zurück, wenn ESI die Mail ablehnt")
    void returnsReasonWhenEsiRejectsTheMail() {
        when(authService.getValidAccessToken(any())).thenReturn(tokenWithScopes(NotificationService.MAIL_SCOPE));
        doThrow(new IllegalStateException("ESI sagt nein")).when(esiService).sendMail(anyLong(), anyString(), any());

        NotificationService.NotifyResult result = service.sendEveMail(SENDER, null, "Betreff", "Text");

        assertThat(result.sent()).isFalse();
        assertThat(result.error()).contains("ESI sagt nein");
    }

    @Test
    @DisplayName("meldet einen nicht verknüpften Absender als Konfigurationsfehler")
    void reportsUnknownSender() {
        when(characterRepo.findById(999L)).thenReturn(Optional.empty());

        NotificationService.NotifyResult result = service.sendEveMail(999L, null, "Betreff", "Text");

        assertThat(result.sent()).isFalse();
        assertThat(result.error()).contains("nicht mit dem Auth verknüpft");
    }

    @Test
    @DisplayName("meldet eine fehlende Webhook-Adresse, statt sie stillschweigend zu verwerfen")
    void reportsMissingWebhookUrl() {
        NotificationService.NotifyResult result =
                service.sendDiscord("  ", "Titel", "Text", NotificationService.COLOR_OK);

        assertThat(result.sent()).isFalse();
        assertThat(result.error()).contains("Webhook-URL");
    }

    /**
     * Baut ein Token, dessen Nutzdaten die angegebenen Scopes enthalten.
     *
     * @param scopes die zu hinterlegenden Scopes
     * @return ein Token in der Form header.payload.signature
     */
    private String tokenWithScopes(String... scopes) {
        String payload = "{\"scp\":[\"" + String.join("\",\"", scopes) + "\"],\"sub\":\"CHARACTER:EVE:1\"}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "header." + encoded + ".signature";
    }
}

package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zwei Wege raus, laut Protokoll gleichwertig: Discord-Webhook oder EVE-Ingame-Mail.
 * Beide Wege sind "best effort" - eine fehlgeschlagene Meldung darf die Vertragsprüfung
 * nicht abbrechen. Der Grund des Fehlschlags wird aber zurückgegeben, damit er im
 * Admin-Panel sichtbar wird und nicht nur im Container-Log landet.
 */
@Slf4j
@Service
public class NotificationService {

    public static final int COLOR_OK = 0x33FF33;
    public static final int COLOR_WARN = 0xFFB000;
    public static final int COLOR_REJECT = 0xFF3B30;

    /** Ohne diesen Scope lehnt ESI das Versenden ab. */
    public static final String MAIL_SCOPE = "esi-mail.send_mail.v1";

    private static final int DISCORD_DESCRIPTION_LIMIT = 4000;
    private static final int EVE_MAIL_BODY_LIMIT = 7000;

    private final RestClient webhookClient;
    private final EsiService esiService;
    private final AuthService authService;
    private final CharacterRepository characterRepo;
    private final ObjectMapper objectMapper;

    public NotificationService(RestClient.Builder builder,
                               EsiService esiService,
                               AuthService authService,
                               CharacterRepository characterRepo,
                               ObjectMapper objectMapper) {
        this.webhookClient = builder.build();
        this.esiService = esiService;
        this.authService = authService;
        this.characterRepo = characterRepo;
        this.objectMapper = objectMapper;
    }

    /** Ergebnis eines Sendeversuchs - bei Fehlschlag mit lesbarem Grund. */
    public record NotifyResult(boolean sent, String error) {
        /**
         * @return ein Ergebnis, das den erfolgreichen Versand meldet
         */
        public static NotifyResult ok() {
            return new NotifyResult(true, null);
        }

        /**
         * @param reason der Grund des Fehlschlags
         * @return ein Ergebnis, das den Fehlschlag samt Grund meldet
         */
        public static NotifyResult fail(String reason) {
            return new NotifyResult(false, reason);
        }
    }

    /**
     * Schickt eine Meldung an den konfigurierten Discord-Webhook.
     *
     * @param webhookUrl die Webhook-Adresse
     * @param title Ueberschrift der Meldung
     * @param description der Meldungstext
     * @param color Farbstreifen der Meldung
     * @return Erfolg oder Grund des Fehlschlags
     */
    public NotifyResult sendDiscord(String webhookUrl, String title, String description, int color) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return NotifyResult.fail("Keine Discord-Webhook-URL konfiguriert.");
        }

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", truncate(title, 250));
        embed.put("description", truncate(description, DISCORD_DESCRIPTION_LIMIT));
        embed.put("color", color);
        embed.put("timestamp", Instant.now().toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "Buybot 3000");
        body.put("embeds", List.of(embed));

        try {
            // Body selbst serialisieren - siehe EsiService.sendMail
            webhookClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
            return NotifyResult.ok();
        } catch (Exception e) {
            String reason = "Discord-Webhook fehlgeschlagen (" + describe(e) + ")";
            log.error(reason);
            return NotifyResult.fail(reason);
        }
    }

    /**
     * EVE-Mail über den Prüf-Charakter an sich selbst (oder einen anderen Empfänger).
     * Braucht den Scope {@value #MAIL_SCOPE} im Token des Absenders.
     */
    public NotifyResult sendEveMail(Long senderCharacterId, Long recipientId, String subject, String body) {
        if (senderCharacterId == null) {
            return NotifyResult.fail("Kein Absender-Charakter konfiguriert.");
        }
        Long recipient = (recipientId != null && recipientId > 0) ? recipientId : senderCharacterId;

        Character sender = characterRepo.findById(senderCharacterId).orElse(null);
        if (sender == null) {
            return NotifyResult.fail("Absender-Charakter " + senderCharacterId + " ist nicht mit dem Auth verknüpft.");
        }

        String token;
        try {
            token = authService.getValidAccessToken(sender);
        } catch (Exception e) {
            String reason = "Token für " + sender.getName() + " konnte nicht erneuert werden (" + describe(e) + ")";
            log.error(reason);
            return NotifyResult.fail(reason);
        }

        if (!authService.tokenHasScope(token, MAIL_SCOPE)) {
            return NotifyResult.fail("Der Token von " + sender.getName() + " enthält den Scope " + MAIL_SCOPE
                    + " nicht. Der Charakter muss sich einmal neu über EVE Login anmelden,"
                    + " oder es wird stattdessen der Discord-Webhook als Meldeweg genutzt.");
        }

        try {
            EsiService.EsiMailRequest mail = new EsiService.EsiMailRequest(
                    truncate(subject, 150),
                    truncate(body, EVE_MAIL_BODY_LIMIT).replace("\n", "<br>"),
                    List.of(new EsiService.EsiMailRecipient(recipient, "character")),
                    0
            );
            esiService.sendMail(senderCharacterId, token, mail);
            return NotifyResult.ok();
        } catch (Exception e) {
            String reason = "EVE-Mail an " + recipient + " abgelehnt (" + describe(e) + ")";
            log.error(reason);
            return NotifyResult.fail(reason);
        }
    }

    /** Macht aus einer Exception etwas, das im Admin-Panel weiterhilft. */
    private String describe(Exception e) {
        if (e instanceof RestClientResponseException re) {
            String responseBody = re.getResponseBodyAsString();
            String detail = (responseBody == null || responseBody.isBlank()) ? "" : ": " + truncate(responseBody.trim(), 300);
            return "HTTP " + re.getStatusCode().value() + detail;
        }
        // Bei I/O-Fehlern ist die Meldung oft null - dann die Ursachenkette abklappern
        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank() && !"null".equals(current.getMessage())) {
                return current.getClass().getSimpleName() + ": " + truncate(current.getMessage(), 300);
            }
            current = current.getCause();
        }
        return e.getClass().getSimpleName();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}

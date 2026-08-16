package com.eve.buy.bot.backend.domain.buybot.dto;

import com.eve.buy.bot.backend.domain.buybot.entity.BotTexts;

/**
 * Was das öffentliche Frontend von der Konfiguration wissen darf:
 * Wartungszustand, Bot-Sprüche, Reaktions-Schwellen und die Angaben,
 * die für die Vertragserstellung angezeigt werden.
 * Bewusst OHNE Preisbasis/Modifikatoren - die bleiben im Admin-Bereich.
 */
public record PublicConfigDto(
        boolean botEnabled,
        String maintenanceTitle,
        String maintenanceMessage,
        Double volumeThreshold,
        Double valueThreshold,
        Double itemValueThreshold,
        String contractRecipient,
        Integer contractExpireDays,
        Integer contractDaysToComplete,
        String contractNote,
        BotTexts botTexts
) {}

package com.eve.buy.bot.backend.domain.buybot.dto;

/** Die Felder aus der EVE-Statikdatenbank, die zur Bewertung eines Items noetig sind. */
public interface TypeDetailsProjection {
    Long getTypeId();
    String getTypeName();
    Double getVolume();
    Long getCategoryId();
}
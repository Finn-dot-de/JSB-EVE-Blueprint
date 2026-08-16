package com.eve.buy.bot.backend.domain.buybot.dto;

/**
 * Eine Zeile der Reprocessing-Ausbeute aus der SDE.
 * Die Mengen in invTypeMaterials gelten je "portionSize" Einheiten
 * (Erze werden z.B. in Batches von 100 verarbeitet) und entsprechen
 * der perfekten Ausbeute von 100 %.
 */
public interface ReprocessMaterialProjection {
    Long getTypeId();
    Long getMaterialTypeId();
    Long getQuantity();
    Integer getPortionSize();
}

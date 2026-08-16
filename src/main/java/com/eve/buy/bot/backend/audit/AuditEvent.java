package com.eve.buy.bot.backend.audit;

import java.time.Instant;

/**
 * Anwendungsereignis, das ein Protokolleintrag werden soll.
 *
 * <p>Alle Angaben sind zum Zeitpunkt des Auslösens bereits aufgelöst, weil der Eintrag
 * asynchron auf einem anderen Thread geschrieben wird und dort weder Request- noch
 * Sicherheitskontext zur Verfügung stehen.
 *
 * @param occurredAt       Zeitpunkt des Ereignisses
 * @param category         fachliche Einordnung
 * @param severity         Schweregrad
 * @param message          kurze Zusammenfassung
 * @param requestId        Kennung des auslösenden Aufrufs, {@code null} bei Hintergrundläufen
 * @param actorCharacterId angemeldeter Charakter, {@code null} bei nicht angemeldeten Spielern
 * @param actorName        Anzeigename des Auslösers oder {@code anonym}
 * @param clientIp         IP-Adresse des Aufrufers
 * @param userAgent        gemeldeter Browser oder Client
 * @param httpMethod       HTTP-Methode
 * @param path             aufgerufener Pfad
 * @param statusCode       HTTP-Status der Antwort
 * @param durationMs       Bearbeitungsdauer in Millisekunden
 * @param details          Zusatzangaben, etwa Ausnahmetyp und Ursache
 */
public record AuditEvent(Instant occurredAt,
                         AuditCategory category,
                         AuditSeverity severity,
                         String message,
                         String requestId,
                         Long actorCharacterId,
                         String actorName,
                         String clientIp,
                         String userAgent,
                         String httpMethod,
                         String path,
                         Integer statusCode,
                         Long durationMs,
                         String details) {
}

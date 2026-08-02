package com.eve.own.auth.backend.esi.etag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stellschrauben der ETag-Schicht, konfigurierbar ueber {@code esi.etag.*}.
 *
 * @param enabled          Schaltet konditionale Requests komplett ab. Nuetzlich zum Debuggen.
 * @param cachePayloads    Ob der letzte Response-Body mitgespeichert wird. Ohne ihn liefert
 *                         ein 304 keine Daten - Aufrufer muessen dann {@code notModified}
 *                         auswerten und duerfen {@code data()} nicht blind dereferenzieren.
 * @param maxPayloadBytes  Groessenobergrenze je Body. Groessere Antworten werden zwar
 *                         weiterhin per ETag geprueft, aber nicht zwischengespeichert.
 * @param retentionDays    Nach wie vielen Tagen ohne Zugriff ein Eintrag geloescht wird.
 */
@ConfigurationProperties(prefix = "esi.etag")
public record EsiEtagProperties(
        Boolean enabled,
        Boolean cachePayloads,
        Integer maxPayloadBytes,
        Integer retentionDays
) {

    public EsiEtagProperties {
        if (enabled == null) enabled = true;
        if (cachePayloads == null) cachePayloads = true;
        if (maxPayloadBytes == null) maxPayloadBytes = 262_144;
        if (retentionDays == null) retentionDays = 30;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean shouldCachePayloads() {
        return Boolean.TRUE.equals(cachePayloads);
    }
}

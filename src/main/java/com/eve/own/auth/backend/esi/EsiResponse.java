package com.eve.own.auth.backend.esi;

import java.time.Instant;

/**
 * Ergebnis eines ESI-Aufrufs inklusive Cache-Information.
 *
 * <p>{@code notModified} bedeutet: ESI hat mit 304 geantwortet, die Daten haben
 * sich seit dem letzten Abruf nicht geaendert. {@code data} ist in dem Fall
 * trotzdem gefuellt, sofern der letzte Response-Body im ETag-Cache liegt.
 * Aufrufer, die nur bei echten Aenderungen arbeiten wollen, pruefen
 * {@link #notModified()}; Aufrufer, die schlicht den Wert brauchen, nutzen
 * {@link #data()} wie bisher.</p>
 */
public record EsiResponse<T>(T data, String etag, boolean notModified, Instant expiresAt) {

    /** ESI hat mit 200 geantwortet, die Daten sind neu. */
    public static <T> EsiResponse<T> changed(T data, String etag, Instant expiresAt) {
        return new EsiResponse<>(data, etag, false, expiresAt);
    }

    /** ESI hat mit 304 geantwortet; {@code data} stammt aus dem Cache und kann null sein. */
    public static <T> EsiResponse<T> unchanged(T cachedData, String etag, Instant expiresAt) {
        return new EsiResponse<>(cachedData, etag, true, expiresAt);
    }

    /** Kein Ergebnis, z.B. weil der Aufruf uebersprungen wurde. */
    public static <T> EsiResponse<T> empty() {
        return new EsiResponse<>(null, null, false, null);
    }

    public boolean hasData() {
        return data != null;
    }

    public T dataOr(T fallback) {
        return data != null ? data : fallback;
    }
}

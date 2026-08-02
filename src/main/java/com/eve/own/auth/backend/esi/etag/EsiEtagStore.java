package com.eve.own.auth.backend.esi.etag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Persistenz der ESI-Cache-Eintraege.
 *
 * <p>Alle Schreibzugriffe laufen in einer eigenen Transaktion. Das ist Absicht:
 * ob ein ETag gespeichert wurde, darf nicht davon abhaengen, ob die fachliche
 * Transaktion des Aufrufers spaeter noch zurueckrollt.</p>
 */
@Slf4j
@Service
public class EsiEtagStore {

    /** Ab dieser Laenge wird der Pfad gehasht, damit der Primaerschluessel passt. */
    private static final int MAX_READABLE_KEY_LENGTH = 180;

    private final EsiEtagRepository repository;
    private final EsiEtagProperties properties;

    public EsiEtagStore(EsiEtagRepository repository, EsiEtagProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Bildet den Cache-Schluessel aus dem aufgeloesten Request-Pfad.
     * Kurze Pfade bleiben lesbar, damit man in der Tabelle noch etwas erkennt.
     */
    public String cacheKey(String resolvedPath) {
        if (resolvedPath.length() <= MAX_READABLE_KEY_LENGTH) {
            return resolvedPath;
        }
        return "sha256:" + sha256(resolvedPath);
    }

    @Transactional(readOnly = true)
    public Optional<EsiEtag> find(String cacheKey) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return repository.findById(cacheKey);
    }

    /** ESI hat geaenderte Daten geliefert (HTTP 200). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChanged(String cacheKey, String etag, String payload,
                              Integer pageCount, Instant expiresAt) {
        if (!properties.isEnabled() || etag == null) {
            return;
        }
        Instant now = Instant.now();
        EsiEtag entry = repository.findById(cacheKey).orElseGet(EsiEtag::new);
        entry.setCacheKey(cacheKey);
        entry.setEtag(etag);
        entry.setPayload(storablePayload(payload));
        entry.setPageCount(pageCount);
        entry.setExpiresAt(expiresAt);
        entry.setLastCheckedAt(now);
        entry.setLastChangedAt(now);
        entry.setNotModifiedHits(0L);
        repository.save(entry);
    }

    /** ESI hat mit 304 geantwortet - nur die Zeitstempel fortschreiben. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNotModified(String cacheKey, Instant expiresAt) {
        if (!properties.isEnabled()) {
            return;
        }
        repository.findById(cacheKey).ifPresent(entry -> {
            entry.setLastCheckedAt(Instant.now());
            if (expiresAt != null) {
                entry.setExpiresAt(expiresAt);
            }
            long hits = entry.getNotModifiedHits() != null ? entry.getNotModifiedHits() : 0L;
            entry.setNotModifiedHits(hits + 1);
            repository.save(entry);
        });
    }

    /**
     * Entfernt Eintraege, die seit {@code retentionDays} nicht mehr angefasst wurden -
     * etwa zu Charakteren, deren Token laengst abgelaufen ist.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long purgeStaleEntries() {
        Instant threshold = Instant.now().minus(Duration.ofDays(properties.retentionDays()));
        long removed = repository.deleteByLastCheckedAtBefore(threshold);
        if (removed > 0) {
            log.info("ETag-Cache aufgeraeumt: {} veraltete Eintraege entfernt.", removed);
        }
        return removed;
    }

    /**
     * Gibt den Body nur zurueck, wenn er gespeichert werden darf und die
     * Groessengrenze einhaelt. Zu grosse Bodies werden bewusst verworfen:
     * der ETag-Abgleich funktioniert weiterhin, nur der 304 liefert dann
     * keine Daten mehr mit.
     */
    private String storablePayload(String payload) {
        if (payload == null || !properties.shouldCachePayloads()) {
            return null;
        }
        int sizeInBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (sizeInBytes > properties.maxPayloadBytes()) {
            log.debug("Response-Body von {} Bytes ueberschreitet das Limit und wird nicht zwischengespeichert.", sizeInBytes);
            return null;
        }
        return payload;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist in jeder JVM vorhanden", e);
        }
    }
}

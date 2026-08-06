package com.eve.own.auth.backend.esi.etag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ETag-Cache")
class EsiEtagStoreTest {

    private static final String KEY = "/characters/1000/assets/";
    private static final String ETAG = "W/\"abc123\"";

    @Mock private EsiEtagRepository repository;
    @Mock private EsiEtagProperties properties;

    private EsiEtagStore store;

    @BeforeEach
    void setUp() {
        store = new EsiEtagStore(repository, properties);

        when(properties.isEnabled()).thenReturn(true);
        when(properties.shouldCachePayloads()).thenReturn(true);
        when(properties.maxPayloadBytes()).thenReturn(1_000_000);
        when(properties.retentionDays()).thenReturn(30);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private EsiEtag savedEntry() {
        ArgumentCaptor<EsiEtag> captor = ArgumentCaptor.forClass(EsiEtag.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Schluesselbildung")
    class CacheKeys {

        @Test
        @DisplayName("laesst kurze Pfade lesbar")
        void keepsShortPathsReadable() {
            assertThat(store.cacheKey(KEY)).isEqualTo(KEY);
        }

        @Test
        @DisplayName("kuerzt zu lange Pfade auf einen Hash")
        void hashesLongPaths() {
            // Sonst passte der Schluessel nicht mehr in den Primaerschluessel.
            String longPath = "/x".repeat(200);

            String key = store.cacheKey(longPath);

            assertThat(key).startsWith("sha256:").hasSizeLessThan(longPath.length());
        }

        @Test
        @DisplayName("bildet denselben Pfad immer auf denselben Schluessel ab")
        void isDeterministic() {
            String longPath = "/x".repeat(200);

            assertThat(store.cacheKey(longPath)).isEqualTo(store.cacheKey(longPath));
        }
    }

    @Nested
    @DisplayName("Geaenderte Daten (HTTP 200)")
    class Changed {

        @Test
        @DisplayName("legt ETag, Body und Zeitstempel ab")
        void storesEverything() {
            store.recordChanged(KEY, ETAG, "[]", 3, Instant.parse("2026-08-05T12:00:00Z"));

            EsiEtag entry = savedEntry();
            assertThat(entry.getCacheKey()).isEqualTo(KEY);
            assertThat(entry.getEtag()).isEqualTo(ETAG);
            assertThat(entry.getPayload()).isEqualTo("[]");
            assertThat(entry.getPageCount()).isEqualTo(3);
            assertThat(entry.getNotModifiedHits()).isZero();
            assertThat(entry.getLastChangedAt()).isNotNull();
        }

        @Test
        @DisplayName("speichert nichts ohne ETag")
        void ignoresResponseWithoutEtag() {
            store.recordChanged(KEY, null, "[]", 1, null);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("verwirft einen zu grossen Body, behaelt aber den ETag")
        void dropsOversizedPayload() {
            // Der Abgleich funktioniert weiter, nur der 304 liefert dann keine Daten.
            when(properties.maxPayloadBytes()).thenReturn(10);

            store.recordChanged(KEY, ETAG, "ein deutlich zu langer Body", 1, null);

            EsiEtag entry = savedEntry();
            assertThat(entry.getEtag()).isEqualTo(ETAG);
            assertThat(entry.getPayload()).isNull();
        }

        @Test
        @DisplayName("speichert den Body gar nicht, wenn das abgeschaltet ist")
        void respectsPayloadCachingSwitch() {
            when(properties.shouldCachePayloads()).thenReturn(false);

            store.recordChanged(KEY, ETAG, "[]", 1, null);

            assertThat(savedEntry().getPayload()).isNull();
        }

        @Test
        @DisplayName("schreibt einen vorhandenen Eintrag fort statt einen zweiten anzulegen")
        void updatesExistingEntry() {
            EsiEtag existing = new EsiEtag();
            existing.setCacheKey(KEY);
            existing.setNotModifiedHits(17L);
            when(repository.findById(KEY)).thenReturn(Optional.of(existing));

            store.recordChanged(KEY, ETAG, "[]", 1, null);

            assertThat(existing.getEtag()).isEqualTo(ETAG);
            assertThat(existing.getNotModifiedHits()).isZero();
        }
    }

    @Nested
    @DisplayName("Unveraenderte Daten (HTTP 304)")
    class NotModified {

        @Test
        @DisplayName("zaehlt die Treffer und schreibt den Zeitstempel fort")
        void countsHits() {
            EsiEtag existing = new EsiEtag();
            existing.setCacheKey(KEY);
            existing.setNotModifiedHits(4L);
            when(repository.findById(KEY)).thenReturn(Optional.of(existing));

            store.recordNotModified(KEY, Instant.parse("2026-08-05T12:00:00Z"));

            assertThat(existing.getNotModifiedHits()).isEqualTo(5L);
            assertThat(existing.getLastCheckedAt()).isNotNull();
            assertThat(existing.getExpiresAt()).isEqualTo(Instant.parse("2026-08-05T12:00:00Z"));
        }

        @Test
        @DisplayName("faengt einen fehlenden Zaehler ab")
        void handlesMissingCounter() {
            EsiEtag existing = new EsiEtag();
            existing.setCacheKey(KEY);
            when(repository.findById(KEY)).thenReturn(Optional.of(existing));

            store.recordNotModified(KEY, null);

            assertThat(existing.getNotModifiedHits()).isEqualTo(1L);
        }

        @Test
        @DisplayName("macht nichts, wenn der Eintrag gar nicht existiert")
        void ignoresUnknownKey() {
            store.recordNotModified(KEY, null);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Abgeschalteter Cache")
    class Disabled {

        @BeforeEach
        void turnOff() {
            when(properties.isEnabled()).thenReturn(false);
        }

        @Test
        @DisplayName("liefert nichts und schreibt nichts")
        void staysOutOfTheWay() {
            assertThat(store.find(KEY)).isEmpty();

            store.recordChanged(KEY, ETAG, "[]", 1, null);
            store.recordNotModified(KEY, null);

            verify(repository, never()).save(any());
            verify(repository, never()).findById(anyString());
        }
    }

    @Nested
    @DisplayName("Aufraeumen")
    class Purging {

        @Test
        @DisplayName("entfernt Eintraege, die lange nicht mehr angefasst wurden")
        void removesStaleEntries() {
            when(repository.deleteByLastCheckedAtBefore(any())).thenReturn(7L);

            assertThat(store.purgeStaleEntries()).isEqualTo(7L);
        }

        @Test
        @DisplayName("meldet null, wenn nichts zu entfernen war")
        void reportsNothingRemoved() {
            when(repository.deleteByLastCheckedAtBefore(any())).thenReturn(0L);

            assertThat(store.purgeStaleEntries()).isZero();
        }
    }

    @Test
    @DisplayName("liefert einen vorhandenen Eintrag")
    void findsExistingEntry() {
        EsiEtag existing = new EsiEtag();
        when(repository.findById(KEY)).thenReturn(Optional.of(existing));

        assertThat(store.find(KEY)).contains(existing);
    }
}

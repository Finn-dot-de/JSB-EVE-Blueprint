package com.eve.own.auth.backend.esi.client;

import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.etag.EsiEtag;
import com.eve.own.auth.backend.esi.etag.EsiEtagStore;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fuehrt alle ESI-Aufrufe aus und kuemmert sich um das Cache-Handling.
 *
 * <p>Jeder GET geht als konditionaler Request raus: liegt zu dem Pfad ein ETag
 * vor, wandert er als {@code If-None-Match} mit. Antwortet ESI mit 304, wird der
 * zuletzt gespeicherte Body zurueckgegeben und {@code notModified} gesetzt.</p>
 *
 * <p>Bewusst arbeitet die Klasse mit rohem JSON statt direkt mit Zieltypen:
 * nur so laesst sich der Body unveraendert im ETag-Cache ablegen und spaeter
 * wieder in den gewuenschten Typ ueberfuehren.</p>
 */
@Slf4j
@Component
public class EsiRequestExecutor {

    /** Platzhaltername fuer die Seitennummer - kollidiert nicht mit echten ESI-Parametern. */
    private static final String PAGE_VARIABLE = "esiPage";

    private static final String HEADER_PAGES = "X-Pages";

    private final RestClient restClient;
    private final EsiEtagStore etagStore;
    private final ObjectMapper objectMapper;

    public EsiRequestExecutor(RestClient esiClient, EsiEtagStore etagStore, ObjectMapper objectMapper) {
        this.restClient = esiClient;
        this.etagStore = etagStore;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // Einzelner GET
    // ==================================================================

    /**
     * Konditionaler GET auf einen einseitigen ESI-Endpunkt.
     *
     * @param uriTemplate  Pfad mit Platzhaltern, z.B. {@code /characters/{id}/mining/}
     * @param uriVariables Werte fuer die Platzhalter
     * @param token        Access-Token oder null bei oeffentlichen Endpunkten
     * @param responseType Zieltyp der Deserialisierung
     */
    public <T> EsiResponse<T> get(String uriTemplate, Object[] uriVariables, String token, Class<T> responseType) {
        PageResult<T> result = getPage(uriTemplate, uriVariables, token, responseType, true);
        return result.response();
    }

    // ==================================================================
    // Paginierter GET
    // ==================================================================

    /**
     * Laeuft alle Seiten eines paginierten Endpunkts ab und haengt die Ergebnisse aneinander.
     *
     * <p>Jede Seite wird einzeln per ETag geprueft. Nur wenn <em>alle</em> Seiten mit 304
     * antworten, meldet das Ergebnis {@code notModified} - dann kann der Aufrufer die
     * gesamte Weiterverarbeitung ueberspringen.</p>
     *
     * <p>Liefert eine Seite 304, ohne dass ihr Body im Cache liegt (weil er zu gross war),
     * wird genau diese Seite unkonditional nachgeladen. Sonst haette man eine Luecke
     * in den Daten.</p>
     */
    public <T> EsiResponse<List<T>> getAllPages(String uriTemplate, Object[] uriVariables,
                                                String token, Class<T[]> arrayType) {
        List<T> collected = new ArrayList<>();
        boolean everythingUnchanged = true;
        int page = 1;
        int totalPages = 1;

        do {
            PageResult<T[]> result = getPage(pagedTemplate(uriTemplate), withPage(uriVariables, page),
                    token, arrayType, true);

            // Der konditionale Versuch entscheidet, ob sich etwas geaendert hat.
            // Ein spaeteres Nachladen darf dieses Urteil nicht mehr kippen.
            boolean pageUnchanged = result.response().notModified();
            T[] items = result.response().data();

            if (pageUnchanged && items == null) {
                // 304, aber der Body war zu gross fuer den Cache: Seite unkonditional nachholen.
                result = getPage(pagedTemplate(uriTemplate), withPage(uriVariables, page),
                        token, arrayType, false);
                items = result.response().data();
            }

            if (!pageUnchanged) {
                everythingUnchanged = false;
            }
            if (items != null) {
                collected.addAll(Arrays.asList(items));
            }
            totalPages = Math.max(totalPages, result.totalPages());
            page++;
        } while (page <= totalPages);

        return everythingUnchanged
                ? EsiResponse.unchanged(collected, null, null)
                : EsiResponse.changed(collected, null, null);
    }

    // ==================================================================
    // POST (ohne Cache - ESI liefert dafuer keine ETags)
    // ==================================================================

    /** POST auf einen oeffentlichen Endpunkt ohne Pfadvariablen. */
    public <T> T post(String uriTemplate, Object body, Class<T> responseType) {
        return post(uriTemplate, new Object[0], body, null, responseType);
    }

    /**
     * POST mit Pfadvariablen und optionalem Token.
     *
     * @param uriVariables Werte fuer die Platzhalter im Template
     * @param token        Access-Token oder null bei oeffentlichen Endpunkten
     */
    public <T> T post(String uriTemplate, Object[] uriVariables, Object body,
                      String token, Class<T> responseType) {
        return restClient.post()
                .uri(uriTemplate, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    }
                })
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(responseType);
    }

    // ==================================================================
    // Interna
    // ==================================================================

    /** Ergebnis einer einzelnen Antwort samt Seitenanzahl aus dem X-Pages-Header. */
    private record PageResult<T>(EsiResponse<T> response, int totalPages) {}

    private <T> PageResult<T> getPage(String uriTemplate, Object[] uriVariables, String token,
                                      Class<T> responseType, boolean conditional) {
        String cacheKey = etagStore.cacheKey(resolvePath(uriTemplate, uriVariables));
        EsiEtag cached = conditional ? etagStore.find(cacheKey).orElse(null) : null;

        ResponseEntity<String> response = send(uriTemplate, uriVariables, token,
                cached != null ? cached.getEtag() : null);

        Instant expiresAt = readExpires(response.getHeaders());

        if (response.getStatusCode().value() == HttpStatus.NOT_MODIFIED.value()) {
            etagStore.recordNotModified(cacheKey, expiresAt);
            String cachedPayload = cached != null ? cached.getPayload() : null;
            String cachedEtag = cached != null ? cached.getEtag() : null;
            int pages = pagesOf(response.getHeaders(), cached);
            return new PageResult<>(
                    EsiResponse.unchanged(deserialize(cachedPayload, responseType), cachedEtag, expiresAt), pages);
        }

        String body = response.getBody();
        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        int pages = pagesOf(response.getHeaders(), cached);

        etagStore.recordChanged(cacheKey, etag, body, pages, expiresAt);
        return new PageResult<>(EsiResponse.changed(deserialize(body, responseType), etag, expiresAt), pages);
    }

    private ResponseEntity<String> send(String uriTemplate, Object[] uriVariables, String token, String etag) {
        return restClient.get()
                .uri(uriTemplate, uriVariables)
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    }
                    // Roh setzen statt setIfNoneMatch: ESI liefert schwache ETags
                    // der Form W/"...", die unveraendert zurueckgehen muessen.
                    if (etag != null) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                    }
                })
                .retrieve()
                .toEntity(String.class);
    }

    private <T> T deserialize(String json, Class<T> responseType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception e) {
            log.warn("ESI-Antwort liess sich nicht als {} lesen: {}", responseType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Seitenanzahl bestimmen. ESI schickt X-Pages nicht zuverlaessig auf 304 mit,
     * deshalb dient der gespeicherte Wert als Rueckfallebene.
     */
    private int pagesOf(HttpHeaders headers, EsiEtag cached) {
        String header = headers.getFirst(HEADER_PAGES);
        if (header != null) {
            try {
                return Integer.parseInt(header.trim());
            } catch (NumberFormatException e) {
                log.debug("Unlesbarer X-Pages-Header: {}", header);
            }
        }
        return cached != null && cached.getPageCount() != null ? cached.getPageCount() : 1;
    }

    private Instant readExpires(HttpHeaders headers) {
        long expiresInMillis = headers.getExpires();
        return expiresInMillis > 0 ? Instant.ofEpochMilli(expiresInMillis) : null;
    }

    /** Loest die Platzhalter auf, damit der Cache-Schluessel den konkreten Aufruf abbildet. */
    private String resolvePath(String uriTemplate, Object[] uriVariables) {
        try {
            return new UriTemplate(uriTemplate).expand(uriVariables).toString();
        } catch (Exception e) {
            log.debug("Pfad {} liess sich nicht aufloesen, nutze Template als Schluessel.", uriTemplate);
            return uriTemplate;
        }
    }

    private String pagedTemplate(String uriTemplate) {
        String separator = uriTemplate.contains("?") ? "&" : "?";
        return uriTemplate + separator + "page={" + PAGE_VARIABLE + "}";
    }

    private Object[] withPage(Object[] uriVariables, int page) {
        Object[] extended = Arrays.copyOf(uriVariables, uriVariables.length + 1);
        extended[uriVariables.length] = page;
        return extended;
    }
}

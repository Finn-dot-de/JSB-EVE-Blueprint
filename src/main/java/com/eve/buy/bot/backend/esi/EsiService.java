package com.eve.buy.bot.backend.esi;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Schmaler Zugang zur EVE Swagger Interface (ESI).
 *
 * <p>Es sind bewusst nur die Endpunkte abgebildet, die der Buybot braucht: Charakter- und
 * Corporationsdaten für die Anmeldung, die Titelabfrage für die Rollen, Verträge samt
 * Inhalt für die Vertragsprüfung, Ortsauflösung und der Mailversand für Benachrichtigungen.
 */
@Service
public class EsiService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * Antwort eines ESI-Aufrufs samt ETag.
     *
     * @param data die gelesenen Nutzdaten, {@code null} bei HTTP 304
     * @param etag das ETag der Antwort, für den nächsten Aufruf
     * @param <T>  Typ der Nutzdaten
     */
    public record EsiResponse<T>(T data, String etag) {}

    /**
     * @param esiClient    auf die ESI-Basis-URL vorkonfigurierter HTTP-Client
     * @param objectMapper serialisiert Anfragekörper selbst, siehe {@link #sendMail}
     */
    public EsiService(RestClient esiClient, ObjectMapper objectMapper) {
        this.restClient = esiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Führt einen lesenden ESI-Aufruf aus und wertet das ETag aus.
     *
     * @param uri           Pfadvorlage relativ zur ESI-Basis-URL
     * @param uriVariables  Werte für die Platzhalter der Vorlage
     * @param token         Zugriffstoken für private Endpunkte, sonst {@code null}
     * @param oldEtag       ETag des letzten Aufrufs, sonst {@code null}
     * @param responseType  erwarteter Antworttyp
     * @param <T>           erwarteter Antworttyp
     * @return Nutzdaten und ETag; bei HTTP 304 sind die Nutzdaten {@code null}
     */
    private <T> EsiResponse<T> fetch(String uri, Object[] uriVariables, String token, String oldEtag, Class<T> responseType) {
        var request = restClient.get().uri(uri, uriVariables);

        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (oldEtag != null) {
            request.header("If-None-Match", oldEtag);
        }

        ResponseEntity<T> response = request.retrieve().toEntity(responseType);
        if (response.getStatusCode().value() == 304) {
            return new EsiResponse<>(null, oldEtag);
        }
        return new EsiResponse<>(response.getBody(), response.getHeaders().getFirst("ETag"));
    }

    /**
     * Liest die öffentlichen Stammdaten eines Charakters.
     *
     * @param characterId EVE-Charakter-ID
     * @param etag        ETag des letzten Aufrufs, sonst {@code null}
     * @return Name und aktuelle Corporation
     */
    public EsiResponse<EsiCharacterResponse> getCharacter(Long characterId, String etag) {
        return fetch("/characters/{id}/", new Object[]{characterId}, null, etag, EsiCharacterResponse.class);
    }

    /**
     * Liest die öffentlichen Stammdaten einer Corporation.
     *
     * @param corporationId EVE-Corporation-ID
     * @param etag          ETag des letzten Aufrufs, sonst {@code null}
     * @return Name, Ticker und Allianz der Corporation
     */
    public EsiResponse<EsiCorporationResponse> getCorporation(Long corporationId, String etag) {
        return fetch("/corporations/{id}/", new Object[]{corporationId}, null, etag, EsiCorporationResponse.class);
    }

    /**
     * Bequemere Form von {@link #getCorporation} ohne ETag-Behandlung.
     *
     * @param corporationId EVE-Corporation-ID
     * @return die Corporationsdaten, {@code null} wenn ESI nichts liefert
     */
    public EsiCorporationResponse getCorporationInfo(Long corporationId) {
        return fetch("/corporations/{id}/", new Object[]{corporationId}, null, null, EsiCorporationResponse.class).data();
    }

    /**
     * Liest die öffentlichen Stammdaten einer Allianz.
     *
     * @param allianceId EVE-Allianz-ID
     * @param etag       ETag des letzten Aufrufs, sonst {@code null}
     * @return Name und Ticker der Allianz
     */
    public EsiResponse<EsiAllianceResponse> getAlliance(Long allianceId, String etag) {
        return fetch("/alliances/{id}/", new Object[]{allianceId}, null, etag, EsiAllianceResponse.class);
    }

    /**
     * Liest die Corp-Titel eines Charakters; Grundlage der Rollenvergabe.
     *
     * @param characterId EVE-Charakter-ID
     * @param token       Zugriffstoken desselben Charakters
     * @param etag        ETag des letzten Aufrufs, sonst {@code null}
     * @return die Titel des Charakters
     */
    public EsiResponse<EsiTitleResponse[]> getCharacterTitles(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/titles/", new Object[]{characterId}, token, etag, EsiTitleResponse[].class);
    }

    /**
     * Sucht eine Station oder Upwell-Struktur nach Namen.
     *
     * <p>Strukturen liefert ESI nur, wenn der suchende Charakter Zugang zu ihnen hat.
     *
     * @param characterId  suchender Charakter
     * @param token        dessen Zugriffstoken
     * @param searchString der gesuchte Ortsname
     * @return Treffer getrennt nach Struktur und Station
     */
    public EsiResponse<EsiSearchResponse> searchStructureOrStation(Long characterId, String token, String searchString) {
        return fetch("/characters/{id}/search/?categories=structure,station&search={search}&strict=false",
                new Object[]{characterId, searchString}, token, null, EsiSearchResponse.class);
    }

    /**
     * Holt alle Verträge des Charakters über alle Seiten hinweg.
     *
     * @param characterId EVE-Charakter-ID
     * @param token       dessen Zugriffstoken
     * @return alle Verträge, die ESI für diesen Charakter kennt
     */
    public List<EsiContractResponse> getAllCharacterContracts(Long characterId, String token) {
        List<EsiContractResponse> all = new ArrayList<>();
        int page = 1;
        int maxPages = 1;

        do {
            ResponseEntity<EsiContractResponse[]> response = restClient.get()
                    .uri("/characters/{id}/contracts/?page={page}", characterId, page)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toEntity(EsiContractResponse[].class);

            if (response.getBody() != null) {
                all.addAll(List.of(response.getBody()));
            }

            String xPages = response.getHeaders().getFirst("X-Pages");
            if (xPages != null) {
                maxPages = Integer.parseInt(xPages);
            }
            page++;
        } while (page <= maxPages);

        return all;
    }

    /**
     * Liest die Positionen eines Vertrags.
     *
     * @param characterId Charakter, der den Vertrag sehen darf
     * @param contractId  ID des Vertrags
     * @param token       Zugriffstoken des Charakters
     * @return die Positionen, ggf. leer
     */
    public List<EsiContractItemResponse> getContractItems(Long characterId, Long contractId, String token) {
        EsiResponse<EsiContractItemResponse[]> response = fetch("/characters/{id}/contracts/{contractId}/items/",
                new Object[]{characterId, contractId}, token, null, EsiContractItemResponse[].class);
        return response.data() == null ? List.of() : List.of(response.data());
    }

    /**
     * Löst eine NPC-Station auf.
     *
     * @param stationId EVE-Stations-ID
     * @return die Stationsdaten
     */
    public EsiStationResponse getStation(Long stationId) {
        return fetch("/universe/stations/{id}/", new Object[]{stationId}, null, null, EsiStationResponse.class).data();
    }

    /**
     * Löst eine Upwell-Struktur auf; erfordert Zugang des Charakters zur Struktur.
     *
     * @param structureId EVE-Struktur-ID
     * @param token       Zugriffstoken eines berechtigten Charakters
     * @return die Strukturdaten
     */
    public EsiStructureResponse getStructure(Long structureId, String token) {
        return fetch("/universe/structures/{id}/", new Object[]{structureId}, token, null, EsiStructureResponse.class).data();
    }

    /**
     * Verschickt eine EVE-Ingame-Mail im Namen des Charakters.
     *
     * <p>Der JSON-Körper wird hier selbst serialisiert und als String übergeben: der
     * ESI-Client ist ohne Konverter für ausgehende Objekte gebaut, ein Objekt-Body käme bei
     * ESI leer an.
     *
     * @param characterId Absender
     * @param token       dessen Zugriffstoken mit {@code esi-mail.send_mail.v1}
     * @param mail        Betreff, Text und Empfänger
     */
    public void sendMail(Long characterId, String token, EsiMailRequest mail) {
        String json = objectMapper.writeValueAsString(mail);
        restClient.post()
                .uri("/characters/{id}/mail/", characterId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Öffentliche Charakterdaten.
     *
     * @param name           Anzeigename
     * @param corporation_id aktuelle Corporation
     */
    public record EsiCharacterResponse(String name, Long corporation_id) {}

    /**
     * Öffentliche Corporationsdaten.
     *
     * @param name        Anzeigename
     * @param ticker      Kürzel
     * @param alliance_id Allianz, sonst {@code null}
     * @param faction_id  Fraktion bei NPC-Corporations, sonst {@code null}
     */
    public record EsiCorporationResponse(String name, String ticker, Long alliance_id, Long faction_id) {}

    /**
     * Öffentliche Allianzdaten.
     *
     * @param name   Anzeigename
     * @param ticker Kürzel
     */
    public record EsiAllianceResponse(String name, String ticker) {}

    /**
     * Ein Corp-Titel eines Charakters.
     *
     * @param title_id ID des Titels innerhalb der Corporation
     * @param name     Anzeigename, kann EVE-Markup enthalten
     */
    public record EsiTitleResponse(Long title_id, String name) {}

    /**
     * Treffer der Ortssuche.
     *
     * @param structure gefundene Upwell-Strukturen
     * @param station   gefundene NPC-Stationen
     */
    public record EsiSearchResponse(List<Long> structure, List<Long> station) {}

    /**
     * Eine NPC-Station.
     *
     * @param station_id ID der Station
     * @param name       Anzeigename
     * @param system_id  Sonnensystem
     * @param type_id    Stationstyp
     */
    public record EsiStationResponse(Long station_id, String name, Long system_id, Long type_id) {}

    /**
     * Eine Upwell-Struktur.
     *
     * @param name             Anzeigename
     * @param solar_system_id  Sonnensystem
     * @param type_id          Strukturtyp
     * @param owner_id         besitzende Corporation
     */
    public record EsiStructureResponse(String name, Long solar_system_id, Long type_id, Long owner_id) {}

    /**
     * Eine Position innerhalb eines Vertrags.
     *
     * @param record_id    laufende Nummer innerhalb des Vertrags
     * @param type_id      Item-Typ
     * @param quantity     Stückzahl
     * @param is_included  {@code true}, wenn der Ersteller das Item mitliefert, {@code false},
     *                     wenn er es einfordert
     * @param is_singleton {@code true} bei unverpackten Einzelstücken
     * @param raw_quantity negative Werte kennzeichnen Blueprint-Kopien
     */
    public record EsiContractItemResponse(Long record_id, Long type_id, Long quantity,
                                          Boolean is_included, Boolean is_singleton, Long raw_quantity) {}

    /**
     * Ein Vertrag aus Sicht des abfragenden Charakters.
     *
     * @param contract_id            ID des Vertrags
     * @param issuer_id              erstellender Charakter
     * @param issuer_corporation_id  dessen Corporation
     * @param assignee_id            Empfänger, sonst {@code null}
     * @param acceptor_id            annehmender Charakter, sonst {@code null}
     * @param availability           Sichtbarkeit, etwa {@code personal} oder {@code public}
     * @param status                 Zustand, offen ist {@code outstanding}
     * @param type                   Vertragsart, für den Ankauf {@code item_exchange}
     * @param title                  frei gewählter Titel
     * @param price                  vom Ersteller geforderte ISK
     * @param reward                 vom Ersteller gebotene ISK
     * @param collateral             hinterlegte Sicherheit bei Kurierverträgen
     * @param buyout                 Sofortkaufpreis bei Auktionen
     * @param volume                 Gesamtvolumen in m3
     * @param days_to_complete       Frist bei Kurierverträgen
     * @param start_location_id      Ort des Vertrags
     * @param end_location_id        Zielort bei Kurierverträgen
     * @param date_issued            Zeitpunkt der Erstellung
     * @param date_expired           Ablaufzeitpunkt
     * @param for_corporation        {@code true}, wenn im Namen der Corporation erstellt
     */
    public record EsiContractResponse(Long contract_id, Long issuer_id, Long issuer_corporation_id,
                                      Long assignee_id, Long acceptor_id,
                                      String availability, String status, String type, String title,
                                      Double price, Double reward, Double collateral, Double buyout, Double volume,
                                      Integer days_to_complete,
                                      Long start_location_id, Long end_location_id,
                                      Instant date_issued, Instant date_expired,
                                      Boolean for_corporation) {}

    /**
     * Empfänger einer EVE-Mail.
     *
     * @param recipient_id   ID des Empfängers
     * @param recipient_type Art des Empfängers, hier stets {@code character}
     */
    public record EsiMailRecipient(Long recipient_id, String recipient_type) {}

    /**
     * Körper einer zu versendenden EVE-Mail.
     *
     * @param subject       Betreff
     * @param body          Text, Zeilenumbrüche als {@code <br>}
     * @param recipients    Empfängerliste
     * @param approved_cost akzeptierte CSPA-Gebühr, für eigene Mails 0
     */
    public record EsiMailRequest(String subject, String body, List<EsiMailRecipient> recipients, Integer approved_cost) {}
}

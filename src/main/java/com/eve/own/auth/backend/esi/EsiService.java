package com.eve.own.auth.backend.esi;

import com.eve.own.auth.backend.esi.client.EsiRequestExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Instant;

/**
 * Typisierte Sicht auf die ESI-Endpunkte.
 *
 * <p>Diese Klasse beschreibt nur noch <em>welcher</em> Endpunkt mit welchem Typ
 * abgefragt wird. Das <em>wie</em> - konditionale Requests, 304-Behandlung,
 * Paginierung - liegt vollstaendig im {@link EsiRequestExecutor}. Deshalb gibt es
 * hier auch keine ETag-Parameter mehr: der Cache wird zentral verwaltet.</p>
 */
@Slf4j
@Service
public class EsiService {

    private final EsiRequestExecutor executor;

    public EsiService(EsiRequestExecutor executor) {
        this.executor = executor;
    }

    // ==================================================================
    // Oeffentliche Stammdaten
    // ==================================================================

    public EsiResponse<EsiCharacterResponse> getCharacter(Long characterId) {
        return executor.get("/characters/{id}/", new Object[]{characterId}, null, EsiCharacterResponse.class);
    }

    public EsiResponse<EsiCorporationResponse> getCorporation(Long corporationId) {
        return executor.get("/corporations/{id}/", new Object[]{corporationId}, null, EsiCorporationResponse.class);
    }

    public EsiResponse<EsiAllianceResponse> getAlliance(Long allianceId) {
        return executor.get("/alliances/{id}/", new Object[]{allianceId}, null, EsiAllianceResponse.class);
    }

    /** Bequemer Direktzugriff fuer Aufrufer, die nur den Datensatz brauchen. */
    public EsiCorporationResponse getCorporationInfo(Long corporationId) {
        return getCorporation(corporationId).data();
    }

    // ==================================================================
    // Charakterdaten (Token noetig)
    // ==================================================================

    public EsiResponse<Double> getWalletBalance(Long characterId, String token) {
        return executor.get("/characters/{id}/wallet/", new Object[]{characterId}, token, Double.class);
    }

    public EsiResponse<SkillResponse> getSkills(Long characterId, String token) {
        return executor.get("/characters/{id}/skills/", new Object[]{characterId}, token, SkillResponse.class);
    }

    public EsiResponse<EsiLpResponse[]> getLoyaltyPoints(Long characterId, String token) {
        return executor.get("/characters/{id}/loyalty/points/", new Object[]{characterId}, token, EsiLpResponse[].class);
    }

    public EsiResponse<EsiMiningResponse[]> getMiningLedger(Long characterId, String token) {
        return executor.get("/characters/{id}/mining/", new Object[]{characterId}, token, EsiMiningResponse[].class);
    }

    public EsiResponse<EsiJournalResponse[]> getWalletJournal(Long characterId, String token) {
        return executor.get("/characters/{id}/wallet/journal/", new Object[]{characterId}, token, EsiJournalResponse[].class);
    }

    /**
     * Die Kontaktliste eines Charakters. Scope {@code esi-characters.read_contacts.v1}.
     *
     * <p>Paginiert, aber in der Praxis einseitig: CCP legt 1024 Eintraege auf
     * eine Seite. Ein Lauf kostet damit einen konditionalen Aufruf je
     * registriertem Charakter.</p>
     */
    public EsiResponse<List<EsiContactResponse>> getContacts(Long characterId, String token) {
        return executor.getAllPages("/characters/{id}/contacts/", new Object[]{characterId}, token,
                EsiContactResponse[].class);
    }

    /**
     * Die Kopfzeilen der juengsten Nachrichten. Scope {@code esi-mail.read_mail.v1}.
     *
     * <p><b>Nur die erste Seite, und das mit Absicht.</b> ESI blaettert im
     * Postfach ueber {@code last_mail_id} - wer weiterblaettern will, muss eine
     * Mail-ID kennen und weiterreichen. Genau die will diese Anwendung nicht
     * haben: mit einer Mail-ID liesse sich der Inhalt nachladen. Indem
     * {@link EsiMailHeaderResponse} die ID gar nicht erst einliest, ist das
     * Weiterblaettern nicht bloss unterlassen, sondern unmoeglich. Der Preis
     * sind die rund 50 juengsten Nachrichten statt des ganzen Postfachs; der
     * Gegenwert ist eine Zusage, die nicht von Disziplin abhaengt.</p>
     *
     * <p>Nebenbei ist genau diese eine Seite die einzige mit stabiler URL und
     * damit die einzige, die der ETag-Cache je bedienen koennte: jede
     * Folgeseite haette bei jedem Lauf eine andere {@code last_mail_id} und
     * damit garantiert einen vollen Rundlauf.</p>
     */
    public EsiResponse<EsiMailHeaderResponse[]> getMailHeaders(Long characterId, String token) {
        return executor.get("/characters/{id}/mail/", new Object[]{characterId}, token,
                EsiMailHeaderResponse[].class);
    }

    public EsiResponse<EsiTitleResponse[]> getCharacterTitles(Long characterId, String token) {
        return executor.get("/characters/{id}/titles/", new Object[]{characterId}, token, EsiTitleResponse[].class);
    }

    public EsiResponse<EsiOnlineResponse> getCharacterOnlineStatus(Long characterId, String token) {
        return executor.get("/characters/{id}/online/", new Object[]{characterId}, token, EsiOnlineResponse.class);
    }

    /**
     * Die <em>echten</em> Ingame-Corp-Rollen eines Charakters.
     *
     * <p>Scope: {@code esi-characters.read_corporation_roles.v1}. Eine
     * Ingame-Rolle braucht der Aufruf nicht - jeder darf seine eigenen Rollen
     * lesen. Genau das macht ihn wertvoll: er <em>kann</em> nicht an einer
     * fehlenden Rolle scheitern. Antwortet er mit 200 und ohne "Director", ist
     * das die belastbare Verneinung und kein Fehlerfall; antwortet er mit 403,
     * liegt es am Scope oder am falschen Charakter.</p>
     *
     * <p>Wozu es ihn hier braucht: {@code ROLE_DIRECTOR} in dieser Anwendung
     * stammt aus einem Ingame-<em>Titel</em>, nicht aus der Ingame-Rolle. Wer
     * den Titel traegt, muss die Rolle nicht haben - und ESI verlangt fuer die
     * Corp-Endpunkte die Rolle. Ohne diesen Aufruf gab es keine Moeglichkeit,
     * "traegt den Titel" von "hat die Rolle" zu unterscheiden, und jede
     * Fehlermeldung war geraten.</p>
     *
     * <p><b>Nur der eigene Charakter.</b> ESI prueft, ob die {@code character_id}
     * im Pfad zum Token gehoert, und antwortet sonst mit 403 ("Character ID
     * mismatch between request path and auth token"). Es gibt also keinen
     * Sammelaufruf: jeder Kandidat muss mit <em>seinem eigenen</em> Token
     * gefragt werden.</p>
     */
    public EsiResponse<EsiCharacterRolesResponse> getCharacterRoles(Long characterId, String token) {
        return executor.get("/characters/{id}/roles/", new Object[]{characterId}, token,
                EsiCharacterRolesResponse.class);
    }

    /** Paginierter Endpunkt: jede Seite wird einzeln per ETag geprueft. */
    public EsiResponse<List<EsiAssetResponse>> getAllAssets(Long characterId, String token) {
        return executor.getAllPages("/characters/{id}/assets/", new Object[]{characterId}, token, EsiAssetResponse[].class);
    }

    /** Maximale Anzahl item_ids, die CCP pro Namens-Request akzeptiert. */
    public static final int ASSET_NAMES_MAX_IDS = 1000;

    /**
     * Ingame vergebene Namen zusammengebauter Items (Schiffe, Container, Strukturen).
     *
     * <p>Bewusst ohne ETag-Cache: ESI liefert fuer diesen POST keine ETags. Fehler
     * werden hier nicht geschluckt, damit der Aufrufer ein 420 (Error-Limit) noch
     * an die zentrale Drosselung durchreichen kann.</p>
     *
     * <p>Die Aufteilung in Bloecke von maximal {@link #ASSET_NAMES_MAX_IDS} IDs
     * liegt beim Aufrufer.</p>
     */
    public EsiAssetNameResponse[] getAssetNames(Long characterId, String token, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return new EsiAssetNameResponse[0];
        }
        if (itemIds.size() > ASSET_NAMES_MAX_IDS) {
            throw new IllegalArgumentException(
                    "ESI akzeptiert maximal " + ASSET_NAMES_MAX_IDS + " item_ids pro Request, erhalten: " + itemIds.size());
        }
        return executor.post("/characters/{id}/assets/names/", new Object[]{characterId},
                itemIds.toArray(new Long[0]), token, EsiAssetNameResponse[].class);
    }

    // ==================================================================
    // Corporation (Token noetig)
    // ==================================================================

    public EsiResponse<EsiCorpTitleResponse[]> getCorporationTitles(Long corporationId, String token) {
        return executor.get("/corporations/{id}/titles/", new Object[]{corporationId}, token, EsiCorpTitleResponse[].class);
    }

    public EsiResponse<Long[]> getCorporationMembers(Long corporationId, String token) {
        return executor.get("/corporations/{id}/members/", new Object[]{corporationId}, token, Long[].class);
    }

    /**
     * Die Mitgliederverfolgung: je Corp-Mitglied unter anderem sein Beitrittsdatum.
     *
     * <p>Der einzige Endpunkt dieser Anwendung, der ueber einen <em>nicht</em>
     * registrierten Charakter etwas aussagt. Alles andere haengt am Token des
     * Charakters selbst - wer sich hier nie angemeldet hat, hat kein Token und
     * hinterlaesst deshalb in keiner Tabelle eine Zeile. Diese Liste kommt
     * dagegen mit dem Token eines <em>Directors</em> und deckt die ganze
     * Corporation ab, registriert oder nicht.</p>
     *
     * <p>Scope {@code esi-corporations.track_members.v1} - er steht bereits in
     * {@code EVE_SCOPES}, hier wird also nichts erweitert. Zusaetzlich verlangt
     * ESI die echte Ingame-Rolle <em>Director</em>; welcher unserer Charaktere
     * sie hat, weiss die Datenbank nicht, weshalb der Aufruf ueber
     * {@code DirectorTokenProvider} laufen muss statt ueber einen geratenen
     * Kandidaten.</p>
     *
     * <p>{@code start_date} ist das einzige Feld, das hier ausgewertet wird.
     * {@code logon_date} und {@code logoff_date} waeren das weit staerkere
     * Signal - gemeinsames Ein- und Ausloggen im Sekundenbereich ist die
     * eigentliche Signatur des Multiboxings -, aber ESI liefert davon nur den
     * <em>letzten</em> Zeitpunkt, keine Zeitreihe. Aus einem Momentanwert laesst
     * sich keine Korrelation bilden; dafuer muesste erst regelmaessig gepollt
     * und mitgeschrieben werden. Deshalb bleiben sie hier ungenutzt.</p>
     */
    public EsiResponse<EsiMemberTrackingResponse[]> getCorporationMemberTracking(Long corporationId,
                                                                                 String token) {
        return executor.get("/corporations/{id}/membertracking/", new Object[]{corporationId}, token,
                EsiMemberTrackingResponse[].class);
    }

    /**
     * Alle Corp-Bestaende. Paginierter Endpunkt, jede Seite wird per ETag geprueft.
     *
     * <p>Verlangt den Scope {@code esi-assets.read_corporation_assets.v1} <em>und</em>
     * die Ingame-Rolle Director beim Token-Charakter. Ohne die Rolle antwortet ESI
     * mit 403 - deshalb probiert der Aufrufer mehrere Kandidaten durch.</p>
     */
    public EsiResponse<List<EsiAssetResponse>> getAllCorporationAssets(Long corporationId, String token) {
        return executor.getAllPages("/corporations/{id}/assets/", new Object[]{corporationId}, token,
                EsiAssetResponse[].class);
    }

    /** Custom-Namen von Corp-Bestaenden. Gleiches 1000er-Limit wie bei Charakteren. */
    public EsiAssetNameResponse[] getCorporationAssetNames(Long corporationId, String token, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return new EsiAssetNameResponse[0];
        }
        if (itemIds.size() > ASSET_NAMES_MAX_IDS) {
            throw new IllegalArgumentException(
                    "ESI akzeptiert maximal " + ASSET_NAMES_MAX_IDS + " item_ids pro Request, erhalten: " + itemIds.size());
        }
        return executor.post("/corporations/{id}/assets/names/", new Object[]{corporationId},
                itemIds.toArray(new Long[0]), token, EsiAssetNameResponse[].class);
    }

    /** Namen der sieben Corp-Hangar-Divisionen (CorpSAG1 - CorpSAG7). */
    public EsiResponse<EsiDivisionsResponse> getCorporationDivisions(Long corporationId, String token) {
        return executor.get("/corporations/{id}/divisions/", new Object[]{corporationId}, token,
                EsiDivisionsResponse.class);
    }

    // ==================================================================
    // Fleet
    // ==================================================================

    public EsiResponse<EsiCharacterFleetResponse> getCharacterFleet(Long characterId, String token) {
        return executor.get("/characters/{id}/fleet/", new Object[]{characterId}, token, EsiCharacterFleetResponse.class);
    }

    public EsiResponse<EsiFleetMemberResponse[]> getFleetMembers(Long fleetId, String token) {
        return executor.get("/fleets/{id}/members/", new Object[]{fleetId}, token, EsiFleetMemberResponse[].class);
    }

    // ==================================================================
    // Universe
    // ==================================================================

    /**
     * Loest den Namen einer Upwell-Struktur auf.
     * Benoetigt den Scope esi-universe.read_structures.v1 UND Docking-Access
     * des Token-Charakters. Ohne Access antwortet ESI mit 403.
     */
    public EsiStructureResponse getStructureInfo(Long structureId, String token) {
        return executor.get("/universe/structures/{id}/", new Object[]{structureId}, token, EsiStructureResponse.class).data();
    }

    /**
     * Die Eckdaten einer NPC-Station.
     *
     * <p>Braucht weder Token noch Docking-Access - NPC-Stationen sind oeffentlich.
     * Noetig ist der Aufruf trotzdem: {@code /universe/names/} liefert zu einer
     * Station nur Kennung, Name und Kategorie, und das Sonnensystem steht in
     * diesem SDE-Abzug weder in {@code mapDenormalize} noch in einer
     * {@code staStations}-Tabelle. Ohne diesen Aufruf bleibt jede Station ohne
     * System - und damit unsichtbar, sobald nach dem Bausystem gefiltert wird.</p>
     */
    public EsiStationResponse getStationInfo(Long stationId) {
        return executor.get("/universe/stations/{id}/", new Object[]{stationId}, null,
                EsiStationResponse.class).data();
    }

    /**
     * Bulk-Aufloesung von IDs zu Namen.
     * ESI liefert fuer diesen POST-Endpunkt keinen ETag, deshalb ohne Cache.
     */
    public EsiIdName[] getUniverseNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new EsiIdName[0];
        }
        try {
            return executor.post("/universe/names/", ids.toArray(new Long[0]), EsiIdName[].class);
        } catch (Exception e) {
            log.warn("Bulk-Namensaufloesung fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // Industrie
    // ==================================================================

    /**
     * Die Industriejobs eines Charakters.
     *
     * <p>{@code include_completed=true} ist zwingend: ohne den Schalter liefert ESI
     * nur die laufenden Jobs, und ein Fortschritt liesse sich daraus nie ableiten -
     * fertige Jobs verschwinden dann einfach, statt gezaehlt zu werden.</p>
     *
     * <p>Nicht paginiert. Scope: {@code esi-industry.read_character_jobs.v1}.</p>
     */
    public EsiResponse<EsiIndustryJobResponse[]> getIndustryJobs(Long characterId, String token) {
        return executor.get("/characters/{id}/industry/jobs/?include_completed=true",
                new Object[]{characterId}, token, EsiIndustryJobResponse[].class);
    }

    /**
     * Die Industriejobs der Corporation.
     *
     * <p>Paginiert. Braucht neben dem Scope
     * {@code esi-industry.read_corporation_jobs.v1} auch die Ingame-Rolle
     * Factory_Manager - fehlt sie, antwortet ESI mit 403.</p>
     */
    public EsiResponse<List<EsiIndustryJobResponse>> getCorporationIndustryJobs(
            Long corporationId, String token) {
        return executor.getAllPages("/corporations/{id}/industry/jobs/?include_completed=true",
                new Object[]{corporationId}, token, EsiIndustryJobResponse[].class);
    }

    /**
     * Die Blaupausen eines Charakters.
     *
     * <p>Die einzige Quelle fuer Material- und Zeiteffizienz - in den Stammdaten
     * steht beides nicht. Paginiert. Scope:
     * {@code esi-characters.read_blueprints.v1}.</p>
     */
    public EsiResponse<List<EsiBlueprintResponse>> getCharacterBlueprints(
            Long characterId, String token) {
        return executor.getAllPages("/characters/{id}/blueprints/",
                new Object[]{characterId}, token, EsiBlueprintResponse[].class);
    }

    /**
     * Die Kostenindizes aller Systeme.
     *
     * <p>Oeffentlich, kein Token. Die einzige ortsabhaengige Groesse, die die
     * Jobgebuehr wirklich veraendert und die niemand auswendig kennt.</p>
     */
    public EsiResponse<EsiCostIndexResponse[]> getIndustrySystems() {
        return executor.get("/industry/systems/", new Object[]{}, null,
                EsiCostIndexResponse[].class);
    }

    /**
     * Die Referenzpreise von CCP.
     *
     * <p>Oeffentlich, kein Token. {@code adjusted_price} ist die Grundlage des
     * geschaetzten Warenwerts und damit der gesamten Jobgebuehr - Marktpreise
     * aus Jita sind dafuer <em>kein</em> Ersatz, es ist ein eigener, traeger
     * Referenzwert.</p>
     */
    public EsiResponse<EsiMarketPriceResponse[]> getMarketPrices() {
        return executor.get("/markets/prices/", new Object[]{}, null,
                EsiMarketPriceResponse[].class);
    }

    /**
     * Die Strukturen der eigenen Corporation - samt ihrer Dienste.
     *
     * <p>Der einzige Endpunkt, der verlaesslich sagt, ob in einer Struktur
     * ueberhaupt gefertigt werden kann. Fuer fremde Strukturen gibt es das nicht;
     * dort bleibt nur die ehrliche Auskunft "Dienste unbekannt".</p>
     *
     * <p>Paginiert. Braucht die Ingame-Rolle Station_Manager.</p>
     */
    public EsiResponse<List<EsiCorpStructureResponse>> getCorporationStructures(
            Long corporationId, String token) {
        return executor.getAllPages("/corporations/{id}/structures/",
                new Object[]{corporationId}, token, EsiCorpStructureResponse[].class);
    }

    // ==================================================================
    // Marktorders
    // ==================================================================

    /**
     * Eine Seite des oeffentlichen Orderbuchs einer Region.
     *
     * <p><b>Kein Token, kein Scope.</b> Der Endpunkt traegt in der OpenAPI-Spez
     * keinen {@code security}-Knoten, und die Spez hat auch keine globale
     * Vorgabe. Selbst nachgeprueft: ein Aufruf ganz ohne
     * {@code Authorization}-Kopfzeile antwortet mit 200. An
     * {@code eve.sso.scopes} ist deshalb nichts zu aendern.</p>
     *
     * <p>{@code order_type=all} liefert Kauf- und Verkaufsorders in einem Zug.
     * Gemessen am 26.08.: {@code all} = 411 Seiten, {@code sell} = 279,
     * {@code buy} = 132 - 279 + 132 = 411, es kostet also nichts, beide Seiten
     * zusammen zu holen. Der Beschreibungstext der Spez behauptet, ohne
     * {@code type_id} kaemen ohnehin immer beide Richtungen; das ist nachweislich
     * falsch, der Filter greift sehr wohl. Deshalb steht der Parameter hier
     * ausdruecklich.</p>
     *
     * <p>Bewusst ohne den ETag-Cache - die Begruendung steht bei
     * {@link EsiRequestExecutor#getUncachedPage}.</p>
     */
    public EsiRequestExecutor.UncachedPage<EsiMarketOrder> getMarketOrdersPage(long regionId, int page) {
        return executor.getUncachedPage("/markets/{region}/orders/?order_type=all&page={page}",
                new Object[]{regionId, page}, EsiMarketOrder[].class);
    }

    // ==================================================================
    // Antworttypen
    // ==================================================================

    /**
     * Ein Industriejob, wie ESI ihn meldet.
     *
     * <p>Achtung bei {@code activity_id}: ESI zaehlt Reaktionen als 9, die
     * Stammdaten als 11. Die Uebersetzung liegt in
     * {@code com.eve.own.auth.backend.domain.industry.IndustryActivity}.</p>
     *
     * <p>{@code successful_runs} steht erst nach der Lieferung fest und ist bis
     * dahin {@code null} - deshalb der Wrapper-Typ.</p>
     */
    public record EsiIndustryJobResponse(
            Long job_id, Long installer_id, Long facility_id, Long station_id,
            Long blueprint_id, Long blueprint_type_id, Long blueprint_location_id,
            Long output_location_id, Long product_type_id,
            Integer activity_id, Integer runs, Integer licensed_runs, Integer successful_runs,
            Double cost, Double probability, String status,
            Instant start_date, Instant end_date, Instant pause_date, Instant completed_date,
            Long completed_character_id) {}

    /**
     * Eine Blaupause im Besitz eines Charakters.
     *
     * <p>{@code runs} ist -1 bei einem Original. Genau daran wird die Kopie
     * erkannt - <em>nicht</em> an {@code quantity}: ein frisch gekaufter Stapel
     * Originale hat eine positive Stueckzahl.</p>
     */
    public record EsiBlueprintResponse(
            Long item_id, Long type_id, Long location_id, String location_flag,
            Integer quantity, Integer runs,
            Integer material_efficiency, Integer time_efficiency) {}

    /** Die Kostenindizes eines Systems, je Aktivitaet einer. */
    public record EsiCostIndexResponse(Long solar_system_id, List<EsiCostIndexEntry> cost_indices) {}

    /** {@code activity} ist ein Text wie "manufacturing" oder "reaction". */
    public record EsiCostIndexEntry(String activity, Double cost_index) {}

    /** Referenzpreise von CCP. Beide Felder koennen fehlen. */
    public record EsiMarketPriceResponse(Long type_id, Double adjusted_price, Double average_price) {}

    /**
     * Eine Struktur der eigenen Corporation.
     *
     * <p>{@code services} traegt die Namen der laufenden Dienste, etwa
     * "manufacturing" oder "reprocessing" - daran haengt die Aussage, ob sich
     * dort ueberhaupt bauen laesst.</p>
     */
    public record EsiCorpStructureResponse(
            Long structure_id, Long type_id, Long system_id, Long corporation_id,
            Long profile_id, String state, Instant fuel_expires,
            List<EsiStructureService> services) {}

    public record EsiStructureService(String name, String state) {}

    /**
     * Eine einzelne Order aus dem oeffentlichen Orderbuch.
     *
     * <p>Alle zwoelf Felder sind in der Spez {@code required}; sie stehen hier
     * vollstaendig, damit kein Feld als "unbekannt" durchlaufen muss.</p>
     *
     * <p><b>{@code location_id} muss {@code Long} sein.</b> Die Spez nennt
     * int64, und das wird gebraucht: in The Forge kam
     * {@code 1044752365771} vor - eine Spielerstruktur. Ein {@code Integer}
     * bricht dort.</p>
     *
     * <p><b>{@code range} ist ein String, keine Zahl.</b> Das Enum mischt
     * Woerter und Ziffern: {@code station}, {@code region},
     * {@code solarsystem}, {@code 1} bis {@code 40}.</p>
     */
    public record EsiMarketOrder(
            Long order_id, Long type_id, Long location_id, Long system_id,
            Long volume_total, Long volume_remain, Long min_volume, Long duration,
            Double price, Boolean is_buy_order, String range, Instant issued) {}

    public record EsiIdName(Long id, String name, String category) {}
    public record EsiOnlineResponse(Boolean online, String last_login, String last_logout, Integer logins) {}
    public record EsiCharacterFleetResponse(Long fleet_id, Long character_id, String role) {}
    public record EsiFleetMemberResponse(Long character_id, Instant join_time, String role, Long ship_type_id, Long solar_system_id) {}
    /**
     * Die Ingame-Corp-Rollen eines Charakters.
     *
     * <p>Alle vier Felder sind laut ESI-Definition optional - die Antwort eines
     * Charakters ohne jede Rolle ist schlicht {@code {}}. Deshalb muss jedes
     * Feld {@code null} sein duerfen.</p>
     *
     * <p>Fuer die Frage "ist das ein Director" zaehlt allein {@code roles}. Die
     * drei Felder mit Ortsbezug tragen Hangar- und Kontorechte an einem
     * bestimmten Ort, nicht die corpweite Rolle.</p>
     */
    public record EsiCharacterRolesResponse(String[] roles, String[] roles_at_hq,
                                            String[] roles_at_base, String[] roles_at_other) {

        /**
         * Ob die corpweite Rollenliste diese Rolle enthaelt.
         *
         * <p>Vergleich exakt und ohne Ignorieren der Gross-/Kleinschreibung: die
         * Bezeichner sind ein festes Enum von CCP. Der Director heisst dort
         * {@code "Director"} - einteilig, ohne Unterstrich, anders als die
         * meisten Nachbarn ({@code Personnel_Manager}, {@code Station_Manager}).</p>
         */
        public boolean hasCorporationRole(String roleName) {
            if (roles == null) {
                return false;
            }
            for (String role : roles) {
                if (roleName.equals(role)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Eine Zeile der Mitgliederverfolgung.
     *
     * <p>Nur {@code character_id} ist laut Spezifikation zugesichert; jedes andere
     * Feld darf fehlen. {@code start_date} ist deshalb {@code Instant} und nicht
     * {@code long} - ein fehlendes Beitrittsdatum muss als <em>fehlend</em>
     * ankommen und nicht als Zeitpunkt null, sonst saehen alle Mitglieder ohne
     * Datum wie am selben Tag beigetreten aus.</p>
     */
    public record EsiMemberTrackingResponse(Long character_id, Instant start_date,
                                            Instant logon_date, Instant logoff_date,
                                            Long location_id, Long ship_type_id, Long base_id) {}

    public record EsiCorpTitleResponse(Long title_id, String name) {}
    public record EsiTitleResponse(Long title_id, String name) {}
    public record EsiMiningResponse(String date, Long quantity, Long solar_system_id, Long type_id) {}
    public record EsiJournalResponse(Long id, String date, String ref_type, Double amount, Long second_party_id, String reason) {}

    /**
     * Ein Eintrag der Kontaktliste.
     *
     * <p>{@code standing} ist {@code Float} und nicht {@code float}: ein
     * fehlender Wert muss als fehlend ankommen. Als primitiver Typ waere er 0
     * und damit von "bewusst neutral gesetzt" nicht mehr zu unterscheiden.</p>
     */
    public record EsiContactResponse(Long contact_id, String contact_type, Float standing,
                                     Boolean is_blocked, Boolean is_watched) {}

    /**
     * Die Kopfzeile einer Nachricht - <b>ohne Betreff und ohne Mail-ID</b>.
     *
     * <p>ESI liefert an dieser Stelle sehr wohl {@code subject}, {@code mail_id},
     * {@code labels} und {@code is_read}. Dass sie hier fehlen, ist die
     * eigentliche Aussage dieses Typs: was nicht als Komponente steht, wird von
     * Jackson verworfen und existiert im Prozess nie als Wert - es kann also
     * auch nicht versehentlich protokolliert, weitergereicht oder gespeichert
     * werden. Ohne {@code mail_id} gibt es zudem keinen Schluessel, mit dem sich
     * der Text nachladen liesse.</p>
     *
     * <p>Die Zusage steht bei {@code CharacterMailCount}; {@code MailPrivacyTest}
     * laesst jeden Versuch scheitern, hier ein Feld nachzuruesten.</p>
     */
    public record EsiMailHeaderResponse(Long from, EsiMailRecipient[] recipients, Instant timestamp) {}

    /** Ein Empfaenger einer Nachricht. {@code recipient_type}: character, corporation, alliance, mailing_list. */
    public record EsiMailRecipient(Long recipient_id, String recipient_type) {}
    public record EsiLpResponse(Long corporation_id, Integer loyalty_points) {}
    public record SkillResponse(Long total_sp, Integer unallocated_sp, EsiSkillEntry[] skills) {}

    public record EsiSkillEntry(Long skill_id, Integer active_skill_level,
                                Integer trained_skill_level, Long skillpoints_in_skill) {}
    public record EsiAssetResponse(Long item_id, Long type_id, Long location_id, Integer quantity,
                                   Boolean is_singleton, String location_flag, String location_type,
                                   Boolean is_blueprint_copy) {}
    public record EsiAssetNameResponse(Long item_id, String name) {}
    public record EsiDivisionsResponse(EsiDivision[] hangar, EsiDivision[] wallet) {}
    public record EsiDivision(Integer division, String name) {}
    public record EsiStructureResponse(String name, Long owner_id, Long solar_system_id, Long type_id) {}
    public record EsiStationResponse(String name, Long system_id, Long type_id, Long owner) {}
    public record EsiCharacterResponse(String name, Long corporation_id) {}
    public record EsiCorporationResponse(String name, String ticker, Long alliance_id, Long faction_id) {}
    public record EsiAllianceResponse(String name, String ticker) {}
}

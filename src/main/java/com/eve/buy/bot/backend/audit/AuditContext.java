package com.eve.buy.bot.backend.audit;

import lombok.Getter;
import lombok.Setter;

/**
 * Hält die Angaben zum laufenden Aufruf für die Dauer eines Requests.
 *
 * <p>Damit müssen IP-Adresse, Aufruf-ID und Auslöser nicht durch alle Methodensignaturen
 * gereicht werden. Der Inhalt hängt am Bearbeitungsthread und wird am Ende des Requests
 * wieder freigegeben.
 */
public final class AuditContext {

    private static final ThreadLocal<Data> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    /** Die Angaben eines einzelnen Aufrufs. */
    @Getter
    @Setter
    public static final class Data {
        private String requestId;
        private String clientIp;
        private String userAgent;
        private String httpMethod;
        private String path;
        private Long actorCharacterId;
        private String actorName;
    }

    /**
     * Beginnt einen neuen Aufruf und ersetzt einen eventuell vorhandenen Kontext.
     *
     * @param requestId  eindeutige Kennung des Aufrufs
     * @param clientIp   IP-Adresse des Aufrufers
     * @param userAgent  gemeldeter Browser oder Client
     * @param httpMethod HTTP-Methode
     * @param path       aufgerufener Pfad
     * @return der neu angelegte Kontext
     */
    public static Data start(String requestId, String clientIp, String userAgent, String httpMethod, String path) {
        Data data = new Data();
        data.setRequestId(requestId);
        data.setClientIp(clientIp);
        data.setUserAgent(userAgent);
        data.setHttpMethod(httpMethod);
        data.setPath(path);
        CURRENT.set(data);
        return data;
    }

    /**
     * Hinterlegt den angemeldeten Charakter, sobald die Authentifizierung ihn kennt.
     *
     * @param characterId EVE-Charakter-ID
     * @param name        Anzeigename des Charakters
     */
    public static void setActor(Long characterId, String name) {
        Data data = CURRENT.get();
        if (data != null) {
            data.setActorCharacterId(characterId);
            data.setActorName(name);
        }
    }

    /**
     * Gibt den Kontext des laufenden Aufrufs zurück.
     *
     * @return der Kontext oder {@code null}, wenn gerade kein Request läuft (etwa im Scheduler)
     */
    public static Data current() {
        return CURRENT.get();
    }

    /**
     * Liefert die Aufruf-ID des laufenden Requests.
     *
     * @return die Aufruf-ID oder {@code null} außerhalb eines Requests
     */
    public static String currentRequestId() {
        Data data = CURRENT.get();
        return data == null ? null : data.getRequestId();
    }

    /** Gibt den Kontext frei; muss am Ende jedes Requests aufgerufen werden. */
    public static void clear() {
        CURRENT.remove();
    }
}

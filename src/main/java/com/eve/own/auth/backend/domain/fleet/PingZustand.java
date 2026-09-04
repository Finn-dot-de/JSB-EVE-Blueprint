package com.eve.own.auth.backend.domain.fleet;

/**
 * Der Lebenslauf eines Flotten-Pings.
 *
 * <p>Es gibt bewusst keinen Zustand "geloescht". Ein Ping ist eine oeffentliche
 * Aussage; wer sie zuruecknimmt, sagt das im selben Kanal. Ein spurlos
 * verschwundener Ping liesse die Frage "wer hat das gepingt" unbeantwortet -
 * und genau dafuer gibt es die Liste.</p>
 */
public enum PingZustand {

    /** Abgesetzt und unveraendert. */
    GEPOSTET,

    /**
     * Nachtraeglich geaendert - Treffpunkt verschoben, Doktrin gewechselt.
     *
     * <p>Discord traegt die Aenderung an derselben Stelle nach, statt eine
     * zweite Nachricht danebenzusetzen: Zwei widerspruechliche Pings im Kanal
     * sind schlimmer als ein falscher, weil niemand weiss, welcher gilt.</p>
     */
    GEAENDERT,

    /**
     * Abgesagt. Die Flotte findet nicht statt.
     *
     * <p>Endzustand: ein abgesagter Ping laesst sich nicht wieder aufwecken.
     * Wer doch fliegt, pingt neu - dann sieht auch jeder, dass es eine neue
     * Ankuendigung ist und nicht eine wiederbelebte alte.</p>
     */
    ABGESAGT
}

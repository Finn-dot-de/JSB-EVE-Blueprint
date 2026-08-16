package com.eve.buy.bot.backend.audit;

/** Fachliche Einordnung eines Protokolleintrags. */
public enum AuditCategory {

    /** Aufruf einer API-Schnittstelle. */
    REQUEST,

    /** Preisanfrage eines Spielers. */
    QUOTE,

    /** Änderung im Admin-Bereich. */
    ADMIN,

    /** Anmeldung, Abmeldung oder abgelehnter Zugriff. */
    SECURITY,

    /** Lauf der Vertragsprüfung. */
    CONTRACT_CHECK,

    /** Versand einer Benachrichtigung. */
    NOTIFICATION,

    /** Unbehandelter Fehler. */
    ERROR
}

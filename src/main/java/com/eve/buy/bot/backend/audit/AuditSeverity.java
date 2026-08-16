package com.eve.buy.bot.backend.audit;

/** Schweregrad eines Protokolleintrags. */
public enum AuditSeverity {

    /** Normaler Betrieb. */
    INFO,

    /** Auffällig, aber kein Ausfall. */
    WARN,

    /** Fehlgeschlagen, muss angesehen werden. */
    ERROR
}

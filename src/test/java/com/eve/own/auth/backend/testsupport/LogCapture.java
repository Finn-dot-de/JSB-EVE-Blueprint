package com.eve.own.auth.backend.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Faengt die Protokollzeilen einer Klasse ab.
 *
 * <p>Fuer die meisten Aussagen ist das Protokoll uninteressant. Hier nicht: der
 * Ausfall, um den es in diesem Teil des Projekts geht, war ausschliesslich
 * daran zu erkennen, <em>wie</em> er gemeldet wurde - "2165 Typen gespeichert,
 * 0 Batches fehlgeschlagen" stand als INFO im Protokoll, waehrend die Tabelle
 * mit Nullen volllief. Eine Erfolgsmeldung, die einen Totalausfall beschreibt,
 * ist ein Fehler wie jeder andere und gehoert getestet.</p>
 *
 * <p>Setzt den Pegel der beobachteten Klasse fuer die Dauer der Beobachtung auf
 * TRACE und stellt ihn danach wieder her - sonst haengt das Ergebnis an der
 * Logback-Konfiguration der Umgebung.</p>
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level vorherigerPegel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> beobachtet) {
        this.logger = (Logger) LoggerFactory.getLogger(beobachtet);
        this.vorherigerPegel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    /** Alle Meldungen einer Stufe, bereits mit eingesetzten Platzhaltern. */
    public List<String> meldungen(Level stufe) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == stufe)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(vorherigerPegel);
    }
}

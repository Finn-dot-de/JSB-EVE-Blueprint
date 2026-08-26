package com.eve.own.auth.backend.domain.market;

import java.time.Instant;
import java.util.Map;

/**
 * Das Ergebnis <em>eines</em> vollstaendigen Marktabzugs.
 *
 * <p><b>Warum es dieses Objekt gibt.</b> Bis hierher holten sich der
 * Asset-Preislauf, der Industrie-Preislauf und der Steuersatz-Abgleich ihre
 * Preise jeweils selbst - drei Dienste, die nichts voneinander wussten, 40
 * Abrufe je Stunde. Beim Regionsabzug waeren daraus 411 Seiten <em>je
 * Verbraucher</em> geworden, also 1.233 statt 411 Anfragen und rund
 * eineinhalb Stunden Netzarbeit je Stunde Betrieb. Genau diesen Fehler - jeder
 * holt sich selbst, was schon jemand geholt hat - hat das Projekt beim
 * Discord-Abgleich schon einmal gemacht.</p>
 *
 * <p>Deshalb ist der Abzug ein <em>Wert</em> und kein Dienst: er wird einmal
 * gebildet und herumgereicht. Ein Verbraucher kann ihn nicht versehentlich neu
 * anfordern, weil es dafuer keine Methode gibt.</p>
 *
 * <p><b>Unvollstaendig gibt es nicht.</b> Ein Abzug entsteht nur, wenn alle
 * Seiten da waren. Bricht er mittendrin ab, wird keiner gebaut - ein halber
 * Markt saehe wie ein Markt aus, in dem tausende Typen ueber Nacht ihr Angebot
 * verloren haben, und wuerde brauchbare Preise durch Luecken ersetzen.</p>
 *
 * @param prices  je Typ der Preis an der Zielstation; ein Typ ohne Order fehlt
 * @param station die Station, auf die gefiltert wurde
 * @param pulledAt wann der Abzug fertig war
 */
public record MarketSnapshot(Map<Long, StationPrice> prices, long station, Instant pulledAt) {

    /**
     * Der Preis eines Typs, oder {@code null} wenn er an der Station keine Order hat.
     *
     * <p>{@code null} ist hier die richtige Antwort und nicht eine Notloesung:
     * gemessen haben 488 der 17.373 gehandelten Typen zwar in der Region ein
     * Angebot, aber keines an Jita 4-4. Auf das Regionsminimum auszuweichen
     * waere genau der stille Fehler, um den es geht - der Preis saehe brauchbar
     * aus und waere an dem Ort, um den gefragt wurde, nicht zu bekommen.</p>
     */
    public StationPrice price(Long typeId) {
        return typeId == null ? null : prices.get(typeId);
    }

    /** Wie viele Typen einen brauchbaren Preis tragen. */
    public int size() {
        return prices.size();
    }
}

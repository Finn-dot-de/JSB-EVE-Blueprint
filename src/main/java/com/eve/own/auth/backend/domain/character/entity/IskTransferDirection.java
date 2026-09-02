package com.eve.own.auth.backend.domain.character.entity;

/**
 * Aus wessen Sicht eine festgehaltene Ueberweisung gelesen werden muss.
 *
 * <p>Die Richtung ist kein Beiwerk, sie ist der halbe Wert des Signals. "Main X
 * zahlt regelmaessig an Y" und "Y zahlt an X" sind zwei verschiedene Aussagen:
 * die erste passt zum Bild eines Kontos, das seinen Alt versorgt, die zweite
 * ebenso zu einem Kaeufer, der eine Rechnung begleicht. Wer nur den Betrag
 * speichert, kann die beiden spaeter nicht mehr auseinanderhalten.</p>
 *
 * <p>Die Sicht ist immer die des <em>registrierten</em> Charakters - nur der hat
 * ein Token, und nur sein Journal wird gelesen.</p>
 */
public enum IskTransferDirection {

    /** Der registrierte Charakter hat gezahlt (negativer Betrag im Journal). */
    OUTGOING,

    /** Der registrierte Charakter hat empfangen (positiver Betrag im Journal). */
    INCOMING
}

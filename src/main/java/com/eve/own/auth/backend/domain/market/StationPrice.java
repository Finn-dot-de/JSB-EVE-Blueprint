package com.eve.own.auth.backend.domain.market;

/**
 * Was ein Typ an <em>einer</em> Station kostet.
 *
 * <p>Beide Seiten duerfen fehlen, aber keine darf 0 sein. Ein {@code null}
 * heisst "an dieser Station bietet gerade niemand" - und das ist etwas anderes
 * als "kostet nichts". Dass diese Unterscheidung verlorenging, war die Ursache
 * der 6.698 Nullzeilen in {@code market_prices}; sie steht deshalb schon im Typ
 * und nicht erst in der Auswertung.</p>
 *
 * <p>Ein {@code StationPrice} mit zwei {@code null} wird gar nicht erst gebaut -
 * {@link MarketSnapshot} kennt einen Typ ohne Order schlicht nicht.</p>
 *
 * @param buy  hoechstes Kaufgebot an der Station, oder {@code null}
 * @param sell guenstigstes Verkaufsangebot an der Station, oder {@code null}
 */
public record StationPrice(Double buy, Double sell) {
}

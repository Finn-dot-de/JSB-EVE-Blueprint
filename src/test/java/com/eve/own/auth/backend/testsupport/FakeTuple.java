package com.eve.own.auth.backend.testsupport;

import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eine Ergebniszeile, wie sie eine native Query liefert.
 *
 * <p>Die Auswertungsdienste lesen ihre Zeilen ueber Spaltenaliasse aus einem
 * {@link Tuple}. Statt fuer jeden Test ein Mock mit einem Dutzend
 * {@code when(...)}-Zeilen aufzubauen, beschreibt diese Klasse die Zeile als
 * das, was sie ist: eine Zuordnung von Alias zu Wert.</p>
 *
 * <p>Nur die tatsaechlich genutzten Methoden sind ausgefuellt - der Rest der
 * Schnittstelle wird von den Diensten nicht angefasst.</p>
 */
public final class FakeTuple implements Tuple {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private FakeTuple() {
    }

    /** Baut eine Zeile aus abwechselnd Alias und Wert. */
    public static FakeTuple of(Object... aliasesAndValues) {
        if (aliasesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Alias und Wert muessen paarweise angegeben werden.");
        }
        FakeTuple tuple = new FakeTuple();
        for (int i = 0; i < aliasesAndValues.length; i += 2) {
            tuple.values.put(String.valueOf(aliasesAndValues[i]), aliasesAndValues[i + 1]);
        }
        return tuple;
    }

    @Override
    public Object get(String alias) {
        if (!values.containsKey(alias)) {
            // Genau dieses Verhalten faengt der Lesecode der Dienste ab.
            throw new IllegalArgumentException("Unbekannter Alias: " + alias);
        }
        return values.get(alias);
    }

    @Override
    public <X> X get(String alias, Class<X> type) {
        return type.cast(get(alias));
    }

    @Override
    public Object get(int i) {
        return values.values().stream().skip(i).findFirst().orElseThrow();
    }

    @Override
    public <X> X get(int i, Class<X> type) {
        return type.cast(get(i));
    }

    @Override
    public Object[] toArray() {
        return values.values().toArray();
    }

    @Override
    public <X> X get(TupleElement<X> tupleElement) {
        throw new UnsupportedOperationException("Von den Diensten nicht genutzt.");
    }

    @Override
    public List<TupleElement<?>> getElements() {
        throw new UnsupportedOperationException("Von den Diensten nicht genutzt.");
    }
}

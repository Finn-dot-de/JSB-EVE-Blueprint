package com.eve.own.auth.backend.domain.fleet.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Die Abfragen des Readiness-Boards greifen quer ueber Bestaende, Skills und
 * SDE. Geprueft wird, dass sie die richtigen Werte gebunden bekommen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Abfragen der Doktrin-Bereitschaft")
class ReadinessQueryRepositoryTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private ReadinessQueryRepository repository;

    private final List<String> executedSql = new ArrayList<>();
    private final Map<String, Object> boundParameters = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        repository = new ReadinessQueryRepository();
        Field em = ReadinessQueryRepository.class.getDeclaredField("em");
        em.setAccessible(true);
        em.set(repository, entityManager);

        when(entityManager.createNativeQuery(anyString(), eq(Tuple.class))).thenAnswer(call -> {
            executedSql.add(call.getArgument(0));
            return query;
        });
        when(entityManager.createNativeQuery(anyString())).thenAnswer(call -> {
            executedSql.add(call.getArgument(0));
            return query;
        });
        when(query.setParameter(anyString(), any())).thenAnswer(call -> {
            boundParameters.put(call.getArgument(0), call.getArgument(1));
            return query;
        });
        when(query.getResultList()).thenReturn(List.of());
    }

    @Test
    @DisplayName("liest die Charaktere aller Accounts ohne Parameter")
    void readsAccountRoster() {
        assertThat(repository.accountRoster()).isEmpty();

        assertThat(executedSql).hasSize(1);
        assertThat(boundParameters).isEmpty();
    }

    @Test
    @DisplayName("fragt die Huellen-Bestaende zu einer Typenliste ab")
    void queriesHullOwnership() {
        repository.hullOwnership(List.of(33472L, 11987L));

        assertThat(boundParameters).containsEntry("typeIds", List.of(33472L, 11987L));
    }

    @Test
    @DisplayName("fragt die Skill-Anforderungen zu einer Typenliste ab")
    void queriesSkillRequirements() {
        repository.skillRequirements(List.of(33472L));

        assertThat(boundParameters).containsEntry("typeIds", List.of(33472L));
    }

    @Test
    @DisplayName("fragt die Skill-Luecken zu einer Typenliste ab")
    void queriesSkillGaps() {
        repository.skillGaps(List.of(33472L));

        assertThat(boundParameters).containsEntry("typeIds", List.of(33472L));
    }

    @Test
    @DisplayName("liest die Charaktere mit gespiegelten Skill-Daten")
    void readsCharactersWithSkillData() {
        assertThat(repository.charactersWithSkillData()).isEmpty();

        assertThat(executedSql).hasSize(1);
    }

    @Test
    @DisplayName("loest Typnamen in Kleinschreibung auf")
    void resolvesTypesByName() {
        repository.resolveTypesByName(List.of("nestor", "guardian"));

        assertThat(boundParameters).containsEntry("names", List.of("nestor", "guardian"));
    }

    @Test
    @DisplayName("gibt fuer eine leere Typenliste sofort nichts zurueck")
    void shortCircuitsOnEmptyInput() {
        // Ein leeres IN () waere in SQL ein Syntaxfehler.
        assertThat(repository.hullOwnership(List.of())).isEmpty();
        assertThat(repository.skillRequirements(List.of())).isEmpty();
        assertThat(repository.skillGaps(List.of())).isEmpty();
        assertThat(repository.resolveTypesByName(List.of())).isEmpty();

        assertThat(executedSql).isEmpty();
    }
}

package com.eve.buy.bot.backend.domain.buybot.repository;

import com.eve.buy.bot.backend.domain.buybot.entity.ContractCheck;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Persistenz der geprueften Vertraege. */
@Repository
public interface ContractCheckRepository extends JpaRepository<ContractCheck, Long> {

    List<ContractCheck> findAllByOrderByIssuedAtDesc(Limit limit);

    /** Verträge, deren Meldung noch aussteht - werden bei jedem Lauf erneut versucht. */
    long countByNotifiedFalse();
}

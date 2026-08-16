package com.eve.own.auth.backend.esi.etag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface EsiEtagRepository extends JpaRepository<EsiEtag, String> {

    long deleteByLastCheckedAtBefore(Instant threshold);

    long countByLastChangedAtAfter(Instant threshold);
}

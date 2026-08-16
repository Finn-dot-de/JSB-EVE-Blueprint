package com.eve.buy.bot.backend.domain.eve.repository;

import com.eve.buy.bot.backend.domain.eve.entity.InvType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Lesezugriff auf die Item-Gruppen der EVE-Statikdatenbank. */
@Repository
public interface InvGroupRepository extends JpaRepository<InvType, Long> {

}

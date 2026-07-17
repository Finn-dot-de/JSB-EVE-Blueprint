package com.eve.own.auth.backend.domain.discord.repository;

import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscordConnectionRepository extends JpaRepository<DiscordConnection, Long> {
}
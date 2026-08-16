package com.eve.buy.bot.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Schaltet die zeitgesteuerten Aufgaben ein: Vertragspruefung, Rollen-Sync, Protokoll-Aufraeumen. */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
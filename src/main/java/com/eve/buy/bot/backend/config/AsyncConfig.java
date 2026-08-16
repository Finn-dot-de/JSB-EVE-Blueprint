package com.eve.buy.bot.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Stellt den Thread-Pool bereit, auf dem Protokolleinträge geschrieben werden. */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor für das Audit-Protokoll.
     *
     * <p>Die Warteschlange ist begrenzt, damit ein Ansturm nicht den Speicher füllt. Läuft
     * sie voll, schreibt der aufrufende Thread den Eintrag selbst: langsamer, aber es geht
     * kein Protokolleintrag verloren.
     *
     * @return der konfigurierte Executor
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}

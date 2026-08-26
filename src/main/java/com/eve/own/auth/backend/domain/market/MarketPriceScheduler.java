package com.eve.own.auth.backend.domain.market;

import com.eve.own.auth.backend.domain.assets.scheduler.AssetPriceScheduler;
import com.eve.own.auth.backend.domain.industry.service.IndustrySyncService;
import com.eve.own.auth.backend.domain.mining.service.MiningPriceService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ein Marktabzug, drei Verbraucher.
 *
 * <p><b>Warum die Verbraucher nicht mehr selbst holen.</b> Bis hierher hatten
 * der Asset-Preislauf, der Industrie-Preislauf und der Steuersatz-Abgleich je
 * einen eigenen {@code @Scheduled} und je einen eigenen Weg ans Netz - drei
 * Dienste, die nichts voneinander wussten und stuendlich zusammen 40 Abrufe
 * machten. Mit dem Regionsabzug waere daraus 3 x 411 = 1.233 Anfragen
 * geworden, wo 411 genuegen: dieselben Seiten, dreimal geholt. Diesen Fehler -
 * jeder holt sich selbst, was schon jemand geholt hat - hat das Projekt beim
 * Discord-Abgleich schon einmal gemacht.</p>
 *
 * <p>Deshalb ist die Reihenfolge hier ausdruecklich festgelegt und nicht dem
 * Zufall dreier Zeitgeber ueberlassen: erst holen, dann verteilen. Der Abzug
 * ist ein Wert, den alle drei bekommen; keiner kann ihn erneut anfordern.</p>
 *
 * <p><b>Takt.</b> Stuendlich, und zwar {@code fixedDelay} statt
 * {@code fixedRate}: ein echter Durchlauf brauchte gemessen 110 Sekunden fuer
 * 411 Seiten und 410.753 Orders - bei einem langsamen ESI kann daraus ein
 * Vielfaches werden, und dann startet mit {@code fixedRate} der naechste Lauf,
 * waehrend der vorige noch laeuft.
 * Der {@link AtomicBoolean} sichert denselben Fall gegen einen von Hand
 * angestossenen Lauf ab. Haeufiger als stuendlich waere ohnehin sinnlos: ESI
 * puffert diesen Endpunkt 300 Sekunden ({@code Expires} minus
 * {@code Last-Modified}, in jeder Stichprobe exakt 300), und CCP sagt zum
 * Umgehen des Caches woertlich, dass es zur Sperre fuehren kann.</p>
 */
@Slf4j
@Component
public class MarketPriceScheduler {

    /** Als Literal, weil {@code @Scheduled} einen Konstantenausdruck verlangt. */
    private static final long ONE_HOUR = 60 * 60 * 1000L;

    /** Zwei Minuten Vorlauf, damit der Start nicht mit dem Abzug um Verbindungen ringt. */
    private static final long INITIAL_DELAY = 120_000L;

    private final AtomicBoolean laeuft = new AtomicBoolean(false);

    private final MarketSnapshotService snapshotService;
    private final AssetPriceScheduler assetPrices;
    private final IndustrySyncService industrySync;
    private final MiningPriceService miningPrices;

    public MarketPriceScheduler(MarketSnapshotService snapshotService,
                                AssetPriceScheduler assetPrices,
                                IndustrySyncService industrySync,
                                MiningPriceService miningPrices) {
        this.snapshotService = snapshotService;
        this.assetPrices = assetPrices;
        this.industrySync = industrySync;
        this.miningPrices = miningPrices;
    }

    @Scheduled(fixedDelay = ONE_HOUR, initialDelay = INITIAL_DELAY)
    public void refreshMarketPrices() {
        if (!laeuft.compareAndSet(false, true)) {
            log.warn("Marktabzug laeuft noch - dieser Lauf entfaellt.");
            return;
        }
        try {
            // Erst der Abzug. Kommt er nicht zustande, wird unten NICHTS
            // geschrieben: die alten Preise bleiben stehen, und das ist der
            // ganze Sinn der Uebung.
            MarketSnapshot abzug = snapshotService.pull();

            int assetTypen = assetPrices.updateAssetPrices(abzug);
            int industrieTypen = industrySync.syncIndustryPrices(abzug);
            miningPrices.refreshJitaPrices(abzug);

            log.info("Preisabgleich fertig: {} brauchbare Preise im Abzug, davon {} bei Asset-Typen "
                            + "und {} bei Industrie-Typen verwendet.",
                    abzug.size(), assetTypen, industrieTypen);
        } catch (MarketSnapshotUnavailableException e) {
            // Der geordnete Abbruch. Kein error(): die Anwendung ist heil, nur
            // die Quelle nicht - aber eine info() waere gelogen.
            log.warn("Preisabgleich uebersprungen, alle Preise bleiben unveraendert: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Preisabgleich fehlgeschlagen: {}", e.getMessage(), e);
        } finally {
            laeuft.set(false);
        }
    }
}

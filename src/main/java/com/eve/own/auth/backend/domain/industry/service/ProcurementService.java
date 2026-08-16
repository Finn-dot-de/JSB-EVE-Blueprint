package com.eve.own.auth.backend.domain.industry.service;

import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.OreSource;
import com.eve.own.auth.backend.domain.industry.service.LogisticsMath.Transport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Beantwortet die Frage "was kaufe ich wo, und was kostet mich das".
 *
 * <p>Der Kern ist ein Vergleich, den man von Hand kaum anstellt: ein Mineral
 * direkt zu kaufen ist fast immer teurer als das Erz - aber nur, wenn man den
 * Transport mitrechnet, wird der Abstand deutlich. Fuer 5,2 Millionen Tritanium
 * kostet die Ware 20,6 Millionen ISK und der Sprungfrachter 23,9 Millionen;
 * dasselbe als komprimiertes Veldspar kostet 17,9 Millionen Ware und 748.000
 * Transport. Der Weg entscheidet, nicht der Preis.</p>
 *
 * <h2>Nebenprodukte</h2>
 * <p>Erze liefern beim Aufbereiten mehrere Minerale gleichzeitig. Wer Scordite
 * fuer Pyerit kauft, bekommt Tritanium dazu. Die einzelne Zeile rechnet das
 * weiterhin nicht gegen - sie faellt dadurch eher zu teuer aus als zu billig,
 * und das ist die sichere Richtung.</p>
 *
 * <p>Das <b>Urteil ueber den Erzweg</b> tut es dagegen sehr wohl: es rechnet
 * ueber alle offenen Mineralien zugleich und schreibt jedes Nebenprodukt gut -
 * gedeckelt auf den tatsaechlichen Bedarf. Ohne diesen Deckel gewinnt das Erz
 * mit dem groessten Ausstoss statt dem nuetzlichsten. Ein Ueberschuss, den
 * niemand braucht, ist null ISK wert: er muesste erst zurueck nach Jita, und
 * die Fracht frisst den Erloes regelmaessig auf.</p>
 *
 * <h2>Was diese Rechnung bewusst nicht kann</h2>
 * <p>Sie waehlt nicht die optimale Kombination mehrerer Erze - das waere ein
 * lineares Programm. Sie beantwortet die schwaechere, aber entscheidbare Frage:
 * <em>lohnt sich ueberhaupt ein Erz</em>. Der Faktor bei vollem Restbedarf ist
 * die obere Schranke fuer jeden Erzkauf; liegt er unter eins, ist der leere Korb
 * beweisbar richtig und kein blosses Versaeumnis.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcurementService {

    /**
     * Ausbeute, wenn zu einem Konto gar keine Skills vorliegen.
     *
     * <p>Die blosse Grundrate einer NPC-Station. Bewusst pessimistisch: wer ohne
     * Kenntnis der Skills grosszuegig rechnet, kauft zu wenig Erz ein, und das
     * faellt erst am leeren Ofen auf. Sobald die Skills bekannt sind, wird mit
     * ihnen gerechnet - gemessen an einem echten Konto ergibt Reprocessing V,
     * Efficiency V und Simple Ore Processing IV eine Ausbeute von 68,3 Prozent.</p>
     */
    private static final BigDecimal FALLBACK_YIELD = ReprocessingMath.NPC_STATION_BASE;

    private final IndustryQueryRepository queryRepo;

    /** Die Sprungentfernungen von Jita, einmal berechnet und dauerhaft behalten. */
    private final AtomicReference<Map<Long, Integer>> jumpCache = new AtomicReference<>();

    /**
     * Wie weit ein System von Jita entfernt ist.
     *
     * <p>Die Karte wird beim ersten Bedarf berechnet - das kostet rund eine
     * Sekunde - und danach behalten. Die Sprungdaten stehen im SDE und aendern
     * sich zur Laufzeit nie.</p>
     *
     * @return Sprungzahl, oder {@code null} wenn ueber Tore nicht erreichbar
     */
    @Transactional(readOnly = true)
    public Integer jumpsFromJita(Long solarSystemId) {
        if (solarSystemId == null) {
            return null;
        }
        Map<Long, Integer> karte = jumpCache.get();
        if (karte == null) {
            karte = queryRepo.allJumpsFromJita();
            jumpCache.set(karte);
            log.info("Sprungentfernungen von Jita berechnet: {} erreichbare Systeme.", karte.size());
        }
        return karte.get(solarSystemId);
    }

    /**
     * Rechnet eine Bedarfstabelle in eine Einkaufsliste um.
     *
     * @param requirements  was gebraucht wird, abzueglich dessen was schon da ist
     * @param systemId      Bauort-System, {@code null} wenn noch nicht gewaehlt
     * @param security      Sicherheitsstatus des Bauorts
     */
    @Transactional(readOnly = true)
    public IndustryDtos.ProcurementDto plan(List<IndustryDtos.RequirementDto> requirements,
                                            Long systemId, Double security) {
        return plan(requirements, systemId, security, Set.of());
    }

    /**
     * Rechnet eine Bedarfstabelle in eine Einkaufsliste um.
     *
     * @param characterIds die Charaktere des Kontos - ihre Aufbereitungs-Skills
     *                     bestimmen, wie viel Erz noetig ist
     */
    @Transactional(readOnly = true)
    public IndustryDtos.ProcurementDto plan(List<IndustryDtos.RequirementDto> requirements,
                                            Long systemId, Double security,
                                            Set<Long> characterIds) {
        Integer spruenge = jumpsFromJita(systemId);
        Transport transport = LogisticsMath.transportFor(security, spruenge);

        List<IndustryDtos.ProcurementLineDto> zeilen = new ArrayList<>();
        BigDecimal wareGesamt = BigDecimal.ZERO;
        double volumenGesamt = 0;
        int ohnePreis = 0;

        for (IndustryDtos.RequirementDto bedarf : requirements) {
            if (bedarf.missing() <= 0) {
                // Schon gedeckt - was da ist, muss nicht gekauft werden.
                continue;
            }
            if ("BUILD".equals(bedarf.decision())) {
                // Was gebaut wird, wird nicht gekauft. Ohne diese Zeile steht das
                // fertige Teil zusammen mit seinen Zutaten auf der Liste - beides
                // zu bezahlen waere doppelt, und die Summe stieg ausgerechnet
                // dann, wenn man sich fuer die guenstigere Eigenfertigung
                // entscheidet.
                continue;
            }
            IndustryDtos.ProcurementLineDto zeile = cheapestSource(bedarf, transport, characterIds);
            zeilen.add(zeile);

            if (zeile.totalCost() == null) {
                ohnePreis++;
            } else {
                wareGesamt = wareGesamt.add(BigDecimal.valueOf(zeile.purchaseCost()));
                volumenGesamt += zeile.volume();
            }
        }

        BigDecimal fracht = LogisticsMath.freightCost(volumenGesamt, transport);
        long ladungen = LogisticsMath.loads(volumenGesamt, transport);
        OreVerdict urteil = oreVerdict(requirements, zeilen, transport, characterIds);

        return new IndustryDtos.ProcurementDto(
                spruenge, systemId != null, transport.name(), transport.label(),
                transport.perCubicMeter().doubleValue(), transport.capacity(),
                wareGesamt.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                fracht.doubleValue(),
                wareGesamt.add(fracht).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                Math.round(volumenGesamt), ladungen, ohnePreis, zeilen,
                urteil.text(), urteil.factor());
    }

    /** Das Urteil ueber den Erzweg, samt Zahl. */
    private record OreVerdict(String text, Double factor) {}

    /**
     * Warum kein Erz auf der Liste steht - oder warum doch.
     *
     * <p>Ohne diesen Satz trifft der Assistent die Entscheidung unsichtbar. Wer
     * Erz erwartet und keines findet, haelt das Fehlen fuer einen Fehler statt
     * fuer ein Ergebnis - und liegt damit meistens daneben.</p>
     *
     * <p>Gerechnet wird der <em>Rentabilitaetsfaktor</em> des besten Erzes: was
     * eine Portion an gebrauchtem Material einspart, geteilt durch das, was sie
     * kostet. Beides einschliesslich Fracht, denn genau darum geht es beim Erz -
     * es ist komprimiert. Ueber 1,0 lohnt sich der Erzweg, darunter nicht.</p>
     *
     * <p>Anders als die Einzelrechnung je Mineral zaehlen hier die
     * <b>Nebenprodukte mit</b>: ein Erz, das nebenbei ein zweites gebrauchtes
     * Mineral liefert, wird dafuer gutgeschrieben. Aber nur bis zur Hoehe des
     * offenen Bedarfs - was darueber hinaus anfaellt, ist null ISK wert. Ohne
     * diesen Deckel gewinnt das Erz mit dem groessten Ausstoss statt dem
     * nuetzlichsten, und der Rat waere falsch.</p>
     */
    private OreVerdict oreVerdict(List<IndustryDtos.RequirementDto> requirements,
                                  List<IndustryDtos.ProcurementLineDto> zeilen,
                                  Transport transport, Set<Long> characterIds) {
        long erzZeilen = zeilen.stream().filter(z -> "ORE".equals(z.source())).count();
        if (erzZeilen > 0) {
            return new OreVerdict(erzZeilen + (erzZeilen == 1
                    ? " Zeile wird über Erz gedeckt - das ist dort günstiger als der Direktkauf."
                    : " Zeilen werden über Erz gedeckt - das ist dort günstiger als der Direktkauf."),
                    null);
        }

        // Was noch offen ist, samt dem Preis, den der Direktkauf dafuer kostet.
        Map<Long, Long> offen = new HashMap<>();
        Map<Long, Double> geliefertPreis = new HashMap<>();
        for (IndustryDtos.RequirementDto r : requirements) {
            if (r.missing() <= 0 || "BUILD".equals(r.decision()) || !queryRepo.isMineral(r.typeId())) {
                continue;
            }
            Double preis = queryRepo.jitaSell(r.typeId());
            if (preis == null) {
                continue;
            }
            offen.merge(r.typeId(), r.missing(), Long::sum);
            // Gespart wird die Ware UND ihr Transport - Mineralien sind sperrig.
            geliefertPreis.put(r.typeId(),
                    preis + r.packagedVolume() * transport.perCubicMeter().doubleValue());
        }
        if (offen.isEmpty()) {
            return new OreVerdict(null, null);
        }

        String bestesErz = null;
        double besterFaktor = 0;
        for (Long mineral : offen.keySet()) {
            for (OreSource erz : queryRepo.compressedOreSourcesFor(mineral)) {
                if (erz.jitaSell() == null || erz.jitaSell() <= 0) {
                    continue;
                }
                double faktor = portionFactor(erz, offen, geliefertPreis, transport, characterIds);
                if (faktor > besterFaktor) {
                    besterFaktor = faktor;
                    bestesErz = erz.typeName();
                }
            }
        }
        if (bestesErz == null) {
            return new OreVerdict("Für keines der fehlenden Mineralien gibt es ein "
                    + "komprimiertes Erz mit Marktpreis.", null);
        }

        String text = "Kein Erz lohnt sich: das beste, %s, erreicht %.0f %% der Schwelle. "
                .formatted(bestesErz, besterFaktor * 100)
                + "Gerechnet mit deinen Aufbereitungs-Skills an einer NPC-Station ohne Rigs; "
                + "eine eigene Refinery mit Rigs hebt die Ausbeute und damit diesen Wert.";
        return new OreVerdict(text, besterFaktor);
    }

    /**
     * Was eine Portion Erz einspart, geteilt durch das, was sie kostet.
     *
     * <p>Ueber alle Materialien der Portion, aber jedes nur bis zur Hoehe des
     * offenen Bedarfs. Der Wert bei vollem Restbedarf ist zugleich die obere
     * Schranke fuer jeden weiteren Erzkauf: liegt er unter 1,0, ist auch jede
     * groessere Menge unrentabel. Ein Ergebnis unter 1,0 ist damit kein
     * Schaetzwert, sondern ein Beweis.</p>
     */
    private double portionFactor(OreSource erz, Map<Long, Long> offen,
                                 Map<Long, Double> geliefertPreis, Transport transport,
                                 Set<Long> characterIds) {
        BigDecimal ausbeute = yieldFor(erz.typeId(), characterIds);
        long portion = Math.max(1, erz.portionSize());

        double nutzen = 0;
        for (var eintrag : queryRepo.materialsPerPortion(erz.typeId()).entrySet()) {
            Long offenerBedarf = offen.get(eintrag.getKey());
            Double preis = geliefertPreis.get(eintrag.getKey());
            if (offenerBedarf == null || preis == null) {
                // Nicht gebraucht - der Ueberschuss ist null ISK wert. Wer ihn
                // bewertet, redet sich Erz schoen: er muesste erst zurueck nach
                // Jita, und die Fracht frisst den Erloes regelmaessig auf.
                continue;
            }
            double anfall = eintrag.getValue() * ausbeute.doubleValue();
            nutzen += Math.min(anfall, offenerBedarf) * preis;
        }

        double kosten = portion * (erz.jitaSell()
                + erz.volumePerUnit() * transport.perCubicMeter().doubleValue());
        return kosten <= 0 ? 0 : nutzen / kosten;
    }

    /**
     * Die guenstigste Quelle fuer eine einzelne Bedarfszeile.
     *
     * <p>Verglichen wird auf <em>Gesamtkosten</em>, also Ware plus Transport.
     * Nur der Einkaufspreis waere irrefuehrend: bei Mineralien liegt der Transport
     * regelmaessig ueber dem Warenwert.</p>
     */
    private IndustryDtos.ProcurementLineDto cheapestSource(IndustryDtos.RequirementDto bedarf,
                                                           Transport transport,
                                                           Set<Long> characterIds) {
        long menge = bedarf.missing();
        Double stueckpreis = queryRepo.jitaSell(bedarf.typeId());

        double direktVolumen = bedarf.packagedVolume() * (double) menge;
        Double direktWare = stueckpreis == null ? null : stueckpreis * menge;
        Double direktGesamt = direktWare == null ? null
                : direktWare + LogisticsMath.freightCost(direktVolumen, transport).doubleValue();

        // Erze kommen nur fuer Mineralien in Frage.
        OreOption erz = queryRepo.isMineral(bedarf.typeId())
                ? bestOre(bedarf.typeId(), menge, transport, characterIds)
                : null;

        boolean erzGewinnt = erz != null
                && (direktGesamt == null || erz.totalCost < direktGesamt);

        if (erzGewinnt) {
            double ersparnis = direktGesamt == null ? 0 : direktGesamt - erz.totalCost;
            return new IndustryDtos.ProcurementLineDto(
                    bedarf.typeId(), bedarf.typeName(), menge,
                    "ORE", erz.oreTypeId, erz.oreName, erz.oreUnits,
                    erz.purchaseCost, erz.volume, erz.totalCost,
                    direktGesamt, ersparnis,
                    erz.mineralCount > 1
                            ? "Liefert " + erz.mineralCount + " Minerale - die übrigen sind ein "
                              + "Zugewinn, der hier nicht gegengerechnet wird."
                            : null);
        }

        return new IndustryDtos.ProcurementLineDto(
                bedarf.typeId(), bedarf.typeName(), menge,
                "DIRECT", null, null, menge,
                direktWare, direktVolumen, direktGesamt,
                erz == null ? null : erz.totalCost,
                erz == null || direktGesamt == null ? 0 : erz.totalCost - direktGesamt,
                stueckpreis == null ? "Kein Marktpreis vorhanden." : null);
    }

    /** Das guenstigste Erz fuer ein Mineral, samt Rechnung. */
    private record OreOption(long oreTypeId, String oreName, long oreUnits,
                             double purchaseCost, double volume, double totalCost,
                             int mineralCount) {}

    private OreOption bestOre(long mineralTypeId, long wantedMineral, Transport transport,
                              Set<Long> characterIds) {
        OreOption bestes = null;

        for (OreSource erz : queryRepo.compressedOreSourcesFor(mineralTypeId)) {
            if (erz.jitaSell() == null || erz.jitaSell() <= 0) {
                // Ohne Preis laesst sich nichts vergleichen - stillschweigend mit
                // null zu rechnen waere schlimmer als das Erz wegzulassen.
                continue;
            }
            BigDecimal ausbeute = yieldFor(erz.typeId(), characterIds);
            long einheiten = ReprocessingMath.oreUnitsFor(
                    wantedMineral, erz.mineralPerBatch(), erz.portionSize(), ausbeute);
            if (einheiten <= 0) {
                continue;
            }
            double ware = einheiten * erz.jitaSell();
            double volumen = einheiten * erz.volumePerUnit();
            double gesamt = ware + LogisticsMath.freightCost(volumen, transport).doubleValue();

            if (bestes == null || gesamt < bestes.totalCost) {
                bestes = new OreOption(erz.typeId(), erz.typeName(), einheiten,
                        ware, volumen, gesamt, erz.mineralCount());
            }
        }
        return bestes;
    }

    /**
     * Die Ausbeute fuer ein Erz, aus den tatsaechlichen Skills des Kontos.
     *
     * <p>Der erztypspezifische Skill haengt am Erz selbst und wird dort
     * nachgeschlagen - er heisst heute nicht mehr nach dem Erz, sondern nach
     * seiner Gruppe. Veldspar und Scordite verlangen beide "Simple Ore
     * Processing"; wer die alten Namen sucht, findet nichts.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal yieldFor(long oreTypeId, Set<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return FALLBACK_YIELD;
        }
        var skills = queryRepo.reprocessingSkills(characterIds, oreTypeId);
        return ReprocessingMath.yield(ReprocessingMath.NPC_STATION_BASE,
                skills.reprocessing(), skills.efficiency(), skills.oreSpecific(), BigDecimal.ONE);
    }
}

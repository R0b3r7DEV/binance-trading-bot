package com.xpl0day.bot.backtest;

import com.xpl0day.bot.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Ejecuta el backtest sobre múltiples periodos definidos en application.yml
 * bajo {@code backtest.periods} y muestra una tabla comparativa con métricas
 * descriptivas por periodo (régimen detectado, volatilidad, captura del movimiento).
 *
 * <p>Activación: {@code --spring.profiles.active=backtest-multi}
 *
 * <p>AVISO: estos resultados son simulaciones sobre datos históricos.
 * El rendimiento pasado no garantiza resultados futuros. No se emite ninguna
 * recomendación de inversión.
 */
@Component
@Profile("backtest-multi")
public class MultiPeriodRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MultiPeriodRunner.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final String DISCLAIMER =
            "Estos resultados son simulaciones sobre datos historicos. El rendimiento pasado no " +
            "garantiza resultados futuros. Esto no es asesoramiento financiero. Cualquier " +
            "decision de inversion es responsabilidad exclusiva del usuario.";

    private final HistoricalDataLoader loader;
    private final BacktestEngine       engine;
    private final BacktestConfig       config;
    private final RegimeClassifier     classifier;

    public MultiPeriodRunner(HistoricalDataLoader loader, BacktestEngine engine,
                             BacktestConfig config, RegimeClassifier classifier) {
        this.loader     = loader;
        this.engine     = engine;
        this.config     = config;
        this.classifier = classifier;
    }

    @Override
    public void run(String... args) {
        log.info("=== MODO BACKTEST MULTI-PERIODO ACTIVO ===");

        engine.setCommissionPct(config.commissionPct());
        engine.setSlippagePct(config.slippagePct());

        List<BacktestConfig.PeriodConfig> periods = config.periods();
        boolean noPeriods = periods.isEmpty();
        if (noPeriods) {
            log.warn("No hay periodos configurados en backtest.periods en application.yml.");
            return;
        }

        List<PeriodRow> rows = new ArrayList<>();
        for (BacktestConfig.PeriodConfig period : periods) {
            PeriodRow row = runPeriod(period);
            rows.add(row);
        }

        System.out.println();
        System.out.println(buildTable(rows));
        System.out.println();
        System.out.println("NOTA: " + DISCLAIMER);
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // privados
    // -----------------------------------------------------------------------

    private PeriodRow runPeriod(BacktestConfig.PeriodConfig period) {
        log.info("--- Periodo: {} ({} a {}) ---",
                period.name(), period.startDate(), period.endDate());

        long startMs = toEpochMs(period.startDate());
        long endMs   = toEndOfDayEpochMs(period.endDate());

        List<Candle> candles = loader.loadFromBinance(startMs, endMs);

        RegimeClassifier.RegimeInfo regime = classifier.classify(candles);

        BacktestResult result;
        boolean hasEnoughData = candles.size() > 10;
        if (hasEnoughData) {
            result = engine.run(candles, config.initialCapital());
        } else {
            log.warn("Periodo '{}' sin datos suficientes ({} velas).", period.name(), candles.size());
            result = emptyResult();
        }

        return new PeriodRow(period.name(), result, regime);
    }

    private String buildTable(List<PeriodRow> rows) {
        // Cabecera y separador — anchos de columna fijos
        String sep =
                "+-------------------------+---------+--------+----+-------+-----------+-----------+---------+---------+-----------+";
        String header = String.format(
                "| %-23s | %-7s | %-6s |%3s | %-5s | %-9s | %-9s | %-7s | %-7s | %-9s |",
                "Periodo", "Regimen", "Vol%", "Trd", "Win%",
                "Estrat%", "B&H%", "Diff pp", "DD max%", "Captura");

        StringBuilder sb = new StringBuilder();
        sb.append(sep).append(System.lineSeparator());
        sb.append(header).append(System.lineSeparator());
        sb.append(sep).append(System.lineSeparator());

        int periodsWon = 0;
        for (PeriodRow row : rows) {
            BacktestResult r  = row.result();
            RegimeClassifier.RegimeInfo regime = row.regime();

            BigDecimal diff = r.getNetReturnPct()
                    .subtract(r.getBuyAndHoldReturnPct())
                    .setScale(2, RoundingMode.HALF_UP);
            boolean beats = diff.compareTo(BigDecimal.ZERO) > 0;
            if (beats) {
                periodsWon++;
            }

            String captura = capturaLabel(r.getNetReturnPct(), r.getBuyAndHoldReturnPct());

            String line = String.format(
                    "| %-23s | %-7s | %5.1f%% |%3d | %4.1f%% | %+8.2f%% | %+8.2f%% | %+6.2f | %6.2f%% | %-9s |",
                    truncate(row.name(), 23),
                    regime.trend().name(),
                    regime.volatilityPct(),
                    r.getTotalTrades(),
                    r.winRatePct(),
                    r.getNetReturnPct(),
                    r.getBuyAndHoldReturnPct(),
                    diff,
                    r.getMaxDrawdownPct(),
                    captura);

            sb.append(line).append(System.lineSeparator());
        }

        sb.append(sep).append(System.lineSeparator());

        String summary = String.format(
                "| Estrategia bate B&H en %d/%d periodos%-52s |",
                periodsWon, rows.size(), "");
        sb.append(summary).append(System.lineSeparator());
        sb.append(sep).append(System.lineSeparator());

        return sb.toString();
    }

    /**
     * Porcentaje del movimiento B&H que capturó la estrategia.
     * Si B&H <= 0, el ratio no aplica como medida de captura de subida.
     */
    private String capturaLabel(BigDecimal stratPct, BigDecimal bhPct) {
        String label = "B&H<=0";
        boolean bhPositive = bhPct.compareTo(BigDecimal.ZERO) > 0;
        if (bhPositive) {
            BigDecimal ratio = stratPct.multiply(HUNDRED)
                    .divide(bhPct, 1, RoundingMode.HALF_UP);
            label = String.format("%+.1f%%", ratio);
        }
        return label;
    }

    private long toEpochMs(String isoDate) {
        return LocalDate.parse(isoDate)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    private long toEndOfDayEpochMs(String isoDate) {
        return LocalDate.parse(isoDate)
                .atTime(23, 59, 59)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
    }

    private String truncate(String s, int maxLen) {
        String result = s;
        if (s.length() > maxLen) {
            result = s.substring(0, maxLen - 1) + "~";
        }
        return result;
    }

    private BacktestResult emptyResult() {
        return new BacktestResult(0, 0, 0,
                config.initialCapital(), config.initialCapital(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0);
    }

    private record PeriodRow(String name, BacktestResult result,
                             RegimeClassifier.RegimeInfo regime) {}
}

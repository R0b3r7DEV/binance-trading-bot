package com.xpl0day.bot.backtest;

import com.xpl0day.bot.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Validación OUT-OF-SAMPLE de la estrategia.
 *
 * <p>Activación: {@code --spring.profiles.active=oos}
 *
 * <p>Flujo:
 * <ol>
 *   <li>Descarga un histórico largo de BTCUSDT 1h del endpoint público de Binance.</li>
 *   <li>Lo parte cronológicamente: primera mitad = ENTRENAMIENTO, segunda = PRUEBA.</li>
 *   <li>Grid search sobre ENTRENAMIENTO (fast, slow, adx-threshold): elige la
 *       combinación con mayor rentabilidad neta.</li>
 *   <li>Esa combinación ganadora se ejecuta UNA SOLA VEZ sobre PRUEBA (sin reoptimizar).</li>
 *   <li>Tabla comparando entrenamiento vs prueba + veredicto descriptivo.</li>
 * </ol>
 *
 * <p>Comisión 0.1% y slippage 0.05% aplicados en ambas mitades.
 * El reporte es DESCRIPTIVO: no constituye recomendación de inversión.
 */
@Component
@Profile("oos")
public class OutOfSampleRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OutOfSampleRunner.class);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String DISCLAIMER =
            "Simulacion sobre datos historicos. El rendimiento pasado NO garantiza resultados " +
            "futuros. Esto NO es asesoramiento financiero. Cualquier decision de inversion es " +
            "responsabilidad exclusiva del usuario.";

    // Grid de búsqueda. fast < slow siempre.
    private static final int[] FAST_GRID  = {10, 20, 30};
    private static final int[] SLOW_GRID  = {50, 100, 150};
    private static final BigDecimal[] ADX_GRID = {
            new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("30")
    };

    // ~3 años de velas de 1h (3 * 365 * 24 = 26280)
    private static final int TOTAL_CANDLES = 26280;

    private final HistoricalDataLoader loader;
    private final OutOfSampleEngine    engine;
    private final BacktestConfig       config;

    public OutOfSampleRunner(HistoricalDataLoader loader, OutOfSampleEngine engine, BacktestConfig config) {
        this.loader = loader;
        this.engine = engine;
        this.config = config;
    }

    @Override
    public void run(String... args) {
        log.info("=== VALIDACION OUT-OF-SAMPLE ===");

        engine.setCommissionPct(config.commissionPct());
        engine.setSlippagePct(config.slippagePct());

        List<Candle> all = loader.loadFromBinance(TOTAL_CANDLES);
        boolean enoughData = all.size() >= 2000;

        if (!enoughData) {
            log.error("Datos insuficientes para OOS: solo {} velas descargadas.", all.size());
        }

        if (enoughData) {
            runValidation(all);
        }
    }

    // -----------------------------------------------------------------------
    // privados
    // -----------------------------------------------------------------------

    private void runValidation(List<Candle> all) {
        int mid = all.size() / 2;
        List<Candle> train = new ArrayList<>(all.subList(0, mid));
        List<Candle> test  = new ArrayList<>(all.subList(mid, all.size()));
        BigDecimal capital = config.initialCapital();

        log.info("Histórico total: {} velas (1h)", all.size());
        log.info("ENTRENAMIENTO: {} velas | PRUEBA: {} velas", train.size(), test.size());

        // --- Grid search sobre ENTRENAMIENTO ---
        List<GridRow> grid = new ArrayList<>();
        OutOfSampleParams best = null;
        BacktestResult bestTrainResult = null;
        BigDecimal bestReturn = null;

        for (int fast : FAST_GRID) {
            for (int slow : SLOW_GRID) {
                boolean validPair = fast < slow;
                if (validPair) {
                    for (BigDecimal adxThr : ADX_GRID) {
                        OutOfSampleParams params = new OutOfSampleParams(fast, slow, adxThr);
                        BacktestResult r = engine.run(train, capital, params);
                        grid.add(new GridRow(params, r));

                        boolean isBetter = bestReturn == null
                                || r.getNetReturnPct().compareTo(bestReturn) > 0;
                        if (isBetter) {
                            bestReturn      = r.getNetReturnPct();
                            best            = params;
                            bestTrainResult = r;
                        }
                    }
                }
            }
        }

        printGridTop(grid);

        // --- Evaluación ÚNICA sobre PRUEBA con la combinación ganadora ---
        BacktestResult testResult = engine.run(test, capital, best);

        printComparison(best, bestTrainResult, testResult);
        printVerdict(bestTrainResult, testResult);
    }

    private void printGridTop(List<GridRow> grid) {
        // Ordenar por rentabilidad neta descendente (copia simple por selección)
        List<GridRow> sorted = new ArrayList<>(grid);
        sorted.sort((a, b) -> b.result().getNetReturnPct().compareTo(a.result().getNetReturnPct()));

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("GRID SEARCH (ENTRENAMIENTO) — top combinaciones por rentabilidad neta:").append(System.lineSeparator());
        String sep = "+------+------+--------+-----------+------+--------+";
        sb.append(sep).append(System.lineSeparator());
        sb.append(String.format("| %-4s | %-4s | %-6s | %-9s | %-4s | %-6s |",
                "fast", "slow", "adxThr", "Neto%", "Trd", "Win%")).append(System.lineSeparator());
        sb.append(sep).append(System.lineSeparator());

        int limit = Math.min(8, sorted.size());
        for (int i = 0; i < limit; i++) {
            GridRow row = sorted.get(i);
            OutOfSampleParams p = row.params();
            BacktestResult r = row.result();
            sb.append(String.format("| %4d | %4d | %6s | %+8.2f%% | %4d | %5.1f%% |",
                    p.fastPeriod(), p.slowPeriod(), p.adxThreshold().toPlainString(),
                    r.getNetReturnPct(), r.getTotalTrades(), r.winRatePct()))
              .append(System.lineSeparator());
        }
        sb.append(sep);
        System.out.println(sb);
    }

    private void printComparison(OutOfSampleParams best, BacktestResult train, BacktestResult test) {
        BigDecimal trainDiff = train.getNetReturnPct().subtract(train.getBuyAndHoldReturnPct())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal testDiff = test.getNetReturnPct().subtract(test.getBuyAndHoldReturnPct())
                .setScale(2, RoundingMode.HALF_UP);

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("COMBINACION GANADORA EN ENTRENAMIENTO: ").append(best).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        String sep = "+----------------------------+----------------+----------------+";
        sb.append(sep).append(System.lineSeparator());
        sb.append(String.format("| %-26s | %-14s | %-14s |", "Metrica", "ENTRENAMIENTO", "PRUEBA"))
          .append(System.lineSeparator());
        sb.append(sep).append(System.lineSeparator());
        sb.append(metricRow("Rentabilidad neta (%)", fmtPct(train.getNetReturnPct()), fmtPct(test.getNetReturnPct())));
        sb.append(metricRow("Buy & Hold (%)", fmtPct(train.getBuyAndHoldReturnPct()), fmtPct(test.getBuyAndHoldReturnPct())));
        sb.append(metricRow("Diferencia (pp)", fmtPct(trainDiff), fmtPct(testDiff)));
        sb.append(metricRow("Nº operaciones", String.valueOf(train.getTotalTrades()), String.valueOf(test.getTotalTrades())));
        sb.append(metricRow("Win rate (%)", fmtPct(train.winRatePct()), fmtPct(test.winRatePct())));
        sb.append(metricRow("Drawdown maximo (%)", fmtPct(train.getMaxDrawdownPct()), fmtPct(test.getMaxDrawdownPct())));
        sb.append(metricRow("Capital final (USDT)", train.getFinalCapital().toPlainString(), test.getFinalCapital().toPlainString()));
        sb.append(sep);
        System.out.println(sb);
    }

    private void printVerdict(BacktestResult train, BacktestResult test) {
        boolean beatsTrain = train.getNetReturnPct().compareTo(train.getBuyAndHoldReturnPct()) > 0;
        boolean beatsTest  = test.getNetReturnPct().compareTo(test.getBuyAndHoldReturnPct()) > 0;

        String verdict;
        if (beatsTrain && beatsTest) {
            verdict = "La ventaja sobre buy & hold PERSISTE en datos no vistos (entrenamiento Y prueba).";
        } else if (beatsTrain && !beatsTest) {
            verdict = "SENAL DE SOBREAJUSTE: la combinacion batio a buy & hold en entrenamiento pero "
                    + "NO en prueba; estaba adaptada al pasado conocido.";
        } else {
            verdict = "La combinacion ganadora en entrenamiento no mostro ventaja clara sobre buy & hold; "
                    + "resultado no concluyente.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("VEREDICTO (descriptivo): ").append(verdict).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("NOTA: ").append(DISCLAIMER).append(System.lineSeparator());
        System.out.println(sb);
    }

    private String metricRow(String metric, String trainVal, String testVal) {
        return String.format("| %-26s | %14s | %14s |", metric, trainVal, testVal) + System.lineSeparator();
    }

    private String fmtPct(BigDecimal v) {
        return String.format("%+.2f%%", v);
    }

    /** Una fila del grid: parámetros y su resultado en entrenamiento. */
    private record GridRow(OutOfSampleParams params, BacktestResult result) {}
}

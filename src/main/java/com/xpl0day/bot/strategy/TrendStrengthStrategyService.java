package com.xpl0day.bot.strategy;

import com.xpl0day.bot.marketdata.Candle;
import com.xpl0day.bot.strategy.indicators.Adx;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Estrategia que combina TODO lo existente con el filtro de fuerza de tendencia (ADX):
 * <ul>
 *   <li>Cruce SMA rápida/lenta + filtro de SMA larga: delega en
 *       {@link TrendFilteredStrategyService} (lógica ya existente, sin tocar).</li>
 *   <li>AÑADE filtro ADX: una señal BUY solo se permite si el ADX está por encima
 *       de {@code adxThreshold} (hay tendencia con fuerza). Si el ADX está por debajo
 *       (mercado lateral), la señal BUY se convierte en HOLD.</li>
 *   <li>Las señales SELL (cierre) se respetan SIEMPRE, sin filtrar.</li>
 *   <li>{@code adxPeriod <= 0} desactiva el filtro ADX (se comporta como
 *       {@link TrendFilteredStrategyService}).</li>
 *   <li>Si no hay datos suficientes para calcular el ADX, la postura conservadora
 *       bloquea la señal BUY (no se opera sin el indicador disponible).</li>
 * </ul>
 *
 * <p>Estilo: un único {@code return} al final por método; BigDecimal para precios.
 */
@Service
public class TrendStrengthStrategyService {

    private final TrendFilteredStrategyService trendFiltered;

    public TrendStrengthStrategyService(TrendFilteredStrategyService trendFiltered) {
        this.trendFiltered = trendFiltered;
    }

    /**
     * Evalúa la señal aplicando cruce SMA, filtro SMA larga y filtro de fuerza ADX.
     *
     * @param candles      velas en orden cronológico
     * @param fastPeriod   periodo SMA rápida
     * @param slowPeriod   periodo SMA lenta
     * @param trendPeriod  periodo SMA larga (0 = sin filtro de tendencia)
     * @param adxPeriod    periodo del ADX (0 = sin filtro de fuerza)
     * @param adxThreshold umbral mínimo de ADX para permitir BUY
     */
    public Signal evaluate(List<Candle> candles, int fastPeriod, int slowPeriod,
                           int trendPeriod, int adxPeriod, BigDecimal adxThreshold) {
        // Base: cruce SMA + filtro SMA larga (lógica existente intacta)
        Signal baseSignal = trendFiltered.evaluate(candles, fastPeriod, slowPeriod, trendPeriod);

        boolean adxFilterActive = adxPeriod > 0;
        boolean isBuySignal     = baseSignal == Signal.BUY;

        Signal result = baseSignal;

        if (adxFilterActive && isBuySignal) {
            boolean strongTrend = isStrongTrend(candles, adxPeriod, adxThreshold);
            boolean blockedByAdx = !strongTrend;
            if (blockedByAdx) {
                result = Signal.HOLD;
            }
        }

        return result;
    }

    /**
     * true si el ADX de la última vela es >= umbral. Si no hay datos suficientes
     * (ADX no calculable) devuelve false: postura conservadora, no se abre posición.
     */
    private boolean isStrongTrend(List<Candle> candles, int adxPeriod, BigDecimal adxThreshold) {
        Adx.Result adx = new Adx(adxPeriod).calculate(candles);
        BigDecimal threshold = adxThreshold != null ? adxThreshold : BigDecimal.ZERO;
        // Adx devuelve 0 cuando no hay datos suficientes; un umbral > 0 lo descarta solo.
        boolean strong = adx.isStrongTrend(threshold) && adx.adx().compareTo(BigDecimal.ZERO) > 0;
        return strong;
    }
}

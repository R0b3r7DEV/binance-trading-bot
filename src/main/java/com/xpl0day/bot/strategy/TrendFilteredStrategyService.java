package com.xpl0day.bot.strategy;

import com.xpl0day.bot.marketdata.Candle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Envuelve {@link StrategyService} añadiendo un filtro de tendencia de largo plazo.
 *
 * <p>Regla del filtro:
 * <ul>
 *   <li>Señal BUY: solo se permite si el precio de cierre está <em>por encima</em>
 *       de la SMA de {@code trendPeriod} periodos. Si el precio está por debajo,
 *       la señal se convierte en HOLD.</li>
 *   <li>Señal SELL: pasa siempre, sin restricciones (permite cerrar posiciones
 *       abiertas independientemente de la tendencia).</li>
 *   <li>Señal HOLD: pasa sin modificar.</li>
 *   <li>Si {@code trendPeriod <= 0}: el filtro está desactivado y el método
 *       se comporta igual que {@link StrategyService#evaluate}.</li>
 *   <li>Si no hay suficientes velas para calcular la SMA larga, la postura
 *       conservadora bloquea la señal BUY (evita entrar sin información).</li>
 * </ul>
 *
 * <p>Estilo: ningún {@code return} dentro de bloque condicional. Un único
 * {@code return} al final de cada método.
 */
@Service
public class TrendFilteredStrategyService {

    private final StrategyService inner;

    public TrendFilteredStrategyService(StrategyService inner) {
        this.inner = inner;
    }

    /**
     * Evalúa la señal base y aplica el filtro de tendencia de largo plazo.
     *
     * @param candles     velas en orden cronológico (la última es la más reciente)
     * @param fastPeriod  periodo de la media rápida
     * @param slowPeriod  periodo de la media lenta
     * @param trendPeriod periodo de la media de tendencia (0 = sin filtro)
     */
    public Signal evaluate(List<Candle> candles, int fastPeriod, int slowPeriod, int trendPeriod) {
        Signal baseSignal = inner.evaluate(candles, fastPeriod, slowPeriod);

        boolean filterActive = trendPeriod > 0;
        boolean isBuySignal  = baseSignal == Signal.BUY;

        Signal result = baseSignal;

        if (filterActive && isBuySignal) {
            boolean hasEnoughData = candles != null && candles.size() >= trendPeriod;
            boolean aboveTrend    = false; // postura conservadora por defecto

            if (hasEnoughData) {
                int last = candles.size() - 1;
                BigDecimal trendSma   = sma(candles, last, trendPeriod);
                BigDecimal closePrice = candles.get(last).close();
                aboveTrend = closePrice.compareTo(trendSma) > 0;
            }

            boolean blockedByFilter = !aboveTrend;
            if (blockedByFilter) {
                result = Signal.HOLD;
            }
        }

        return result;
    }

    /** SMA de los precios de cierre terminando en {@code endIndex} inclusive. */
    private BigDecimal sma(List<Candle> candles, int endIndex, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        int start = endIndex - period + 1;
        for (int i = start; i <= endIndex; i++) {
            sum = sum.add(candles.get(i).close());
        }
        return sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }
}

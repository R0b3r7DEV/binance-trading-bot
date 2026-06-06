package com.xpl0day.bot.strategy;

import com.xpl0day.bot.marketdata.Candle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Estrategia de cruce de medias móviles simples (SMA).
 *
 * Regla:
 *  - Si la media rápida cruza POR ENCIMA de la lenta -> BUY
 *  - Si la media rápida cruza POR DEBAJO de la lenta -> SELL
 *  - En cualquier otro caso -> HOLD
 *
 * "Cruce" significa que en la vela anterior la relación era la contraria.
 * Así solo emitimos señal en el momento del cruce, no en cada vela.
 *
 * IMPORTANTE: esta estrategia es un punto de partida DIDÁCTICO. No es
 * rentable por sí sola. Sirve para tener una regla inequívoca y testeable.
 *
 * El método es determinista y sin estado: misma entrada -> misma salida.
 * Eso lo hace testeable y backtesteable.
 */
@Service
public class StrategyService {

    /**
     * Evalúa las velas y devuelve una señal.
     *
     * @param candles    lista de velas en orden cronológico (la última es la más reciente)
     * @param fastPeriod periodo de la media rápida
     * @param slowPeriod periodo de la media lenta
     * @return la señal resultante
     */
    public Signal evaluate(List<Candle> candles, int fastPeriod, int slowPeriod) {
        Signal result = Signal.HOLD;

        // Necesitamos al menos slowPeriod + 1 velas para comparar con la vela previa.
        boolean enoughData = candles != null && candles.size() >= slowPeriod + 1;

        if (enoughData) {
            int last = candles.size() - 1;

            BigDecimal fastNow = sma(candles, last, fastPeriod);
            BigDecimal slowNow = sma(candles, last, slowPeriod);
            BigDecimal fastPrev = sma(candles, last - 1, fastPeriod);
            BigDecimal slowPrev = sma(candles, last - 1, slowPeriod);

            boolean crossedUp = fastPrev.compareTo(slowPrev) <= 0
                    && fastNow.compareTo(slowNow) > 0;
            boolean crossedDown = fastPrev.compareTo(slowPrev) >= 0
                    && fastNow.compareTo(slowNow) < 0;

            if (crossedUp) {
                result = Signal.BUY;
            } else if (crossedDown) {
                result = Signal.SELL;
            }
        }

        return result;
    }

    /**
     * Media móvil simple de los precios de cierre, terminando en endIndex
     * (inclusive) y mirando hacia atrás 'period' velas.
     */
    private BigDecimal sma(List<Candle> candles, int endIndex, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        int start = endIndex - period + 1;

        for (int i = start; i <= endIndex; i++) {
            sum = sum.add(candles.get(i).close());
        }

        return sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }
}

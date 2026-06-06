package com.xpl0day.bot.backtest;

import com.xpl0day.bot.marketdata.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Clasificador descriptivo del régimen de mercado de un periodo.
 *
 * <p>Calcula métricas estadísticas objetivas sobre el conjunto de velas:
 * <ul>
 *   <li><b>Tendencia</b>: compara el precio inicial con el final. Si el cambio
 *       supera ±{@value #TREND_THRESHOLD_PCT}%, se considera tendencial. De lo
 *       contrario, lateral. Solo describe lo que ocurrió, no predice el futuro.</li>
 *   <li><b>Volatilidad</b>: desviación estándar de los retornos simples por
 *       vela, anualizada multiplicando por √8760 (horas/año para velas de 1h).
 *       Expresada como % anualizado. Si el intervalo no es 1h, la anualización
 *       es una aproximación.</li>
 * </ul>
 *
 * <p>Este componente describe hechos históricos. No emite predicciones ni
 * recomendaciones de ningún tipo.
 */
@Component
public class RegimeClassifier {

    public enum TrendType { ALCISTA, BAJISTA, LATERAL }

    /** Umbral de cambio de precio (%) para clasificar tendencia vs lateral. */
    private static final double TREND_THRESHOLD_PCT = 10.0;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** sqrt(8760) = factor de anualización para retornos horarios. */
    private static final double ANNUALIZATION_FACTOR = Math.sqrt(8760.0);

    /**
     * Resultado de la clasificación: tipo de tendencia y volatilidad anualizada.
     *
     * @param trend         tipo de tendencia observado en el periodo
     * @param volatilityPct desviación estándar de retornos, anualizada, en %
     * @param changePct     variación precio inicial → precio final del periodo (%)
     */
    public record RegimeInfo(TrendType trend, BigDecimal volatilityPct, BigDecimal changePct) {}

    /**
     * Clasifica el régimen de mercado del periodo representado por {@code candles}.
     * Requiere al menos 2 velas; con menos devuelve LATERAL y 0% de volatilidad.
     */
    public RegimeInfo classify(List<Candle> candles) {
        boolean hasData = candles != null && candles.size() >= 2;

        RegimeInfo result = new RegimeInfo(TrendType.LATERAL, BigDecimal.ZERO, BigDecimal.ZERO);

        if (hasData) {
            BigDecimal firstClose = candles.get(0).close();
            BigDecimal lastClose  = candles.get(candles.size() - 1).close();

            BigDecimal changePct = lastClose.subtract(firstClose)
                    .multiply(HUNDRED)
                    .divide(firstClose, 4, RoundingMode.HALF_UP);

            TrendType trend = classifyTrend(changePct);
            BigDecimal volatility = annualizedVolatility(candles);

            result = new RegimeInfo(trend, volatility, changePct);
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // privados
    // -----------------------------------------------------------------------

    private TrendType classifyTrend(BigDecimal changePct) {
        TrendType trend = TrendType.LATERAL;
        double change = changePct.doubleValue();
        boolean isBull = change > TREND_THRESHOLD_PCT;
        boolean isBear = change < -TREND_THRESHOLD_PCT;
        if (isBull) {
            trend = TrendType.ALCISTA;
        } else if (isBear) {
            trend = TrendType.BAJISTA;
        }
        return trend;
    }

    /**
     * Desviación estándar de los retornos simples, anualizada.
     * Retorno simple en cada vela: (close[i] - close[i-1]) / close[i-1].
     */
    private BigDecimal annualizedVolatility(List<Candle> candles) {
        int n = candles.size() - 1;
        BigDecimal result = BigDecimal.ZERO;

        boolean canCompute = n >= 2;
        if (canCompute) {
            List<BigDecimal> returns = computeReturns(candles);
            BigDecimal mean = mean(returns);
            BigDecimal variance = variance(returns, mean);

            // Pasar a double solo para la raíz cuadrada
            double stdDev = Math.sqrt(variance.doubleValue());
            double annualized = stdDev * ANNUALIZATION_FACTOR * 100.0; // → porcentaje

            result = BigDecimal.valueOf(annualized).setScale(2, RoundingMode.HALF_UP);
        }

        return result;
    }

    private List<BigDecimal> computeReturns(List<Candle> candles) {
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            BigDecimal prev  = candles.get(i - 1).close();
            BigDecimal curr  = candles.get(i).close();
            boolean validPrev = prev.compareTo(BigDecimal.ZERO) > 0;
            if (validPrev) {
                BigDecimal ret = curr.subtract(prev).divide(prev, 10, RoundingMode.HALF_UP);
                returns.add(ret);
            }
        }
        return returns;
    }

    private BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        BigDecimal mean = BigDecimal.ZERO;
        boolean notEmpty = !values.isEmpty();
        if (notEmpty) {
            mean = sum.divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
        }
        return mean;
    }

    private BigDecimal variance(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal sumSq = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean);
            sumSq = sumSq.add(diff.multiply(diff, MathContext.DECIMAL64));
        }
        BigDecimal variance = BigDecimal.ZERO;
        boolean enoughSamples = values.size() >= 2;
        if (enoughSamples) {
            // Varianza muestral (n-1)
            variance = sumSq.divide(BigDecimal.valueOf(values.size() - 1), 10, RoundingMode.HALF_UP);
        }
        return variance;
    }
}

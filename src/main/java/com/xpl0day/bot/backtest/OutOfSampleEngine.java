package com.xpl0day.bot.backtest;

import com.xpl0day.bot.config.BotProperties;
import com.xpl0day.bot.marketdata.Candle;
import com.xpl0day.bot.strategy.indicators.Adx;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Motor de backtest PARAMETRIZADO y eficiente, pensado para grid search.
 *
 * <p>A diferencia de {@link BacktestEngine} (que lee los parámetros de la
 * configuración y recalcula indicadores por ventana), este motor:
 * <ul>
 *   <li>Acepta los parámetros por argumento ({@link OutOfSampleParams}).</li>
 *   <li>Precalcula SMAs (mediante sumas prefijas exactas) y la serie de ADX
 *       UNA sola vez en O(n), evitando el coste O(n²) del grid search.</li>
 * </ul>
 *
 * <p>La regla de decisión replica EXACTAMENTE la de los servicios de estrategia
 * (StrategyService + TrendFilteredStrategyService + TrendStrengthStrategyService):
 * <ul>
 *   <li>Cruce SMA rápida/lenta para BUY/SELL.</li>
 *   <li>BUY solo si precio &gt; SMA(trendPeriod) [filtro tendencia].</li>
 *   <li>BUY solo si ADX &gt;= umbral [filtro fuerza].</li>
 *   <li>SELL (cierre) se respeta siempre.</li>
 * </ul>
 * Las SMAs usan la misma fórmula (suma/period, escala 8 HALF_UP) y el ADX el
 * mismo {@link Adx}, por lo que los valores coinciden con los servicios.
 *
 * <p>Comisión y slippage se aplican igual que en {@link BacktestEngine}.
 * Estilo: un único {@code return} al final por método; BigDecimal para dinero.
 */
@Component
public class OutOfSampleEngine {

    private static final int SCALE = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BotProperties properties;

    private BigDecimal commissionPct = new BigDecimal("0.1");
    private BigDecimal slippagePct   = new BigDecimal("0.05");

    public OutOfSampleEngine(BotProperties properties) {
        this.properties = properties;
    }

    public void setCommissionPct(BigDecimal commissionPct) { this.commissionPct = commissionPct; }
    public void setSlippagePct(BigDecimal slippagePct)     { this.slippagePct = slippagePct; }

    /**
     * Ejecuta el backtest sobre {@code candles} con la combinación {@code params}.
     *
     * @param candles velas en orden cronológico
     * @param capital capital inicial en USDT
     * @param params  combinación de parámetros a evaluar
     */
    public BacktestResult run(List<Candle> candles, BigDecimal capital, OutOfSampleParams params) {
        int fast        = params.fastPeriod();
        int slow        = params.slowPeriod();
        BigDecimal adxThreshold = params.adxThreshold();
        int trendPeriod = properties.trading().trendPeriod();
        int adxPeriod   = properties.trading().adxPeriod();
        BigDecimal stopLossPct = properties.risk().stopLossPercent();

        int n = candles.size();

        // --- Precálculo de indicadores (O(n)) ---
        BigDecimal[] prefix = prefixSums(candles);          // sumas prefijas de cierres
        BigDecimal[] smaFast  = smaSeries(prefix, n, fast);
        BigDecimal[] smaSlow  = smaSeries(prefix, n, slow);
        BigDecimal[] smaTrend = trendPeriod > 0 ? smaSeries(prefix, n, trendPeriod) : null;
        BigDecimal[] adxSeries = adxPeriod > 0 ? new Adx(adxPeriod).adxSeries(candles) : null;

        // Primer índice operable: todos los indicadores disponibles (y el previo del cruce)
        int minTrend = trendPeriod > 0 ? trendPeriod - 1 : 0;
        int minAdx   = adxPeriod > 0 ? 2 * adxPeriod - 1 : 0;
        int minCandles = Math.max(Math.max(slow, minTrend), minAdx);
        if (minCandles < 1) {
            minCandles = 1;
        }

        // --- Estado de simulación ---
        BigDecimal currentCapital  = capital;
        BigDecimal peakCapital     = capital;
        BigDecimal maxDrawdown     = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;

        boolean hasPosition  = false;
        BigDecimal positionQty = BigDecimal.ZERO;
        BigDecimal entryPrice  = BigDecimal.ZERO;

        int totalTrades   = 0;
        int winningTrades = 0;
        int losingTrades  = 0;

        for (int i = minCandles; i < n; i++) {
            BigDecimal closePrice = candles.get(i).close();

            // Stop-loss
            boolean stopTriggered = false;
            if (hasPosition) {
                BigDecimal stopLevel = entryPrice.multiply(
                        BigDecimal.ONE.subtract(stopLossPct.divide(HUNDRED, SCALE, RoundingMode.HALF_UP)));
                stopTriggered = closePrice.compareTo(stopLevel) <= 0;
            }

            // Señal con filtros (precomputados)
            int signal = computeSignal(i, closePrice, smaFast, smaSlow, smaTrend,
                    adxSeries, trendPeriod, adxPeriod, adxThreshold);

            boolean shouldSell = hasPosition && (signal == -1 || stopTriggered);
            boolean shouldBuy  = !hasPosition && signal == 1;

            // Cerrar posición
            if (shouldSell) {
                BigDecimal exitPrice  = applySlippage(closePrice, false);
                BigDecimal proceeds   = positionQty.multiply(exitPrice);
                BigDecimal commission = proceeds.multiply(commissionPct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
                BigDecimal net        = proceeds.subtract(commission);
                totalCommission       = totalCommission.add(commission);
                currentCapital        = currentCapital.add(net);

                BigDecimal pnl = net.subtract(positionQty.multiply(entryPrice));
                boolean isWin = pnl.compareTo(BigDecimal.ZERO) > 0;
                if (isWin) {
                    winningTrades = winningTrades + 1;
                } else {
                    losingTrades = losingTrades + 1;
                }
                totalTrades = totalTrades + 1;

                hasPosition = false;
                positionQty = BigDecimal.ZERO;
                entryPrice  = BigDecimal.ZERO;
            }

            // Abrir posición
            if (shouldBuy) {
                BigDecimal fillPrice  = applySlippage(closePrice, true);
                BigDecimal commission = currentCapital.multiply(commissionPct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
                BigDecimal afterFee   = currentCapital.subtract(commission);
                totalCommission       = totalCommission.add(commission);
                positionQty           = afterFee.divide(fillPrice, SCALE, RoundingMode.HALF_UP);
                entryPrice            = fillPrice;
                currentCapital        = BigDecimal.ZERO;
                hasPosition           = true;
            }

            // Drawdown a valor de mercado
            BigDecimal equity = currentCapital;
            if (hasPosition) {
                equity = positionQty.multiply(closePrice);
            }
            if (equity.compareTo(peakCapital) > 0) {
                peakCapital = equity;
            }
            if (peakCapital.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drawdown = peakCapital.subtract(equity)
                        .multiply(HUNDRED)
                        .divide(peakCapital, 4, RoundingMode.HALF_UP);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }

        // Cierre forzado al final
        BigDecimal finalCapital = currentCapital;
        if (hasPosition) {
            BigDecimal lastPrice  = candles.get(n - 1).close();
            BigDecimal exitPrice  = applySlippage(lastPrice, false);
            BigDecimal proceeds   = positionQty.multiply(exitPrice);
            BigDecimal commission = proceeds.multiply(commissionPct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            finalCapital          = proceeds.subtract(commission);
            totalCommission       = totalCommission.add(commission);
        }

        BigDecimal netReturnPct = finalCapital.subtract(capital)
                .multiply(HUNDRED)
                .divide(capital, 4, RoundingMode.HALF_UP);

        BigDecimal firstClose = candles.get(minCandles).close();
        BigDecimal lastClose  = candles.get(n - 1).close();
        BigDecimal buyAndHoldPct = lastClose.subtract(firstClose)
                .multiply(HUNDRED)
                .divide(firstClose, 4, RoundingMode.HALF_UP);

        return new BacktestResult(
                totalTrades, winningTrades, losingTrades,
                capital,
                finalCapital.setScale(2, RoundingMode.HALF_UP),
                netReturnPct,
                maxDrawdown,
                buyAndHoldPct,
                totalCommission.setScale(2, RoundingMode.HALF_UP),
                n);
    }

    // -----------------------------------------------------------------------
    // privados
    // -----------------------------------------------------------------------

    /**
     * Devuelve la señal: 1 = BUY, -1 = SELL, 0 = HOLD. Replica la regla de los
     * servicios de estrategia usando indicadores precomputados.
     */
    private int computeSignal(int i, BigDecimal closePrice,
                              BigDecimal[] smaFast, BigDecimal[] smaSlow, BigDecimal[] smaTrend,
                              BigDecimal[] adxSeries, int trendPeriod, int adxPeriod,
                              BigDecimal adxThreshold) {
        int signal = 0;

        boolean haveCrossData = smaFast[i] != null && smaSlow[i] != null
                && smaFast[i - 1] != null && smaSlow[i - 1] != null;

        if (haveCrossData) {
            boolean crossedUp = smaFast[i - 1].compareTo(smaSlow[i - 1]) <= 0
                    && smaFast[i].compareTo(smaSlow[i]) > 0;
            boolean crossedDown = smaFast[i - 1].compareTo(smaSlow[i - 1]) >= 0
                    && smaFast[i].compareTo(smaSlow[i]) < 0;

            if (crossedUp) {
                signal = 1;
            } else if (crossedDown) {
                signal = -1;
            }
        }

        // Filtros solo afectan a BUY
        if (signal == 1) {
            boolean allowed = passesBuyFilters(i, closePrice, smaTrend, adxSeries,
                    trendPeriod, adxPeriod, adxThreshold);
            if (!allowed) {
                signal = 0;
            }
        }

        return signal;
    }

    /** true si una señal BUY supera los filtros de tendencia y de fuerza (ADX). */
    private boolean passesBuyFilters(int i, BigDecimal closePrice,
                                     BigDecimal[] smaTrend, BigDecimal[] adxSeries,
                                     int trendPeriod, int adxPeriod, BigDecimal adxThreshold) {
        boolean trendOk = true;
        if (trendPeriod > 0) {
            BigDecimal trendVal = smaTrend != null ? smaTrend[i] : null;
            trendOk = trendVal != null && closePrice.compareTo(trendVal) > 0;
        }

        boolean adxOk = true;
        if (adxPeriod > 0) {
            BigDecimal adxVal = adxSeries != null ? adxSeries[i] : null;
            adxOk = adxVal != null && adxVal.compareTo(adxThreshold) >= 0;
        }

        return trendOk && adxOk;
    }

    /** Sumas prefijas exactas de los precios de cierre: prefix[k] = sum(closes[0..k-1]). */
    private BigDecimal[] prefixSums(List<Candle> candles) {
        int n = candles.size();
        BigDecimal[] prefix = new BigDecimal[n + 1];
        prefix[0] = BigDecimal.ZERO;
        for (int k = 1; k <= n; k++) {
            prefix[k] = prefix[k - 1].add(candles.get(k - 1).close());
        }
        return prefix;
    }

    /**
     * Serie de SMA alineada con los índices de velas. SMA[i] = null si i < period-1.
     * Coincide exactamente con StrategyService.sma (suma/period, escala 8 HALF_UP).
     */
    private BigDecimal[] smaSeries(BigDecimal[] prefix, int n, int period) {
        BigDecimal[] sma = new BigDecimal[n];
        BigDecimal divisor = BigDecimal.valueOf(period);
        for (int i = period - 1; i < n; i++) {
            BigDecimal sum = prefix[i + 1].subtract(prefix[i - period + 1]);
            sma[i] = sum.divide(divisor, SCALE, RoundingMode.HALF_UP);
        }
        return sma;
    }

    private BigDecimal applySlippage(BigDecimal price, boolean isBuy) {
        BigDecimal factor = slippagePct.divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal adjusted;
        if (isBuy) {
            adjusted = price.multiply(BigDecimal.ONE.add(factor));
        } else {
            adjusted = price.multiply(BigDecimal.ONE.subtract(factor));
        }
        return adjusted.setScale(SCALE, RoundingMode.HALF_UP);
    }
}

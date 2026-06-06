package com.xpl0day.bot.backtest;

import com.xpl0day.bot.config.BotProperties;
import com.xpl0day.bot.marketdata.Candle;
import com.xpl0day.bot.strategy.Signal;
import com.xpl0day.bot.strategy.TrendFilteredStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Reproduce la estrategia SMA vela a vela sobre datos históricos, con soporte
 * opcional para el filtro de tendencia de largo plazo.
 *
 * <p>Simula condiciones reales:
 * <ul>
 *   <li>Comisión (%) aplicada en entrada y salida.</li>
 *   <li>Slippage (%) aplicado al precio de ejecución.</li>
 *   <li>Stop-loss desde {@code risk.stopLossPercent}.</li>
 *   <li>Filtro de tendencia: BUY solo si precio > SMA(trendPeriod).
 *       trendPeriod=0 desactiva el filtro.</li>
 * </ul>
 *
 * <p>Estilo: ningún {@code return} dentro de bloque condicional.
 */
@Component
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TrendFilteredStrategyService strategyService;
    private final BotProperties properties;

    private BigDecimal commissionPct = new BigDecimal("0.1");
    private BigDecimal slippagePct   = new BigDecimal("0.05");

    public BacktestEngine(TrendFilteredStrategyService strategyService, BotProperties properties) {
        this.strategyService = strategyService;
        this.properties = properties;
    }

    public void setCommissionPct(BigDecimal commissionPct) { this.commissionPct = commissionPct; }
    public void setSlippagePct(BigDecimal slippagePct)     { this.slippagePct = slippagePct; }

    /**
     * Ejecuta el backtest sobre la lista de velas proporcionada.
     *
     * @param candles lista histórica en orden cronológico
     * @param capital capital inicial en USDT
     */
    public BacktestResult run(List<Candle> candles, BigDecimal capital) {
        int fastPeriod   = properties.trading().fastPeriod();
        int slowPeriod   = properties.trading().slowPeriod();
        int trendPeriod  = properties.trading().trendPeriod();
        BigDecimal stopLossPct = properties.risk().stopLossPercent();

        // Con filtro activo esperamos también a tener trendPeriod velas
        int minForStrategy = slowPeriod + 1;
        int minForTrend    = trendPeriod > 0 ? trendPeriod : 0;
        int minCandles     = Math.max(minForStrategy, minForTrend);

        BigDecimal currentCapital = capital;
        BigDecimal peakCapital    = capital;
        BigDecimal maxDrawdown    = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;

        boolean hasPosition  = false;
        BigDecimal positionQty = BigDecimal.ZERO;
        BigDecimal entryPrice  = BigDecimal.ZERO;

        int totalTrades   = 0;
        int winningTrades = 0;
        int losingTrades  = 0;

        log.info("Iniciando backtest: {} velas, capital={} USDT, comision={}%, slippage={}%, trendPeriod={}",
                candles.size(), capital, commissionPct, slippagePct,
                trendPeriod > 0 ? trendPeriod : "OFF");

        for (int i = minCandles; i < candles.size(); i++) {
            List<Candle> window  = candles.subList(0, i + 1);
            Candle current       = candles.get(i);
            BigDecimal closePrice = current.close();

            // Stop-loss
            boolean stopTriggered = false;
            if (hasPosition) {
                BigDecimal stopLevel = entryPrice.multiply(
                        BigDecimal.ONE.subtract(stopLossPct.divide(HUNDRED, 8, RoundingMode.HALF_UP)));
                stopTriggered = closePrice.compareTo(stopLevel) <= 0;
            }

            Signal signal = strategyService.evaluate(window, fastPeriod, slowPeriod, trendPeriod);

            boolean shouldSell = hasPosition && (signal == Signal.SELL || stopTriggered);
            boolean shouldBuy  = !hasPosition && signal == Signal.BUY;

            // Cerrar posicion
            if (shouldSell) {
                BigDecimal exitPrice  = applySlippage(closePrice, false);
                BigDecimal proceeds   = positionQty.multiply(exitPrice);
                BigDecimal commission = proceeds.multiply(commissionPct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
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

                String reason = stopTriggered ? "STOP-LOSS" : "SELL";
                log.debug("[{}] {} precio={} qty={} PnL={} capital={}",
                        i, reason, exitPrice, positionQty,
                        pnl.setScale(2, RoundingMode.HALF_UP),
                        currentCapital.setScale(2, RoundingMode.HALF_UP));

                hasPosition  = false;
                positionQty  = BigDecimal.ZERO;
                entryPrice   = BigDecimal.ZERO;
            }

            // Abrir posicion
            if (shouldBuy) {
                BigDecimal fillPrice   = applySlippage(closePrice, true);
                BigDecimal commission  = currentCapital.multiply(commissionPct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
                BigDecimal afterFee    = currentCapital.subtract(commission);
                totalCommission        = totalCommission.add(commission);
                positionQty            = afterFee.divide(fillPrice, 8, RoundingMode.HALF_UP);
                entryPrice             = fillPrice;
                currentCapital         = BigDecimal.ZERO;
                hasPosition            = true;

                log.debug("[{}] BUY precio={} qty={}", i, fillPrice, positionQty);
            }

            // Valoracion a mercado para drawdown
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

        // Cerrar posicion abierta al precio de la ultima vela
        BigDecimal finalCapital = currentCapital;
        if (hasPosition) {
            BigDecimal lastPrice  = candles.get(candles.size() - 1).close();
            BigDecimal exitPrice  = applySlippage(lastPrice, false);
            BigDecimal proceeds   = positionQty.multiply(exitPrice);
            BigDecimal commission = proceeds.multiply(commissionPct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
            finalCapital          = proceeds.subtract(commission);
            totalCommission       = totalCommission.add(commission);
        }

        BigDecimal netReturnPct = finalCapital.subtract(capital)
                .multiply(HUNDRED)
                .divide(capital, 4, RoundingMode.HALF_UP);

        // Buy&hold: primera vela valida hasta la ultima
        BigDecimal firstClose = candles.get(minCandles).close();
        BigDecimal lastClose  = candles.get(candles.size() - 1).close();
        BigDecimal buyAndHoldPct = lastClose.subtract(firstClose)
                .multiply(HUNDRED)
                .divide(firstClose, 4, RoundingMode.HALF_UP);

        log.info("Backtest finalizado: trades={} ganadores={} capital_final={} rentabilidad={}%",
                totalTrades, winningTrades,
                finalCapital.setScale(2, RoundingMode.HALF_UP), netReturnPct);

        return new BacktestResult(
                totalTrades, winningTrades, losingTrades,
                capital,
                finalCapital.setScale(2, RoundingMode.HALF_UP),
                netReturnPct,
                maxDrawdown,
                buyAndHoldPct,
                totalCommission.setScale(2, RoundingMode.HALF_UP),
                candles.size()
        );
    }

    private BigDecimal applySlippage(BigDecimal price, boolean isBuy) {
        BigDecimal factor = slippagePct.divide(HUNDRED, 8, RoundingMode.HALF_UP);
        BigDecimal adjusted;
        if (isBuy) {
            adjusted = price.multiply(BigDecimal.ONE.add(factor));
        } else {
            adjusted = price.multiply(BigDecimal.ONE.subtract(factor));
        }
        return adjusted.setScale(8, RoundingMode.HALF_UP);
    }
}

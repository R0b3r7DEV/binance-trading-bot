package com.xpl0day.bot.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resumen de los resultados de un backtest. Inmutable.
 */
public final class BacktestResult {

    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final BigDecimal initialCapital;
    private final BigDecimal finalCapital;
    private final BigDecimal netReturnPct;
    private final BigDecimal maxDrawdownPct;
    private final BigDecimal buyAndHoldReturnPct;
    private final BigDecimal totalCommissionPaid;
    private final int candlesProcessed;

    public BacktestResult(
            int totalTrades,
            int winningTrades,
            int losingTrades,
            BigDecimal initialCapital,
            BigDecimal finalCapital,
            BigDecimal netReturnPct,
            BigDecimal maxDrawdownPct,
            BigDecimal buyAndHoldReturnPct,
            BigDecimal totalCommissionPaid,
            int candlesProcessed) {
        this.totalTrades = totalTrades;
        this.winningTrades = winningTrades;
        this.losingTrades = losingTrades;
        this.initialCapital = initialCapital;
        this.finalCapital = finalCapital;
        this.netReturnPct = netReturnPct;
        this.maxDrawdownPct = maxDrawdownPct;
        this.buyAndHoldReturnPct = buyAndHoldReturnPct;
        this.totalCommissionPaid = totalCommissionPaid;
        this.candlesProcessed = candlesProcessed;
    }

    public int getTotalTrades()       { return totalTrades; }
    public int getWinningTrades()     { return winningTrades; }
    public int getLosingTrades()      { return losingTrades; }
    public BigDecimal getInitialCapital()      { return initialCapital; }
    public BigDecimal getFinalCapital()        { return finalCapital; }
    public BigDecimal getNetReturnPct()        { return netReturnPct; }
    public BigDecimal getMaxDrawdownPct()      { return maxDrawdownPct; }
    public BigDecimal getBuyAndHoldReturnPct() { return buyAndHoldReturnPct; }
    public BigDecimal getTotalCommissionPaid() { return totalCommissionPaid; }
    public int getCandlesProcessed()  { return candlesProcessed; }

    public BigDecimal winRatePct() {
        BigDecimal winRate = BigDecimal.ZERO;
        if (totalTrades > 0) {
            winRate = BigDecimal.valueOf(winningTrades)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);
        }
        return winRate;
    }

    @Override
    public String toString() {
        return String.format(
                "+------------------------------------------------------+%n"
                + "|            RESULTADO DEL BACKTEST                    |%n"
                + "+------------------------------------------------------+%n"
                + "|  Velas procesadas     : %6d                        |%n"
                + "|  Operaciones totales  : %6d                        |%n"
                + "|  Ganadoras            : %6d  (%.2f%%)              |%n"
                + "|  Perdedoras           : %6d                        |%n"
                + "+------------------------------------------------------+%n"
                + "|  Capital inicial      : %12.2f USDT             |%n"
                + "|  Capital final        : %12.2f USDT             |%n"
                + "|  Rentabilidad neta    : %+12.2f%%                 |%n"
                + "|  Comisiones pagadas   : %12.2f USDT             |%n"
                + "+------------------------------------------------------+%n"
                + "|  Drawdown maximo      : %12.2f%%                 |%n"
                + "|  Buy & Hold           : %+12.2f%%                 |%n"
                + "+------------------------------------------------------+%n",
                candlesProcessed,
                totalTrades,
                winningTrades, winRatePct(),
                losingTrades,
                initialCapital,
                finalCapital,
                netReturnPct,
                totalCommissionPaid,
                maxDrawdownPct,
                buyAndHoldReturnPct
        );
    }
}

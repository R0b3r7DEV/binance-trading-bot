package com.xpl0day.bot.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "backtest")
public record BacktestConfig(
        BigDecimal initialCapital,
        int totalCandles,
        BigDecimal commissionPct,
        BigDecimal slippagePct,
        List<PeriodConfig> periods
) {
    /**
     * Define un periodo temporal con nombre y fechas en formato ISO (YYYY-MM-DD).
     * Las fechas se convierten a epoch ms en {@link MultiPeriodRunner}.
     */
    public record PeriodConfig(String name, String startDate, String endDate) {}

    public BacktestConfig {
        if (initialCapital == null) initialCapital = new BigDecimal("1000");
        if (totalCandles == 0)      totalCandles = 2000;
        if (commissionPct == null)  commissionPct = new BigDecimal("0.1");
        if (slippagePct == null)    slippagePct   = new BigDecimal("0.05");
        if (periods == null)        periods = List.of();
    }
}

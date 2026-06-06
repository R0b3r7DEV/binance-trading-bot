package com.xpl0day.bot.backtest;

import java.math.BigDecimal;

/**
 * Combinación de parámetros barridos en el grid search out-of-sample.
 * trendPeriod y adxPeriod se mantienen fijos (de la configuración);
 * lo que se optimiza es la pareja de medias y el umbral de ADX.
 *
 * @param fastPeriod   periodo de la SMA rápida
 * @param slowPeriod   periodo de la SMA lenta
 * @param adxThreshold umbral mínimo de ADX para permitir BUY
 */
public record OutOfSampleParams(int fastPeriod, int slowPeriod, BigDecimal adxThreshold) {

    @Override
    public String toString() {
        return String.format("fast=%d slow=%d adxThr=%s", fastPeriod, slowPeriod, adxThreshold.toPlainString());
    }
}

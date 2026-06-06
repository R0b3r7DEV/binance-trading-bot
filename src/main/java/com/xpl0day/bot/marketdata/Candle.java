package com.xpl0day.bot.marketdata;

import java.math.BigDecimal;

/**
 * Una vela (kline) de mercado. Usamos BigDecimal para precios: nunca double
 * en cálculos de dinero, porque los errores de redondeo se acumulan.
 *
 * @param openTime   marca de tiempo de apertura (ms epoch)
 * @param open       precio de apertura
 * @param high       máximo del periodo
 * @param low        mínimo del periodo
 * @param close      precio de cierre (el más usado en indicadores)
 * @param volume     volumen negociado
 * @param closeTime  marca de tiempo de cierre (ms epoch)
 */
public record Candle(
        long openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        long closeTime
) {
}

package com.xpl0day.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuración central del bot. Se rellena desde application.yml / variables
 * de entorno. Las claves NUNCA se escriben aquí: vienen de BINANCE_API_KEY y
 * BINANCE_API_SECRET en el entorno.
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Binance binance,
        Trading trading,
        Risk risk
) {
    public record Binance(
            String apiKey,
            String apiSecret,
            String baseUrl,   // testnet: https://testnet.binance.vision
            boolean testnet
    ) {}

    public record Trading(
            String symbol,        // ej. BTCUSDT
            String interval,      // ej. 1h
            int candleLimit,      // cuántas velas pedir (ej. 100)
            int fastPeriod,       // media rápida (ej. 20)
            int slowPeriod,       // media lenta (ej. 50)
            int trendPeriod,      // SMA de largo plazo para filtro de tendencia (0 = desactivado)
            int adxPeriod,        // periodo del ADX (ej. 14; 0 = filtro ADX desactivado)
            BigDecimal adxThreshold // umbral mínimo de ADX para abrir posición (ej. 25)
    ) {}

    public record Risk(
            BigDecimal maxPositionQuote,   // tamaño máx por operación en USDT
            BigDecimal stopLossPercent,    // % de stop-loss (ej. 2.0)
            BigDecimal maxDailyLossQuote,  // pérdida diaria máx antes de parar
            boolean killSwitch             // si true, el bot no opera
    ) {}
}

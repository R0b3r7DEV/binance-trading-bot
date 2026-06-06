package com.xpl0day.bot.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import com.xpl0day.bot.marketdata.Candle;

/**
 * Lanza el backtest automáticamente cuando el perfil "backtest" está activo.
 *
 * Uso: añade {@code --spring.profiles.active=backtest} al arrancar la app.
 * No ejecuta órdenes reales ni necesita base de datos ni claves de API.
 * Carga datos del endpoint público de Binance (sin autenticación).
 *
 * Parámetros configurables en application.yml bajo backtest.* (con defaults):
 *   backtest.initial-capital  (default: 1000 USDT)
 *   backtest.total-candles    (default: 2000)
 *   backtest.commission-pct   (default: 0.1)
 *   backtest.slippage-pct     (default: 0.05)
 */
@Component
@Profile("backtest")
public class BacktestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    private final HistoricalDataLoader loader;
    private final BacktestEngine engine;
    private final BacktestConfig config;

    public BacktestRunner(HistoricalDataLoader loader, BacktestEngine engine, BacktestConfig config) {
        this.loader = loader;
        this.engine = engine;
        this.config = config;
    }

    @Override
    public void run(String... args) {
        log.info("=== MODO BACKTEST ACTIVO ===");

        engine.setCommissionPct(config.commissionPct());
        engine.setSlippagePct(config.slippagePct());

        List<Candle> candles = loader.loadFromBinance(config.totalCandles());

        BacktestResult result = new BacktestResult(0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

        if (candles.size() > 0) {
            result = engine.run(candles, config.initialCapital());
        } else {
            log.error("No se pudieron cargar velas históricas. Verifica la URL de Binance y la red.");
        }

        System.out.println();
        System.out.println(result);
        System.out.println();
        System.out.println("NOTA: Estos resultados son simulaciones sobre datos historicos. " +
                "El rendimiento pasado no garantiza resultados futuros. Esto no es asesoramiento " +
                "financiero. Cualquier decision de inversion es responsabilidad exclusiva del usuario.");
        System.out.println();
    }
}

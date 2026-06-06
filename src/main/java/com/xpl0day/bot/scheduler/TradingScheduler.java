package com.xpl0day.bot.scheduler;

import com.xpl0day.bot.config.BotProperties;
import com.xpl0day.bot.execution.ExecutionService;
import com.xpl0day.bot.execution.ExecutionService.OrderResult;
import com.xpl0day.bot.marketdata.Candle;
import com.xpl0day.bot.marketdata.MarketDataService;
import com.xpl0day.bot.risk.RiskManager;
import com.xpl0day.bot.state.Position;
import com.xpl0day.bot.state.StateManager;
import com.xpl0day.bot.strategy.Signal;
import com.xpl0day.bot.strategy.TrendFilteredStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Ciclo principal del bot. Orquesta cada ejecución en el orden:
 *   datos de mercado → indicadores → señal → riesgo → ejecución → estado
 *
 * <p><b>Kill-switch = true (modo observación)</b>: el ciclo completo se ejecuta y
 * registra, pero RiskManager bloquea cualquier orden antes de enviarla.
 *
 * <p><b>Robustez</b>: cualquier excepción inesperada queda capturada y registrada;
 * el scheduler nunca muere por un fallo puntual de red o de API.
 */
@Component
@Profile("!backtest & !backtest-multi & !oos")
public class TradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingScheduler.class);
    private static final String SEP =
            "------------------------------------------------------------";

    private final BotProperties                properties;
    private final MarketDataService            marketDataService;
    private final TrendFilteredStrategyService strategyService;
    private final RiskManager                  riskManager;
    private final ExecutionService             executionService;
    private final StateManager                 stateManager;

    public TradingScheduler(
            BotProperties properties,
            MarketDataService marketDataService,
            TrendFilteredStrategyService strategyService,
            RiskManager riskManager,
            ExecutionService executionService,
            StateManager stateManager) {
        this.properties       = properties;
        this.marketDataService = marketDataService;
        this.strategyService  = strategyService;
        this.riskManager      = riskManager;
        this.executionService = executionService;
        this.stateManager     = stateManager;
    }

    // -----------------------------------------------------------------------
    // Ciclo principal
    // -----------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${bot-scheduler.fixed-delay-ms}")
    public void runCycle() {
        // Captura de seguridad al nivel más alto: evita que el scheduler muera
        // por una excepción no prevista (timeout de red, bug de parseo, etc.).
        try {
            runCycleInternal();
        } catch (Exception e) {
            log.error("[CICLO] Excepcion inesperada en el ciclo: {}. Scheduler sigue activo.",
                    e.getMessage(), e);
        }
    }

    private void runCycleInternal() {
        String symbol    = properties.trading().symbol();
        String interval  = properties.trading().interval();
        int fast         = properties.trading().fastPeriod();
        int slow         = properties.trading().slowPeriod();
        int trend        = properties.trading().trendPeriod();
        boolean killOn   = properties.risk().killSwitch();
        String ts        = LocalDateTime.now().toString().replace("T", " ").substring(0, 19);

        log.info("[CICLO] {} | {}/{} | kill-switch={}",
                ts, symbol, interval, killOn ? "ACTIVO (sin ordenes)" : "INACTIVO (modo real)");

        // 1. Datos de mercado — si falla, el error ya fue registrado por MarketDataService
        List<Candle> candles = marketDataService.getCandles();
        boolean hasData = !candles.isEmpty();

        if (!hasData) {
            log.warn("[CICLO] Sin velas disponibles — ciclo omitido hasta el proximo tick");
        }

        if (hasData) {
            runWithMarketData(candles, symbol, fast, slow, trend);
        }

        log.info("[CICLO] {}", SEP);
    }

    /**
     * Segunda fase del ciclo: indicadores, señal, riesgo, ejecución y estado final.
     * Solo se ejecuta cuando hay velas disponibles y suficientes para los indicadores.
     */
    private void runWithMarketData(List<Candle> candles, String symbol,
                                   int fast, int slow, int trend) {
        // Guardia de datos: necesitamos al menos trend+1 velas (o slow+1 si no hay filtro)
        // para que todos los indicadores se calculen. Sin suficientes datos nunca operamos.
        int minRequired = trend > 0 ? trend + 1 : slow + 1;
        boolean sufficient = candles.size() >= minRequired;

        if (!sufficient) {
            int indicator = trend > 0 ? trend : slow;
            String label  = trend > 0 ? "SMA" + trend + " (tendencia)" : "SMA" + slow + " (lenta)";
            log.warn("[CICLO] DATOS INSUFICIENTES: {} velas disponibles — se necesitan {} para {}. "
                    + "Ciclo abortado sin operar.", candles.size(), minRequired, label);
        }

        if (sufficient) {
            runCycleCore(candles, symbol, fast, slow, trend);
        }
    }

    /** Lógica principal del ciclo cuando hay datos suficientes. */
    private void runCycleCore(List<Candle> candles, String symbol,
                              int fast, int slow, int trend) {
        // 2. Indicadores —————————————————————————————————————————————————
        BigDecimal currentPrice = candles.get(candles.size() - 1).close();
        String fastStr  = smaLabel(candles, fast);
        String slowStr  = smaLabel(candles, slow);
        String trendStr = trend > 0 ? smaLabel(candles, trend) : "DESACTIVADO";

        log.info("[CICLO] Precio: {} USDT  | Velas: {}",
                currentPrice.setScale(2, RoundingMode.HALF_UP), candles.size());
        log.info("[CICLO] SMA{}: {}  |  SMA{}: {}  |  SMA{}: {}",
                fast, fastStr, slow, slowStr,
                trend > 0 ? trend : "tendencia", trendStr);

        // 3. Señal de estrategia ——————————————————————————————————————————
        Signal signal = strategyService.evaluate(candles, fast, slow, trend);
        log.info("[CICLO] Señal estrategia: {}", signal);

        // 4. Filtro de riesgo ——————————————————————————————————————————
        boolean hasPosition  = stateManager.hasOpenPosition(symbol);
        BigDecimal dailyLoss = stateManager.getDailyLoss();
        boolean allowed      = riskManager.isAllowed(signal, hasPosition, dailyLoss);

        String posDesc = buildPositionDesc(symbol, hasPosition);
        log.info("[CICLO] RiskManager: {}  |  Posicion: {}  |  Perdida diaria: {} USDT",
                allowed ? "PERMITIDO" : "BLOQUEADO",
                posDesc, dailyLoss.setScale(2, RoundingMode.HALF_UP));

        // 5. Ejecución ————————————————————————————————————————————————
        if (allowed) {
            executeBuyIfSignalled(signal, symbol);
            executeSellIfSignalled(signal, symbol);
        }

        // 6. Estado final ——————————————————————————————————————————————
        boolean finalHasPos = stateManager.hasOpenPosition(symbol);
        log.info("[CICLO] Estado final posicion: {}",
                buildPositionDesc(symbol, finalHasPos));
    }

    // -----------------------------------------------------------------------
    // Ejecución de órdenes
    // -----------------------------------------------------------------------

    private void executeBuyIfSignalled(Signal signal, String symbol) {
        if (signal == Signal.BUY) {
            BigDecimal quoteSize = riskManager.positionSizeQuote(properties.risk().maxPositionQuote());
            log.info("[CICLO] Enviando COMPRA MARKET: {} USDT en {}", quoteSize, symbol);

            Optional<OrderResult> orderOpt = executionService.executeBuy(quoteSize);
            boolean filled = orderOpt.isPresent();
            if (filled) {
                OrderResult order = orderOpt.get();
                stateManager.openPosition(symbol, order.avgPrice(), order.executedQty());
                log.info("[CICLO] COMPRA confirmada: orderId={} qty={} precioMedio={} USDT",
                        order.orderId(), order.executedQty(),
                        order.avgPrice().setScale(2, RoundingMode.HALF_UP));
            } else {
                log.warn("[CICLO] COMPRA no completada — se reintentara en el proximo ciclo si la senal persiste");
            }
        }
    }

    private void executeSellIfSignalled(Signal signal, String symbol) {
        if (signal == Signal.SELL) {
            Optional<Position> posOpt = stateManager.getPosition(symbol);
            boolean hasPos = posOpt.isPresent();
            if (hasPos) {
                BigDecimal quantity = posOpt.get().getQuantity();
                log.info("[CICLO] Enviando VENTA MARKET: {} {} en {}", quantity, symbol, symbol);

                Optional<OrderResult> orderOpt = executionService.executeSell(quantity);
                boolean filled = orderOpt.isPresent();
                if (filled) {
                    OrderResult order = orderOpt.get();
                    stateManager.closePosition(symbol, order.avgPrice());
                    log.info("[CICLO] VENTA confirmada: orderId={} qty={} precioMedio={} USDT",
                            order.orderId(), order.executedQty(),
                            order.avgPrice().setScale(2, RoundingMode.HALF_UP));
                } else {
                    log.warn("[CICLO] VENTA no completada — posicion sigue abierta");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers de logging
    // -----------------------------------------------------------------------

    /**
     * Calcula y formatea la SMA de los últimos {@code period} cierres.
     * Devuelve "(N/A)" si no hay suficientes velas o period <= 0.
     */
    private String smaLabel(List<Candle> candles, int period) {
        String label = "(N/A)";
        boolean canCompute = period > 0 && candles.size() >= period;
        if (canCompute) {
            BigDecimal sum = BigDecimal.ZERO;
            int from = candles.size() - period;
            for (int i = from; i < candles.size(); i++) {
                sum = sum.add(candles.get(i).close());
            }
            BigDecimal sma = sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
            label = sma.toPlainString();
        }
        return label;
    }

    /** Construye una descripción legible del estado de posición actual. */
    private String buildPositionDesc(String symbol, boolean hasPosition) {
        String desc = "NINGUNA";
        if (hasPosition) {
            Optional<Position> opt = stateManager.getPosition(symbol);
            boolean present = opt.isPresent();
            if (present) {
                Position p = opt.get();
                desc = String.format("ABIERTA qty=%s entrada=%s USDT",
                        p.getQuantity().toPlainString(),
                        p.getEntryPrice().setScale(2, RoundingMode.HALF_UP).toPlainString());
            } else {
                desc = "ABIERTA (sin datos en BD)";
            }
        }
        return desc;
    }
}

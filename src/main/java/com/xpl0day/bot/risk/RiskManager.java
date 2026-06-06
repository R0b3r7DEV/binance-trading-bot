package com.xpl0day.bot.risk;

import com.xpl0day.bot.config.BotProperties;
import com.xpl0day.bot.strategy.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Filtro de riesgo. Toda señal de la estrategia pasa por aquí ANTES de
 * convertirse en una orden real. Esta clase es la que más veces te salvará
 * de una pérdida grande.
 *
 * Comprueba, en orden:
 *  1. Kill switch global.
 *  2. Límite de pérdida diaria.
 *  3. Coherencia de la señal con la posición actual
 *     (no comprar si ya estás comprado, no vender si no tienes nada).
 */
@Service
public class RiskManager {

    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    private final BotProperties properties;

    public RiskManager(BotProperties properties) {
        this.properties = properties;
    }

    /**
     * Decide si una señal puede ejecutarse.
     *
     * @param signal         señal emitida por la estrategia
     * @param hasOpenPosition si actualmente tenemos posición abierta
     * @param realizedDailyLoss pérdida acumulada hoy (valor positivo = pérdida)
     * @return true si la operación está permitida
     */
    public boolean isAllowed(Signal signal, boolean hasOpenPosition, BigDecimal realizedDailyLoss) {
        boolean allowed = true;
        String reason = "";

        BotProperties.Risk risk = properties.risk();

        if (risk.killSwitch()) {
            allowed = false;
            reason = "kill-switch activo";
        } else if (realizedDailyLoss.compareTo(risk.maxDailyLossQuote()) >= 0) {
            allowed = false;
            reason = "límite de pérdida diaria alcanzado";
        } else if (signal == Signal.BUY && hasOpenPosition) {
            allowed = false;
            reason = "ya hay posición abierta, no se compra de nuevo";
        } else if (signal == Signal.SELL && !hasOpenPosition) {
            allowed = false;
            reason = "no hay posición que vender";
        } else if (signal == Signal.HOLD) {
            allowed = false;
            reason = "señal HOLD, nada que hacer";
        }

        if (!allowed) {
            log.info("Señal {} bloqueada por RiskManager: {}", signal, reason);
        }

        return allowed;
    }

    /**
     * Calcula el tamaño de la posición en moneda quote (USDT), respetando el
     * máximo configurado. Nunca devuelve más que maxPositionQuote.
     */
    public BigDecimal positionSizeQuote(BigDecimal availableQuote) {
        BigDecimal max = properties.risk().maxPositionQuote();
        BigDecimal size = availableQuote.min(max);
        return size.max(BigDecimal.ZERO);
    }
}

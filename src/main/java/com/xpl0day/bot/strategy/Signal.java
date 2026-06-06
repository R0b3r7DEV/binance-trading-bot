package com.xpl0day.bot.strategy;

/**
 * Señal que emite la estrategia tras analizar los datos de mercado.
 * El bot solo actúa sobre BUY y SELL; HOLD significa no hacer nada.
 */
public enum Signal {
    BUY,
    SELL,
    HOLD
}

package com.xpl0day.bot.strategy.indicators;

import com.xpl0day.bot.marketdata.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests del indicador ADX.
 *
 * <p>Los valores de referencia se derivan APLICANDO A MANO la fórmula de Wilder
 * (fuente independiente del código), no copiando la salida del propio código:
 *
 * <p><b>Serie alcista pura</b> (H y L suben +1 cada vela, rango constante = 2):
 * para cada vela TR=2, +DM=1, -DM=0. Con period=3 la inicialización Wilder suma
 * los 3 primeros: trS=6, plusS=3, minS=0 →
 *   +DI = 100·3/6 = 50,  -DI = 0,  DX = 100·|50-0|/(50+0) = 100.
 * Como todos los DX = 100, el ADX (media/suavizado de DX) = 100.
 * Resultado esperado: ADX=100, +DI=50, -DI=0.
 *
 * <p><b>Serie bajista pura</b> (espejo): TR=2, +DM=0, -DM=1 →
 *   -DI=50, +DI=0, DX=100, ADX=100.
 *
 * <p><b>Serie lateral (zig-zag)</b>: +DM y -DM se alternan, los DI quedan
 * parecidos y el DX (y por tanto el ADX) baja muy por debajo de 25. La derivación
 * manual paso a paso da ADX ≈ 20.4 (ver test choppy).
 */
class AdxTest {

    private static final double DELTA = 0.01;

    private Candle candle(double high, double low, double close) {
        return new Candle(
                0L,
                BigDecimal.valueOf(close), // open (no afecta al ADX)
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                BigDecimal.ONE,            // volume (no afecta)
                0L);
    }

    @Test
    void alcistaPura_adx100_plusDi50_minusDi0() {
        // 7 velas (= 2*period+1 con period=3). H y L suben +1 cada vela.
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            double high  = 10 + i;
            double low   = 8 + i;
            double close = 9 + i;
            candles.add(candle(high, low, close));
        }

        Adx.Result r = new Adx(3).calculate(candles);

        assertEquals(100.0, r.adx().doubleValue(),     DELTA, "ADX serie alcista pura");
        assertEquals(50.0,  r.plusDi().doubleValue(),  DELTA, "+DI serie alcista pura");
        assertEquals(0.0,   r.minusDi().doubleValue(), DELTA, "-DI serie alcista pura");
        assertTrue(r.isStrongTrend(new BigDecimal("25")), "ADX=100 debe superar umbral 25");
        assertTrue(r.isUptrend(), "+DI > -DI debe indicar tendencia alcista");
    }

    @Test
    void bajistaPura_adx100_minusDi50_plusDi0() {
        // Espejo: H y L bajan -1 cada vela.
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            double high  = 16 - i;
            double low   = 14 - i;
            double close = 15 - i;
            candles.add(candle(high, low, close));
        }

        Adx.Result r = new Adx(3).calculate(candles);

        assertEquals(100.0, r.adx().doubleValue(),     DELTA, "ADX serie bajista pura");
        assertEquals(0.0,   r.plusDi().doubleValue(),  DELTA, "+DI serie bajista pura");
        assertEquals(50.0,  r.minusDi().doubleValue(), DELTA, "-DI serie bajista pura");
        assertFalse(r.isUptrend(), "-DI > +DI debe indicar tendencia bajista");
    }

    @Test
    void lateralZigzag_adxBajo_noTendenciaFuerte() {
        // Zig-zag completo: alterna [12,10,11] y [10,8,9]. ADX derivado a mano ≈ 20.4.
        double[][] swings = {
                {10, 8, 9},
                {12, 10, 11},
                {10, 8, 9},
                {12, 10, 11},
                {10, 8, 9},
                {12, 10, 11},
                {10, 8, 9},
                {12, 10, 11},
                {10, 8, 9}
        };
        List<Candle> candles = new ArrayList<>();
        for (double[] s : swings) {
            candles.add(candle(s[0], s[1], s[2]));
        }

        Adx.Result r = new Adx(3).calculate(candles);

        assertTrue(r.adx().doubleValue() < 25.0,
                "ADX lateral debe estar por debajo de 25 (fue " + r.adx() + ")");
        assertFalse(r.isStrongTrend(new BigDecimal("25")),
                "Mercado lateral NO debe marcarse como tendencia fuerte");
        assertTrue(r.plusDi().doubleValue() > 0, "+DI debe ser > 0 en zig-zag");
        assertTrue(r.minusDi().doubleValue() > 0, "-DI debe ser > 0 en zig-zag");
        // Cota inferior razonable para detectar regresiones groseras
        assertTrue(r.adx().doubleValue() > 10.0,
                "ADX lateral derivado a mano ~20; no debe colapsar a 0 (fue " + r.adx() + ")");
    }

    @Test
    void datosInsuficientes_devuelveCeros() {
        // period=14 requiere 29 velas; con 5 no hay suficientes.
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles.add(candle(10 + i, 8 + i, 9 + i));
        }

        Adx.Result r = new Adx(14).calculate(candles);

        assertEquals(0.0, r.adx().doubleValue(),     DELTA);
        assertEquals(0.0, r.plusDi().doubleValue(),  DELTA);
        assertEquals(0.0, r.minusDi().doubleValue(), DELTA);
    }
}

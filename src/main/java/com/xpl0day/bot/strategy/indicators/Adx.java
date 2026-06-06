package com.xpl0day.bot.strategy.indicators;

import com.xpl0day.bot.marketdata.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Average Directional Index (ADX) con +DI y -DI.
 *
 * <p>Algoritmo de Wilder (1978):
 * <ol>
 *   <li>True Range (TR) = max(H-L, |H-prevC|, |L-prevC|)</li>
 *   <li>+DM = H-prevH si positivo y mayor que prevL-L, si no 0</li>
 *   <li>-DM = prevL-L si positivo y mayor que H-prevH, si no 0</li>
 *   <li>Suavizado Wilder: S[n] = S[n-1] - S[n-1]/period + raw[n]
 *       (inicialización = suma simple de los primeros {@code period} valores)</li>
 *   <li>+DI = 100 * +DM_suav / TR_suav</li>
 *   <li>DX = 100 * |+DI - -DI| / (+DI + -DI)</li>
 *   <li>ADX = suavizado Wilder de DX (requiere {@code period} valores de DX para el primer ADX)</li>
 * </ol>
 *
 * <p>Mínimo de velas necesarias: {@code 2 * period + 1}. El primer valor de ADX
 * disponible corresponde al índice {@code 2*period-1}.
 *
 * <p>Clase pura, sin estado, sin Spring. {@link #calculate(List)} devuelve el valor
 * en la última vela; {@link #adxSeries(List)} devuelve la serie completa en O(n),
 * útil para backtesting eficiente (evita recalcular en cada ventana).
 */
public final class Adx {

    private static final int    SCALE  = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO    = BigDecimal.ZERO;

    private final int period;

    public Adx(int period) {
        if (period < 1) throw new IllegalArgumentException("period debe ser >= 1");
        this.period = period;
    }

    /**
     * Resultado del cálculo en la última vela de la serie.
     *
     * @param adx     ADX (0-100; > 25 indica tendencia significativa)
     * @param plusDi  +DI (fuerza de la tendencia alcista)
     * @param minusDi -DI (fuerza de la tendencia bajista)
     */
    public record Result(BigDecimal adx, BigDecimal plusDi, BigDecimal minusDi) {
        /** true si ADX >= threshold (tendencia fuerte). */
        public boolean isStrongTrend(BigDecimal threshold) {
            return adx.compareTo(threshold) >= 0;
        }
        /** true si +DI > -DI (alcista). */
        public boolean isUptrend() {
            return plusDi.compareTo(minusDi) > 0;
        }
    }

    /**
     * Calcula el ADX sobre la lista completa y devuelve el valor en la última vela.
     * Si no hay suficientes datos, devuelve {@code Result(0, 0, 0)}.
     */
    public Result calculate(List<Candle> candles) {
        boolean sufficient = candles != null && candles.size() >= 2 * period + 1;
        Result result = new Result(ZERO, ZERO, ZERO);
        if (sufficient) {
            Computed c = compute(candles);
            BigDecimal lastAdx = c.adx[candles.size() - 1];
            BigDecimal adxValue = lastAdx != null ? lastAdx : ZERO;
            result = new Result(adxValue, c.plusDiLast, c.minusDiLast);
        }
        return result;
    }

    /**
     * Serie completa de ADX alineada con {@code candles}. Las posiciones donde el
     * ADX aún no es calculable (antes del índice {@code 2*period-1}) quedan en
     * {@code null}. Si no hay datos suficientes, todas las posiciones son {@code null}.
     *
     * <p>Coste O(n): pensado para backtesting sin recalcular en cada ventana.
     */
    public BigDecimal[] adxSeries(List<Candle> candles) {
        int n = candles != null ? candles.size() : 0;
        BigDecimal[] series = new BigDecimal[n];
        boolean sufficient = n >= 2 * period + 1;
        if (sufficient) {
            Computed c = compute(candles);
            System.arraycopy(c.adx, 0, series, 0, n);
        }
        return series;
    }

    // -----------------------------------------------------------------------
    // privados
    // -----------------------------------------------------------------------

    /** Empaqueta la serie de ADX y los últimos +DI/-DI calculados. */
    private record Computed(BigDecimal[] adx, BigDecimal plusDiLast, BigDecimal minusDiLast) {}

    private Computed compute(List<Candle> candles) {
        int n = candles.size();

        BigDecimal[] tr  = new BigDecimal[n];
        BigDecimal[] pDm = new BigDecimal[n];
        BigDecimal[] mDm = new BigDecimal[n];
        tr[0] = ZERO; pDm[0] = ZERO; mDm[0] = ZERO;

        for (int i = 1; i < n; i++) {
            BigDecimal[] raw = rawValues(candles, i);
            tr[i]  = raw[0];
            pDm[i] = raw[1];
            mDm[i] = raw[2];
        }

        // Inicialización Wilder: suma simple de los primeros `period` valores (índices 1..period)
        BigDecimal trS   = ZERO;
        BigDecimal plusS = ZERO;
        BigDecimal minS  = ZERO;
        for (int i = 1; i <= period; i++) {
            trS   = trS.add(tr[i]);
            plusS = plusS.add(pDm[i]);
            minS  = minS.add(mDm[i]);
        }

        BigDecimal[] adx = new BigDecimal[n]; // null donde no es calculable

        // Acumular los primeros `period` valores de DX para el primer ADX
        BigDecimal dxSum = computeDx(trS, plusS, minS); // DX en índice `period`
        for (int i = period + 1; i < 2 * period; i++) {
            trS   = wilderUpdate(trS,  tr[i]);
            plusS = wilderUpdate(plusS, pDm[i]);
            minS  = wilderUpdate(minS,  mDm[i]);
            dxSum = dxSum.add(computeDx(trS, plusS, minS));
        }

        // Primer ADX = media de los `period` primeros DX, en índice 2*period-1
        BigDecimal prevAdx = dxSum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        int firstAdxIndex = 2 * period - 1;
        if (firstAdxIndex < n) {
            adx[firstAdxIndex] = prevAdx;
        }

        // Suavizado de ADX para el resto de velas
        for (int i = 2 * period; i < n; i++) {
            trS   = wilderUpdate(trS,  tr[i]);
            plusS = wilderUpdate(plusS, pDm[i]);
            minS  = wilderUpdate(minS,  mDm[i]);
            BigDecimal dx = computeDx(trS, plusS, minS);
            BigDecimal cur = prevAdx.multiply(BigDecimal.valueOf(period - 1))
                    .add(dx)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            adx[i] = cur;
            prevAdx = cur;
        }

        BigDecimal plusDiLast  = di(plusS, trS);
        BigDecimal minusDiLast = di(minS, trS);
        return new Computed(adx, plusDiLast, minusDiLast);
    }

    /**
     * Calcula TR, +DM y -DM para la vela en el índice {@code i}.
     * Devuelve [TR, +DM, -DM].
     */
    private BigDecimal[] rawValues(List<Candle> candles, int i) {
        Candle curr = candles.get(i);
        Candle prev = candles.get(i - 1);

        BigDecimal high      = curr.high();
        BigDecimal low       = curr.low();
        BigDecimal prevClose = prev.close();
        BigDecimal prevHigh  = prev.high();
        BigDecimal prevLow   = prev.low();

        // True Range = max(H-L, |H-prevC|, |L-prevC|)
        BigDecimal hl  = high.subtract(low);
        BigDecimal hpc = high.subtract(prevClose).abs();
        BigDecimal lpc = low.subtract(prevClose).abs();
        BigDecimal tr  = hl.max(hpc).max(lpc);

        // Directional movement
        BigDecimal deltaH = high.subtract(prevHigh);
        BigDecimal deltaL = prevLow.subtract(low);

        BigDecimal pDm = ZERO;
        BigDecimal mDm = ZERO;

        boolean deltaHPositive = deltaH.compareTo(ZERO) > 0;
        boolean deltaLPositive = deltaL.compareTo(ZERO) > 0;

        if (deltaHPositive && deltaH.compareTo(deltaL) > 0) {
            pDm = deltaH;
        }
        if (deltaLPositive && deltaL.compareTo(deltaH) > 0) {
            mDm = deltaL;
        }

        return new BigDecimal[]{tr, pDm, mDm};
    }

    /** Wilder's smoothing: S[n] = S[n-1] - S[n-1]/period + raw[n]. */
    private BigDecimal wilderUpdate(BigDecimal prev, BigDecimal newVal) {
        BigDecimal step = prev.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        return prev.subtract(step).add(newVal);
    }

    /** +DI o -DI = 100 * dmSmooth / trSmooth. */
    private BigDecimal di(BigDecimal dmSmooth, BigDecimal trSmooth) {
        BigDecimal result = ZERO;
        boolean trPositive = trSmooth.compareTo(ZERO) > 0;
        if (trPositive) {
            result = HUNDRED.multiply(dmSmooth)
                    .divide(trSmooth, SCALE, RoundingMode.HALF_UP);
        }
        return result;
    }

    /** DX = 100 * |+DI - -DI| / (+DI + -DI). */
    private BigDecimal computeDx(BigDecimal trS, BigDecimal plusS, BigDecimal minS) {
        BigDecimal plusDi  = di(plusS, trS);
        BigDecimal minusDi = di(minS, trS);
        BigDecimal sum = plusDi.add(minusDi);
        BigDecimal dx = ZERO;
        boolean sumPositive = sum.compareTo(ZERO) > 0;
        if (sumPositive) {
            dx = HUNDRED.multiply(plusDi.subtract(minusDi).abs())
                    .divide(sum, SCALE, RoundingMode.HALF_UP);
        }
        return dx;
    }
}

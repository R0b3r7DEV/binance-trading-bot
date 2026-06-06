package com.xpl0day.bot.marketdata;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.spot.rest.api.SpotRestApi;
import com.binance.connector.client.spot.rest.model.Interval;
import com.binance.connector.client.spot.rest.model.KlinesItem;
import com.binance.connector.client.spot.rest.model.KlinesResponse;
import com.xpl0day.bot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Obtiene las últimas N velas del símbolo configurado usando el conector oficial
 * de Binance (io.github.binance:binance-spot). Traduce KlinesItem (ArrayList<String>)
 * al record Candle con BigDecimal para todos los precios.
 */
@Service
@Profile("!backtest & !backtest-multi & !oos")
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final BotProperties properties;
    private final SpotRestApi spotApi;

    public MarketDataService(BotProperties properties, SpotRestApi spotApi) {
        this.properties = properties;
        this.spotApi = spotApi;
    }

    /**
     * Obtiene las últimas {@code candleLimit} velas del símbolo e intervalo
     * configurados en BotProperties. Devuelve lista vacía si la llamada falla
     * (el error ya queda logueado).
     */
    public List<Candle> getCandles() {
        List<Candle> result = new ArrayList<>();
        String symbol = properties.trading().symbol();
        String intervalStr = properties.trading().interval();
        int limit = properties.trading().candleLimit();

        try {
            Interval interval = Interval.fromValue(intervalStr);
            // startTime/endTime null = las más recientes; timeZone null = UTC
            @SuppressWarnings("unchecked")
            ApiResponse<KlinesResponse> response =
                    (ApiResponse<KlinesResponse>) spotApi.klines(symbol, interval, null, null, null, limit);
            KlinesResponse klines = response.getData();

            for (KlinesItem item : klines) {
                // KlinesItem extiende ArrayList<String>:
                //   [0] openTime  [1] open  [2] high  [3] low  [4] close  [5] volume  [6] closeTime
                Candle candle = new Candle(
                        Long.parseLong(item.get(0)),
                        new BigDecimal(item.get(1)),
                        new BigDecimal(item.get(2)),
                        new BigDecimal(item.get(3)),
                        new BigDecimal(item.get(4)),
                        new BigDecimal(item.get(5)),
                        Long.parseLong(item.get(6))
                );
                result.add(candle);
            }
            log.info("Velas obtenidas: {} ({} {})", result.size(), symbol, intervalStr);
        } catch (ApiException e) {
            log.error("Error obteniendo klines para {} (HTTP {}): {}", symbol, e.getCode(), e.getMessage());
        }

        return result;
    }
}

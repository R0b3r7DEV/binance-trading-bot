package com.xpl0day.bot.execution;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.spot.rest.api.SpotRestApi;
import com.binance.connector.client.spot.rest.model.NewOrderRequest;
import com.binance.connector.client.spot.rest.model.NewOrderResponse;
import com.binance.connector.client.spot.rest.model.OrderType;
import com.binance.connector.client.spot.rest.model.Side;
import com.xpl0day.bot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Envía órdenes MARKET de compra y venta al endpoint spot de Binance.
 *
 * <p>BUY: se indica la cantidad en quote (USDT) mediante quoteOrderQty.
 * <p>SELL: se indica la cantidad en base (BTC) mediante quantity.
 *
 * <p>Nota sobre Double: la API del conector acepta Double para qty/price.
 * Las conversiones desde BigDecimal se hacen exclusivamente en el punto
 * de llamada a la API; el resto del bot siempre usa BigDecimal.
 */
@Service
@Profile("!backtest & !backtest-multi & !oos")
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final BotProperties properties;
    private final SpotRestApi spotApi;

    public ExecutionService(BotProperties properties, SpotRestApi spotApi) {
        this.properties = properties;
        this.spotApi = spotApi;
    }

    /**
     * Resultado de una orden ejecutada.
     *
     * @param orderId     identificador de la orden en Binance
     * @param executedQty cantidad base ejecutada (BTC)
     * @param avgPrice    precio promedio de ejecución
     */
    public record OrderResult(Long orderId, BigDecimal executedQty, BigDecimal avgPrice) {}

    /**
     * Coloca una orden de compra MARKET usando quoteOrderQty (USDT).
     * Devuelve vacío si la orden falla (el error queda logueado).
     */
    public Optional<OrderResult> executeBuy(BigDecimal quoteAmount) {
        Optional<OrderResult> result = Optional.empty();
        String symbol = properties.trading().symbol();

        try {
            NewOrderRequest request = new NewOrderRequest()
                    .symbol(symbol)
                    .side(Side.BUY)
                    .type(OrderType.MARKET)
                    .quoteOrderQty(quoteAmount.doubleValue()); // conversión puntual al boundary del API

            @SuppressWarnings("unchecked")
            ApiResponse<NewOrderResponse> response =
                    (ApiResponse<NewOrderResponse>) spotApi.newOrder(request);
            NewOrderResponse order = response.getData();

            BigDecimal executedQty = new BigDecimal(order.getExecutedQty());
            BigDecimal quoteQty = new BigDecimal(order.getCummulativeQuoteQty());
            BigDecimal avgPrice = calcAvgPrice(executedQty, quoteQty);

            result = Optional.of(new OrderResult(order.getOrderId(), executedQty, avgPrice));
            log.info("COMPRA ejecutada — orderId={} qty={} precioMedio={} USDT={} símbolo={}",
                    order.getOrderId(), executedQty, avgPrice, quoteQty, symbol);
        } catch (ApiException e) {
            log.error("Error al ejecutar COMPRA en {} (HTTP {}): {}", symbol, e.getCode(), e.getMessage());
        }

        return result;
    }

    /**
     * Coloca una orden de venta MARKET usando la cantidad en base (BTC).
     * Devuelve vacío si la orden falla (el error queda logueado).
     */
    public Optional<OrderResult> executeSell(BigDecimal quantity) {
        Optional<OrderResult> result = Optional.empty();
        String symbol = properties.trading().symbol();

        try {
            NewOrderRequest request = new NewOrderRequest()
                    .symbol(symbol)
                    .side(Side.SELL)
                    .type(OrderType.MARKET)
                    .quantity(quantity.doubleValue()); // conversión puntual al boundary del API

            @SuppressWarnings("unchecked")
            ApiResponse<NewOrderResponse> response =
                    (ApiResponse<NewOrderResponse>) spotApi.newOrder(request);
            NewOrderResponse order = response.getData();

            BigDecimal executedQty = new BigDecimal(order.getExecutedQty());
            BigDecimal quoteQty = new BigDecimal(order.getCummulativeQuoteQty());
            BigDecimal avgPrice = calcAvgPrice(executedQty, quoteQty);

            result = Optional.of(new OrderResult(order.getOrderId(), executedQty, avgPrice));
            log.info("VENTA ejecutada — orderId={} qty={} precioMedio={} USDT={} símbolo={}",
                    order.getOrderId(), executedQty, avgPrice, quoteQty, symbol);
        } catch (ApiException e) {
            log.error("Error al ejecutar VENTA en {} (HTTP {}): {}", symbol, e.getCode(), e.getMessage());
        }

        return result;
    }

    private BigDecimal calcAvgPrice(BigDecimal executedQty, BigDecimal quoteQty) {
        BigDecimal avgPrice = BigDecimal.ZERO;
        if (executedQty.compareTo(BigDecimal.ZERO) > 0) {
            avgPrice = quoteQty.divide(executedQty, 8, RoundingMode.HALF_UP);
        }
        return avgPrice;
    }
}

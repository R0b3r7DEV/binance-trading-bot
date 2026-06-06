package com.xpl0day.bot.config;

import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.spot.rest.api.SpotRestApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!backtest & !backtest-multi & !oos")
public class BinanceClientConfig {

    @Bean
    public SpotRestApi spotRestApi(BotProperties properties) {
        SignatureConfiguration sigConfig = new SignatureConfiguration();
        sigConfig.setApiKey(properties.binance().apiKey());
        sigConfig.setSecretKey(properties.binance().apiSecret());

        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setUrl(properties.binance().baseUrl());
        clientConfig.setSignatureConfiguration(sigConfig);
        // 3 reintentos con 500 ms de back-off para absorber errores transitorios
        clientConfig.setRetries(3);
        clientConfig.setBackOff(500);

        return new SpotRestApi(clientConfig);
    }
}

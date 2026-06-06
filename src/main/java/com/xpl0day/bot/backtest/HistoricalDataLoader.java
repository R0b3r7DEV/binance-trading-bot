package com.xpl0day.bot.backtest;

import com.xpl0day.bot.config.BotProperties;
import com.xpl0day.bot.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga velas históricas de dos fuentes:
 *  1. API pública de Binance GET /api/v3/klines (sin API key, con paginación).
 *  2. CSV local con columnas: openTime,open,high,low,close,volume,closeTime
 *
 * La paginación va hacia atrás en el tiempo: se descarga primero la página más
 * reciente y se avanza con endTime = (primer openTime de la página anterior - 1).
 * Esto evita problemas de solapamiento y es robusto ante reinicios.
 *
 * Siempre devuelve la lista ordenada cronológicamente (la más reciente al final).
 */
@Component
public class HistoricalDataLoader {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataLoader.class);
    private static final int PAGE_SIZE = 1000; // máximo permitido por Binance
    private static final String KLINES_PATH = "/api/v3/klines";
    // Siempre usamos mainnet para datos históricos: el endpoint es público,
    // no requiere API key y el testnet solo conserva datos muy recientes.
    private static final String MAINNET_BASE = "https://api.binance.com";

    private final BotProperties properties;

    public HistoricalDataLoader(BotProperties properties) {
        this.properties = properties;
    }

    /**
     * Descarga {@code totalCandles} velas históricas del endpoint público de Binance
     * usando paginación hacia atrás. No requiere API key.
     *
     * Estrategia:
     *  - Página 0: sin endTime → Binance devuelve las PAGE_SIZE más recientes.
     *  - Página k: endTime = openTime[0] de la página anterior - 1 ms.
     * Los datos se insertan en orden cronológico al final.
     */
    public List<Candle> loadFromBinance(int totalCandles) {
        String symbol = properties.trading().symbol();
        String interval = properties.trading().interval();
        int pages = (totalCandles + PAGE_SIZE - 1) / PAGE_SIZE;

        log.info("Cargando {} velas de Binance ({} {}, {} paginas)...",
                totalCandles, symbol, interval, pages);

        List<List<Candle>> collectedPages = new ArrayList<>();
        Long endTime = null; // null en la primera iteracion = las más recientes
        boolean hasMoreData = true;

        for (int page = 0; page < pages && hasMoreData; page++) {
            List<Candle> pageCandles = fetchPage(MAINNET_BASE, symbol, interval, endTime);
            hasMoreData = !pageCandles.isEmpty();
            if (hasMoreData) {
                collectedPages.add(0, pageCandles); // prepend para mantener orden cronologico
                endTime = pageCandles.get(0).openTime() - 1; // siguiente pagina termina antes del primer openTime
                log.debug("Pagina {}/{}: {} velas (hasta openTime={})",
                        page + 1, pages, pageCandles.size(), pageCandles.get(0).openTime());
            }
        }

        // Aplanar en orden cronologico (collectedPages[0] = mas antiguo)
        List<Candle> all = new ArrayList<>();
        for (List<Candle> page : collectedPages) {
            all.addAll(page);
        }

        // Recortar al total solicitado (las N mas recientes)
        int fromIndex = Math.max(0, all.size() - totalCandles);
        List<Candle> result = new ArrayList<>(all.subList(fromIndex, all.size()));
        log.info("Carga completa: {} velas obtenidas de Binance", result.size());
        return result;
    }

    /**
     * Carga velas desde un CSV local. Formato (con o sin cabecera):
     * {@code openTime,open,high,low,close,volume,closeTime}
     * donde openTime y closeTime son epoch en milisegundos.
     */
    public List<Candle> loadFromCsv(Path csvPath) {
        List<Candle> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                boolean isHeader = firstLine && !Character.isDigit(line.charAt(0));
                firstLine = false;
                if (!isHeader && !line.isBlank()) {
                    Candle candle = parseCsvLine(line);
                    if (candle != null) {
                        result.add(candle);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error leyendo CSV {}: {}", csvPath, e.getMessage());
        }
        log.info("Carga completa: {} velas leidas de {}", result.size(), csvPath.getFileName());
        return result;
    }

    /**
     * Descarga todas las velas del símbolo e intervalo configurados entre
     * {@code startTimeMs} y {@code endTimeMs} (ambos inclusive, en epoch ms).
     *
     * Usa la misma paginación hacia atrás que {@link #loadFromBinance(int)}:
     * empieza desde {@code endTimeMs} y avanza hacia atrás hasta cubrir
     * {@code startTimeMs}. Nunca supera {@code MAX_PAGES} llamadas HTTP.
     */
    public List<Candle> loadFromBinance(long startTimeMs, long endTimeMs) {
        String symbol = properties.trading().symbol();
        String interval = properties.trading().interval();
        int maxPages = 50; // techo de seguridad: 50 * 1000 = 50 000 velas

        log.info("Cargando velas Binance ({} {}) [{} - {}]...",
                symbol, interval, startTimeMs, endTimeMs);

        List<List<Candle>> collectedPages = new ArrayList<>();
        Long currentEndTime = endTimeMs;
        boolean hasMoreData = true;
        boolean reachedStart = false;
        int pagesFetched = 0;

        while (hasMoreData && !reachedStart && pagesFetched < maxPages) {
            List<Candle> pageCandles = fetchPage(MAINNET_BASE, symbol, interval, currentEndTime);
            hasMoreData = !pageCandles.isEmpty();
            if (hasMoreData) {
                collectedPages.add(0, pageCandles);
                long firstOpenTime = pageCandles.get(0).openTime();
                reachedStart = firstOpenTime <= startTimeMs;
                currentEndTime = firstOpenTime - 1;
                pagesFetched++;
                log.debug("Pagina {}: {} velas (firstOpen={})", pagesFetched, pageCandles.size(), firstOpenTime);
            }
        }

        // Aplanar en orden cronologico
        List<Candle> all = new ArrayList<>();
        for (List<Candle> page : collectedPages) {
            all.addAll(page);
        }

        // Filtrar al rango solicitado exacto
        List<Candle> result = new ArrayList<>();
        for (Candle c : all) {
            boolean inRange = c.openTime() >= startTimeMs && c.openTime() <= endTimeMs;
            if (inRange) {
                result.add(c);
            }
        }

        log.info("Carga completa: {} velas en el rango ({} paginas HTTP)", result.size(), pagesFetched);
        return result;
    }

    // -------------------------------------------------------------------------
    // privados
    // -------------------------------------------------------------------------

    /**
     * Descarga una página de hasta PAGE_SIZE velas.
     *
     * @param endTime epoch ms del límite superior (exclusivo). {@code null} para las más recientes.
     */
    private List<Candle> fetchPage(String baseUrl, String symbol, String interval, Long endTime) {
        List<Candle> page = new ArrayList<>();
        StringBuilder urlSb = new StringBuilder(baseUrl)
                .append(KLINES_PATH)
                .append("?symbol=").append(symbol)
                .append("&interval=").append(interval)
                .append("&limit=").append(PAGE_SIZE);
        if (endTime != null) {
            urlSb.append("&endTime=").append(endTime);
        }
        String urlStr = urlSb.toString();

        try {
            HttpURLConnection conn = openConnection(urlStr);
            int status = conn.getResponseCode();
            if (status == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String body = readAll(reader);
                    page.addAll(parseJsonArray(body));
                }
            } else {
                String errBody = "";
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    errBody = readAll(errReader);
                } catch (Exception ignored) {}
                log.warn("HTTP {} al descargar klines (endTime={}): {}", status, endTime, errBody);
            }
            conn.disconnect();
        } catch (IOException e) {
            log.error("Error de red al obtener klines: {}", e.getMessage());
        }
        return page;
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", "trading-bot-backtest/0.1.0");
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private String readAll(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Parser del array JSON de klines de Binance (sin dependencia extra de Gson).
     * Cada kline: [openTime,"open","high","low","close","volume",closeTime,...]
     */
    private List<Candle> parseJsonArray(String json) {
        List<Candle> candles = new ArrayList<>();
        String trimmed = json.trim();
        boolean validJson = trimmed.length() >= 2
                && trimmed.charAt(0) == '['
                && trimmed.charAt(trimmed.length() - 1) == ']';
        if (validJson) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (!inner.isEmpty()) {
                List<String> items = splitTopLevelArrayItems(inner);
                for (String item : items) {
                    Candle c = parseKlineItem(item.trim());
                    if (c != null) {
                        candles.add(c);
                    }
                }
            }
        }
        return candles;
    }

    /**
     * Divide la cadena JSON por comas de nivel 0 (ignora comas dentro de corchetes).
     */
    private List<String> splitTopLevelArrayItems(String s) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    items.add(s.substring(start, i + 1));
                    int next = i + 1;
                    while (next < s.length() && (s.charAt(next) == ',' || s.charAt(next) == ' ')) {
                        next++;
                    }
                    start = next;
                    i = next - 1;
                }
            }
        }
        return items;
    }

    /**
     * Parsea un elemento kline: [1499040000000,"0.01634790","0.80000000",...]
     * Posiciones: 0=openTime, 1=open, 2=high, 3=low, 4=close, 5=volume, 6=closeTime
     */
    private Candle parseKlineItem(String item) {
        Candle result = null;
        boolean hasContent = item.length() >= 2;
        if (hasContent) {
            try {
                String content = item.substring(1, item.length() - 1);
                String[] parts = content.split(",");
                boolean hasEnoughParts = parts.length >= 7;
                if (hasEnoughParts) {
                    long openTime  = Long.parseLong(parts[0].trim());
                    BigDecimal open   = new BigDecimal(unquote(parts[1]));
                    BigDecimal high   = new BigDecimal(unquote(parts[2]));
                    BigDecimal low    = new BigDecimal(unquote(parts[3]));
                    BigDecimal close  = new BigDecimal(unquote(parts[4]));
                    BigDecimal volume = new BigDecimal(unquote(parts[5]));
                    long closeTime = Long.parseLong(parts[6].trim());
                    result = new Candle(openTime, open, high, low, close, volume, closeTime);
                }
            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                log.warn("No se pudo parsear kline: {}", item);
            }
        }
        return result;
    }

    private String unquote(String s) {
        return s.trim().replace("\"", "");
    }

    private Candle parseCsvLine(String line) {
        Candle result = null;
        try {
            String[] parts = line.split(",");
            boolean hasEnoughParts = parts.length >= 7;
            if (hasEnoughParts) {
                result = new Candle(
                        Long.parseLong(parts[0].trim()),
                        new BigDecimal(parts[1].trim()),
                        new BigDecimal(parts[2].trim()),
                        new BigDecimal(parts[3].trim()),
                        new BigDecimal(parts[4].trim()),
                        new BigDecimal(parts[5].trim()),
                        Long.parseLong(parts[6].trim())
                );
            }
        } catch (NumberFormatException e) {
            log.warn("Linea CSV invalida: {}", line);
        }
        return result;
    }
}

package com.xpl0day.bot.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Centraliza la gestión del estado del bot:
 *  - Posición abierta (persistida en PostgreSQL vía PositionRepository).
 *  - Pérdida diaria acumulada (en memoria; se resetea a medianoche).
 *
 * Es el único punto que escribe/lee la posición, lo que garantiza
 * consistencia entre el scheduler y el risk manager.
 */
@Service
@Profile("!backtest & !backtest-multi & !oos")
public class StateManager {

    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private final PositionRepository positionRepository;

    private BigDecimal dailyLoss = BigDecimal.ZERO;
    private LocalDate lossResetDate = LocalDate.now();

    public StateManager(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public boolean hasOpenPosition(String symbol) {
        return positionRepository.findFirstBySymbol(symbol).isPresent();
    }

    public Optional<Position> getPosition(String symbol) {
        return positionRepository.findFirstBySymbol(symbol);
    }

    public void openPosition(String symbol, BigDecimal entryPrice, BigDecimal quantity) {
        Position position = new Position(symbol, entryPrice, quantity, Instant.now());
        positionRepository.save(position);
        log.info("Posición ABIERTA — símbolo={} qty={} entryPrice={}", symbol, quantity, entryPrice);
    }

    /**
     * Cierra la posición del símbolo, calcula el PnL y actualiza la pérdida diaria
     * si el resultado fue negativo.
     */
    public void closePosition(String symbol, BigDecimal exitPrice) {
        Optional<Position> opt = positionRepository.findFirstBySymbol(symbol);
        if (opt.isPresent()) {
            Position pos = opt.get();
            BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
            BigDecimal exitValue = exitPrice.multiply(pos.getQuantity());
            BigDecimal pnl = exitValue.subtract(entryValue);
            boolean isLoss = pnl.compareTo(BigDecimal.ZERO) < 0;
            if (isLoss) {
                accumulateDailyLoss(pnl.negate());
            }
            positionRepository.delete(pos);
            log.info("Posición CERRADA — símbolo={} qty={} entrada={} salida={} pnl={}",
                    symbol, pos.getQuantity(), pos.getEntryPrice(), exitPrice, pnl);
        }
    }

    public BigDecimal getDailyLoss() {
        resetDailyLossIfNewDay();
        return dailyLoss;
    }

    private void accumulateDailyLoss(BigDecimal loss) {
        resetDailyLossIfNewDay();
        dailyLoss = dailyLoss.add(loss);
        log.info("Pérdida registrada: {} (total hoy: {})", loss, dailyLoss);
    }

    private void resetDailyLossIfNewDay() {
        LocalDate today = LocalDate.now();
        boolean isNewDay = !today.equals(lossResetDate);
        if (isNewDay) {
            dailyLoss = BigDecimal.ZERO;
            lossResetDate = today;
            log.info("Contador de pérdida diaria reiniciado para {}", today);
        }
    }
}

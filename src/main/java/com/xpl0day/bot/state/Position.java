package com.xpl0day.bot.state;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Posición SPOT abierta. Solo puede haber una por símbolo en cualquier momento.
 * La tabla se crea/actualiza automáticamente (ddl-auto: update en application.yml).
 */
@Entity
@Table(name = "position")
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false)
    private Instant openTime;

    public Position() {}

    public Position(String symbol, BigDecimal entryPrice, BigDecimal quantity, Instant openTime) {
        this.symbol = symbol;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.openTime = openTime;
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public Instant getOpenTime() { return openTime; }

    public void setId(Long id) { this.id = id; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public void setOpenTime(Instant openTime) { this.openTime = openTime; }
}

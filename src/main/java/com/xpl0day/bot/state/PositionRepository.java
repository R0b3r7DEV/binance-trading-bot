package com.xpl0day.bot.state;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {

    /** Devuelve la primera posición abierta para el símbolo dado, si existe. */
    Optional<Position> findFirstBySymbol(String symbol);
}

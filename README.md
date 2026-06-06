# Trading Bot — Algorithmic Trading System with Empirical Strategy Validation

> A Spring Boot algorithmic trading bot for Binance (Spot), built as an engineering
> and research project. Beyond the implementation, this project applies **rigorous
> out-of-sample backtesting** to honestly evaluate whether the trading strategy holds
> any real edge — and documents the findings transparently.

*[Versión en español más abajo / Spanish version below](#trading-bot--sistema-de-trading-algorítmico-con-validación-empírica-de-estrategia)*

---

## 🇬🇧 English

### Overview

This is a fully functional algorithmic trading bot that connects to Binance, reads
market data, evaluates a trading strategy, and executes spot orders — with state
persistence, risk management, and a safe-by-default design. It was developed and
validated entirely against Binance's **testnet** (no real funds).

The project has two halves:

1. **The engineering** — a clean, production-shaped Spring Boot application.
2. **The research** — an empirical study, using out-of-sample validation, of whether
   the implemented technical strategy actually beats a simple buy-and-hold baseline.

> **Honest finding:** across three increasingly rigorous evaluations, the technical
> strategies tested (SMA crossover, with trend filter, with ADX strength filter) did
> **not** demonstrate a persistent edge over buy-and-hold on out-of-sample data. This
> result is documented openly rather than hidden — see [Research & Findings](#research--findings).

### Architecture

The system is built around a clear separation of responsibilities:

| Component | Responsibility |
|---|---|
| `MarketDataService` | Fetches candlestick (kline) data from Binance |
| `StrategyService` | Applies the strategy and emits a `BUY` / `SELL` / `HOLD` signal |
| `RiskManager` | Validates every signal (kill-switch, daily loss limit, position sizing) |
| `ExecutionService` | Sends market orders to Binance |
| `StateManager` + PostgreSQL | Persists open positions across restarts |
| `TradingScheduler` | Orchestrates the decision cycle on a fixed interval |
| Backtesting modules | Historical simulation, multi-period and out-of-sample evaluation |

**Tech stack:** Java 21 · Spring Boot 3.4 · Spring Data JPA · PostgreSQL ·
Binance Spot connector (`io.github.binance`) · Maven.

### Key design decisions

- **Safe by default.** The bot ships with the kill-switch *enabled* and pointing at
  testnet. Executing real orders requires deliberate, explicit configuration changes.
- **Risk management as a first-class citizen.** A dedicated `RiskManager` enforces a
  kill-switch, a maximum position size, and a daily loss limit — every signal passes
  through it before becoming an order.
- **State survives restarts.** Open positions are persisted in PostgreSQL, so the bot
  recovers its state after a crash or restart instead of losing track of holdings.
- **Secrets via environment only.** API keys are never hardcoded; they are read
  exclusively from environment variables.
- **Deterministic, testable strategy logic.** The strategy is a pure function of the
  input candles, which makes it unit-testable and backtestable.
- **Precision-correct money math.** All prices and monetary values use `BigDecimal`,
  never floating point.

### The strategy

The implemented strategy is a **moving-average crossover with trend filters**:

- Enter long when the fast SMA (20) crosses above the slow SMA (50).
- Exit when it crosses back below.
- A long-term SMA (200) acts as a trend filter — longs are only allowed in an uptrend.
- An optional **ADX** filter restricts entries to periods of genuine trend strength,
  designed to avoid the false signals that plague crossover systems in sideways markets.

### Research & Findings

The most valuable part of this project is the honest evaluation methodology.

**Multi-regime backtest.** The strategy was tested across bull, bear, and sideways
market periods. It only "beat" buy-and-hold in the bear market — by losing less, not
by gaining. In the sideways market it bled capital through false signals and fees.

**Out-of-sample validation.** To guard against overfitting, historical data was split
chronologically: parameters were optimized on the **training half**, then the winning
combination was evaluated **once** on the unseen **test half**.

| Metric | Training (optimized) | Test (unseen) |
|---|---|---|
| Strategy net return | positive, but far below buy-and-hold | negative |
| vs. buy-and-hold | underperformed | "beat" it only by losing less in a downturn |
| Verdict | — | **No demonstrated edge** |

**Conclusion.** The strategy that maximized training-period return still lost badly to
buy-and-hold, and on unseen data it lost money. The system classified this correctly as
*inconclusive* — neither a persistent edge nor pure overfitting, simply **no real
advantage over buy-and-hold**. This is consistent with financial theory: publicly
available technical patterns rarely yield a sustainable edge for retail participants.

### What this project demonstrates

- Designing and building a non-trivial, multi-component backend system in Spring Boot.
- Integrating with an external financial API, including authentication and rate limits.
- State persistence, risk controls, and safe-by-default operational design.
- **Scientific rigor**: applying out-of-sample validation and reporting an unflattering
  result honestly, rather than cherry-picking a good-looking backtest.

> ⚠️ **Disclaimer.** This software is for educational and research purposes only. It is
> **not** financial advice. Backtests are historical simulations; past performance does
> not guarantee future results. The strategy has been shown not to be profitable. Do not
> use this with real funds. Any trading decision is the sole responsibility of the user.

### Getting started

See [`MODO_DE_EMPLEO.md`](./MODO_DE_EMPLEO.md) for the full setup, usage, and
troubleshooting guide. In short:

```bash
# Requirements: Java 21, PostgreSQL 16, a Binance testnet account
# Set credentials as environment variables (never hardcode them)
# Observation mode (no orders):
java "-Dspring.profiles.active=testnet" "-Dbot.risk.kill-switch=true" -jar target/trading-bot-0.1.0.jar
# Out-of-sample backtest:
java "-Dspring.profiles.active=oos" -jar target/trading-bot-0.1.0.jar
```

### License

MIT — see [`LICENSE`](./LICENSE).

---

## 🇪🇸 Español

# Trading Bot — Sistema de Trading Algorítmico con Validación Empírica de Estrategia

> Un bot de trading algorítmico para Binance (Spot) desarrollado en Spring Boot, como
> proyecto de ingeniería e investigación. Más allá de la implementación, este proyecto
> aplica **backtesting riguroso out-of-sample** para evaluar con honestidad si la
> estrategia tiene alguna ventaja real — y documenta los hallazgos de forma transparente.

### Descripción

Es un bot de trading algorítmico completamente funcional que se conecta a Binance, lee
datos de mercado, evalúa una estrategia y ejecuta órdenes spot — con persistencia de
estado, gestión de riesgo y un diseño seguro por defecto. Se desarrolló y validó
íntegramente contra la **testnet** de Binance (sin fondos reales).

El proyecto tiene dos mitades:

1. **La ingeniería** — una aplicación Spring Boot limpia y con forma de producción.
2. **La investigación** — un estudio empírico, con validación out-of-sample, sobre si la
   estrategia técnica implementada realmente supera a una simple referencia de comprar y
   mantener.

> **Hallazgo honesto:** a lo largo de tres evaluaciones cada vez más rigurosas, las
> estrategias técnicas probadas (cruce de medias, con filtro de tendencia, con filtro de
> fuerza ADX) **no** demostraron una ventaja persistente sobre comprar y mantener en
> datos no vistos. Este resultado se documenta abiertamente en vez de ocultarse — ver
> [Investigación y hallazgos](#investigación-y-hallazgos).

### Arquitectura

El sistema se construye sobre una separación clara de responsabilidades:

| Componente | Responsabilidad |
|---|---|
| `MarketDataService` | Obtiene los datos de velas (klines) de Binance |
| `StrategyService` | Aplica la estrategia y emite una señal `BUY` / `SELL` / `HOLD` |
| `RiskManager` | Valida cada señal (kill-switch, límite de pérdida diaria, tamaño de posición) |
| `ExecutionService` | Envía las órdenes de mercado a Binance |
| `StateManager` + PostgreSQL | Persiste las posiciones abiertas entre reinicios |
| `TradingScheduler` | Orquesta el ciclo de decisión en un intervalo fijo |
| Módulos de backtesting | Simulación histórica, evaluación multi-régimen y out-of-sample |

**Stack tecnológico:** Java 21 · Spring Boot 3.4 · Spring Data JPA · PostgreSQL ·
connector de Binance Spot (`io.github.binance`) · Maven.

### Decisiones de diseño clave

- **Seguro por defecto.** El bot arranca con el kill-switch *activado* y apuntando a
  testnet. Ejecutar órdenes reales exige cambios de configuración deliberados y explícitos.
- **Gestión de riesgo de primera clase.** Un `RiskManager` dedicado impone un kill-switch,
  un tamaño máximo de posición y un límite de pérdida diaria — cada señal pasa por él
  antes de convertirse en orden.
- **El estado sobrevive a los reinicios.** Las posiciones abiertas se persisten en
  PostgreSQL, de modo que el bot recupera su estado tras una caída o reinicio en lugar de
  perder de vista lo que tiene.
- **Secretos solo por entorno.** Las claves API nunca van hardcodeadas; se leen
  exclusivamente de variables de entorno.
- **Lógica de estrategia determinista y testeable.** La estrategia es una función pura de
  las velas de entrada, lo que la hace testeable unitariamente y backtesteable.
- **Matemática de dinero con precisión correcta.** Todos los precios y valores monetarios
  usan `BigDecimal`, nunca coma flotante.

### La estrategia

La estrategia implementada es un **cruce de medias móviles con filtros de tendencia**:

- Entrar en largo cuando la SMA rápida (20) cruza por encima de la lenta (50).
- Salir cuando vuelve a cruzar por debajo.
- Una SMA de largo plazo (200) actúa como filtro de tendencia — solo se permiten largos
  en tendencia alcista.
- Un filtro **ADX** opcional restringe las entradas a periodos de fuerza de tendencia
  real, diseñado para evitar las señales falsas que afectan a los sistemas de cruce en
  mercados laterales.

### Investigación y hallazgos

La parte más valiosa de este proyecto es la metodología de evaluación honesta.

**Backtest multi-régimen.** La estrategia se probó en periodos de mercado alcista,
bajista y lateral. Solo "ganó" a comprar y mantener en el mercado bajista — perdiendo
menos, no ganando. En el mercado lateral sangró capital con señales falsas y comisiones.

**Validación out-of-sample.** Para protegerse del sobreajuste, los datos históricos se
dividieron cronológicamente: los parámetros se optimizaron en la **mitad de
entrenamiento**, y luego la combinación ganadora se evaluó **una sola vez** sobre la
**mitad de prueba** no vista.

| Métrica | Entrenamiento (optimizado) | Prueba (no vista) |
|---|---|---|
| Rentabilidad neta de la estrategia | positiva, pero muy por debajo de comprar y mantener | negativa |
| vs. comprar y mantener | por debajo | "ganó" solo por perder menos en una caída |
| Veredicto | — | **Sin ventaja demostrada** |

**Conclusión.** La estrategia que maximizó la rentabilidad en entrenamiento seguía
perdiendo claramente contra comprar y mantener, y en datos no vistos perdió dinero. El
sistema lo clasificó correctamente como *no concluyente* — ni una ventaja persistente ni
puro sobreajuste, simplemente **sin ventaja real sobre comprar y mantener**. Esto es
coherente con la teoría financiera: los patrones técnicos públicos rara vez producen una
ventaja sostenible para el inversor minorista.

### Qué demuestra este proyecto

- Diseñar y construir un sistema backend no trivial y multi-componente en Spring Boot.
- Integrar con una API financiera externa, incluyendo autenticación y límites de tasa.
- Persistencia de estado, controles de riesgo y diseño operativo seguro por defecto.
- **Rigor científico**: aplicar validación out-of-sample y reportar un resultado poco
  favorecedor con honestidad, en lugar de elegir un backtest que quede bonito.

> ⚠️ **Aviso.** Este software es solo para fines educativos y de investigación. **No** es
> asesoramiento financiero. Los backtests son simulaciones históricas; el rendimiento
> pasado no garantiza resultados futuros. Se ha demostrado que la estrategia no es
> rentable. No lo uses con fondos reales. Cualquier decisión de inversión es
> responsabilidad exclusiva del usuario.

### Cómo empezar

Consulta [`MODO_DE_EMPLEO.md`](./MODO_DE_EMPLEO.md) para la guía completa de instalación,
uso y resolución de errores. En resumen:

```bash
# Requisitos: Java 21, PostgreSQL 16, una cuenta de testnet de Binance
# Define las credenciales como variables de entorno (nunca las hardcodees)
# Modo observación (sin órdenes):
java "-Dspring.profiles.active=testnet" "-Dbot.risk.kill-switch=true" -jar target/trading-bot-0.1.0.jar
# Backtest out-of-sample:
java "-Dspring.profiles.active=oos" -jar target/trading-bot-0.1.0.jar
```

### Licencia

MIT — ver [`LICENSE`](./LICENSE).

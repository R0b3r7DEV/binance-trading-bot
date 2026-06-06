# Trading Bot — Modo de Empleo

Manual de instalación, uso, resolución de errores e interpretación del bot de trading
spot para Binance (Spring Boot + PostgreSQL).

> **Aviso importante.** Este bot es un proyecto de aprendizaje y de portfolio. Los
> backtests realizados muestran que la estrategia **pierde dinero** en 2 de 3 regímenes
> de mercado y no bate de forma consistente a "comprar y mantener". **No uses dinero real
> hasta tener una estrategia validada como rentable.** El rendimiento pasado no garantiza
> resultados futuros. Esto no es asesoramiento financiero; cualquier decisión de inversión
> es responsabilidad exclusiva del usuario.

---

## 1. Qué es este proyecto

Un bot que se conecta a Binance (modo spot), lee velas de precio, calcula una estrategia
de cruce de medias móviles con filtro de tendencia, y decide cuándo comprar y vender.
Está pensado para correr primero en **testnet** (dinero ficticio) antes de cualquier
contacto con dinero real.

Componentes principales:

- **MarketDataService** — obtiene las velas (klines) de Binance.
- **StrategyService** — aplica la estrategia y emite señal BUY / SELL / HOLD.
- **RiskManager** — filtra cada señal (kill-switch, límite de pérdida, tamaño de posición).
- **ExecutionService** — envía las órdenes a Binance.
- **StateManager / PostgreSQL** — persiste la posición abierta entre reinicios.
- **TradingScheduler** — orquesta el ciclo cada N segundos.
- **Módulo de backtest** — simula la estrategia sobre datos históricos.

---

## 2. Requisitos previos

| Componente | Versión | Para qué |
|---|---|---|
| Java JDK | 21 | Ejecutar el bot |
| PostgreSQL | 16 | Persistir la posición |
| Cuenta testnet | — | Claves de API ficticias |

---

## 3. Instalación

### 3.1 Instalar Java 21

En Windows con winget:

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

**Tras instalar, cierra y abre PowerShell de nuevo** para que se actualice el PATH.
Comprueba:

```powershell
java -version
```

Debe responder `openjdk version "21.x.x"`. Si no, mira la sección de errores (5.1).

### 3.2 Instalar PostgreSQL 16

```powershell
winget install PostgreSQL.PostgreSQL.16
```

Durante la instalación **apunta la contraseña del usuario `postgres`** que te pida.
Si usaste una instalación rápida, prueba `postgres` como contraseña por defecto.

### 3.3 Crear la base de datos

Abre **pgAdmin**, conéctate al servidor (usuario `postgres`), y en el árbol de la
izquierda: clic derecho en **Databases → Create → Database…**, nombre `tradingbot`, Save.

> No uses `CREATE DATABASE` en el Query Tool de pgAdmin: da el error
> `25001 ... no puede ser ejecutado dentro de un bloque de transacción`. Usa la interfaz.

### 3.4 Obtener claves de testnet

1. Entra en <https://testnet.binance.vision> (login con GitHub).
2. Genera un par de claves API (HMAC).
3. Copia la **API key** y la **secret** (la secret solo se muestra una vez).
4. Te dan saldo ficticio automáticamente.

> **Nunca** pegues las claves en código, en chats, ni en archivos que subas a Git.
> Si una clave se expone, regénerala desde testnet.

---

## 4. Cómo arrancar el bot

### 4.1 Definir variables de entorno (PowerShell)

Las variables `$env:` solo viven en la ventana actual. Hay que ponerlas **cada vez**
que abras una ventana nueva, y arrancar desde **esa misma ventana**.

```powershell
$env:BINANCE_API_KEY="tu_clave_de_testnet"
$env:BINANCE_API_SECRET="tu_secret_de_testnet"
$env:DB_USER="postgres"
$env:DB_PASSWORD="postgres"
```

Verifica (sin imprimir la secret):

```powershell
echo $env:BINANCE_API_KEY
```

### 4.2 Modo observación (no ejecuta órdenes)

Recomendado siempre para comprobar que todo funciona antes de operar. El kill-switch
en `true` hace todo el ciclo pero **nunca** envía órdenes.

```powershell
java "-Dspring.profiles.active=testnet" "-Dbot.risk.kill-switch=true" "-Djavax.net.ssl.trustStore=NUL" "-Djavax.net.ssl.trustStoreType=Windows-ROOT" -jar "C:\Users\Usuario\Desktop\Binance Bot\target\trading-bot-0.1.0.jar"
```

### 4.3 Modo ejecución en testnet (sí opera, dinero ficticio)

La única diferencia es `kill-switch=false`:

```powershell
java "-Dspring.profiles.active=testnet" "-Dbot.risk.kill-switch=false" "-Djavax.net.ssl.trustStore=NUL" "-Djavax.net.ssl.trustStoreType=Windows-ROOT" -jar "C:\Users\Usuario\Desktop\Binance Bot\target\trading-bot-0.1.0.jar"
```

Confirma que en los logs aparece `kill-switch=INACTIVO (modo real)`.

> **Por qué el perfil `testnet` es obligatorio.** La testnet no tiene suficiente histórico
> de velas de 1h para calcular la SMA200 (solo ~71 velas). El perfil `testnet` cambia el
> intervalo a **1m**, donde sí hay 250+ velas. Si arrancas sin perfil, verás
> `No active profile set`, el bot usará 1h, y abortará los ciclos por datos insuficientes.

### 4.4 Backtest (simulación histórica, sin claves ni órdenes)

```powershell
java "-Dspring.profiles.active=backtest" -jar "C:\Users\Usuario\Desktop\Binance Bot\target\trading-bot-0.1.0.jar"
```

---

## 5. Errores encontrados y cómo resolverlos

### 5.1 `java` no se reconoce como comando

**Causa:** Java no está en el PATH, o la ventana de PowerShell es anterior a la instalación.

**Solución:**
1. Cierra y abre PowerShell de nuevo.
2. Si persiste, localiza Java: `ls "C:\Program Files\Eclipse Adoptium"`.
3. Añade la carpeta `\bin` al PATH del sistema (buscar "variables de entorno" en Windows →
   editar `Path` → Nuevo → pegar la ruta del `bin`).
4. Solo para la sesión actual:
   ```powershell
   $env:Path += ";C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot\bin"
   ```

### 5.2 `Connection to localhost:5432 refused`

**Causa:** PostgreSQL no está arrancado o la base de datos `tradingbot` no existe.

**Solución:**
1. Comprueba el servicio: `Get-Service -Name postgresql*` → debe estar `Running`.
   Si no: `Start-Service -Name postgresql*` (PowerShell como administrador).
2. Comprueba el puerto: `Test-NetConnection -ComputerName localhost -Port 5432`.
3. Crea la base de datos `tradingbot` desde pgAdmin (ver 3.3).

### 5.3 pgAdmin pide una contraseña que no recuerdas

- Si la pide **al abrir pgAdmin**, es la "contraseña maestra" de pgAdmin: créala nueva ahí.
- Si la pide **al pinchar el servidor**, es la del usuario `postgres` de la instalación.
  Prueba `postgres`. Si no, hay que resetearla (editar `pg_hba.conf`) o reinstalar
  PostgreSQL apuntando bien la contraseña.

### 5.4 `CREATE DATABASE no puede ser ejecutado dentro de un bloque de transacción` (SQL 25001)

**Causa:** pgAdmin ejecuta el Query Tool dentro de una transacción.

**Solución:** crea la base de datos con la interfaz de pgAdmin (Databases → Create →
Database…), no con SQL.

### 5.5 `ClassNotFoundException: .profiles.active=testnet`

**Causa:** PowerShell malinterpreta los argumentos `-D` (sobre todo con los backticks de
continuación de línea).

**Solución:**
- Pon el comando en **una sola línea**, y **entrecomilla cada argumento** `-D`:
  `"-Dspring.profiles.active=testnet"`.
- Alternativa con variables de entorno (a prueba de PowerShell):
  ```powershell
  $env:SPRING_PROFILES_ACTIVE="testnet"
  $env:BOT_RISK_KILLSWITCH="false"
  java -jar "C:\...\trading-bot-0.1.0.jar"
  ```

### 5.6 `SMA200: (N/A)` y pocas velas (71)

**Causa:** arrancaste sin el perfil `testnet`, así que usó intervalo 1h, donde la testnet
no tiene suficiente histórico.

**Solución:** arranca siempre con `-Dspring.profiles.active=testnet` (intervalo 1m,
250 velas). Ver 4.3.

---

## 6. Cómo leer los logs

Cada ciclo imprime un bloque así:

```
[CICLO] 2026-06-06 14:13:25 | BTCUSDT/1m | kill-switch=INACTIVO (modo real)
[CICLO] Precio: 60973.57 USDT  | Velas: 250
[CICLO] SMA20: 60935.27  |  SMA50: 60869.16  |  SMA200: 60859.24
[CICLO] Señal estrategia: HOLD
[CICLO] RiskManager: BLOQUEADO  |  Posicion: NINGUNA  |  Perdida diaria: 0.00 USDT
[CICLO] Estado final posicion: NINGUNA
```

Cómo interpretarlo:

- **kill-switch=INACTIVO** → el bot puede ejecutar órdenes. `ACTIVO` = solo observa.
- **Velas: 250** → datos suficientes (necesita 201 para la SMA200). Si ves menos, no opera.
- **SMA20 / SMA50** → medias rápida y lenta. El **cruce** entre ellas genera la señal.
- **SMA200** → media de tendencia (el filtro). Si el precio está por encima, permite
  compras; si está por debajo, las bloquea.
- **Señal** → `BUY` / `SELL` / `HOLD`. La mayoría de ciclos son HOLD: es normal.
- **RiskManager** → `BLOQUEADO` o permitido, con el motivo. HOLD siempre se bloquea
  (no hay nada que hacer).
- **Posicion** → `NINGUNA` o una posición abierta con su precio de entrada.

**La primera orden** se reconoce porque la señal deja de ser HOLD, aparece una línea de
ejecución, y la posición pasa de `NINGUNA` a abierta. Verifícala en tres sitios: el log,
la web de testnet, y la tabla de posiciones en pgAdmin.

---

## 7. Qué validar en testnet

El objetivo de la testnet es validar **ingeniería**, no rentabilidad:

- [ ] El bot arranca y conecta a Binance sin error de autenticación.
- [ ] Trae 250 velas y calcula la SMA200 (no N/A).
- [ ] Emite señales coherentes con las medias.
- [ ] Ejecuta la primera orden cuando hay cruce + filtro favorable.
- [ ] La orden aparece en la web de testnet.
- [ ] La posición se guarda en PostgreSQL.
- [ ] **Reconexión:** corta el wifi un par de minutos → ¿reconecta sin caerse?
- [ ] **Persistencia:** reinicia el bot con una posición abierta → ¿sigue ahí al volver?
- [ ] El stop-loss salta cuando el precio cae el % configurado.
- [ ] El kill-switch detiene la ejecución cuando se pone en `true`.

Déjalo correr **días**, no minutos. Los fallos de robustez solo aparecen con el tiempo.

---

## 8. Cómo pasarlo al mundo real

> **Antes de leer esta sección, lee la 8.1.** El paso a real no es principalmente técnico.

### 8.1 Requisito previo innegociable: una estrategia rentable

El backtest actual muestra que la estrategia **pierde** en mercados laterales y bajistas,
y captura solo una fracción de las subidas. **Pasar a real una estrategia que pierde solo
significa perder dinero de verdad en vez de ficticio.** Antes de cualquier paso a real:

1. Conseguir que el backtest sea rentable en **varios regímenes** (alcista, bajista, lateral),
   no solo en uno.
2. Validar en datos **out-of-sample** (periodos que no se usaron para ajustar los parámetros),
   para no engañarse con sobreajuste (overfitting).
3. Mantener semanas de paper trading en testnet con buen comportamiento.

Si la estrategia no supera esto, **el paso a real no debe darse.** No es un fallo del
proyecto: es el sistema haciendo su trabajo de avisarte.

### 8.2 Aviso legal (vender el bot o gestionar dinero de terceros)

Vender señales, gestionar dinero ajeno, prometer rentabilidades o cobrar por beneficios
es **actividad financiera regulada** (en España, CNMV / MiFID II). Hacerlo sin licencia
puede acarrear responsabilidad penal, además de demandas de usuarios que pierdan dinero.

- Distribuir el bot como **software** que cada usuario ejecuta con su propia cuenta y su
  propio riesgo es distinto de gestionar su dinero.
- **El bot debe reportar hechos, nunca recomendar** ("invierte / no inviertas" es
  asesoramiento).
- **Consulta a un abogado** especializado en regulación financiera antes de vender nada.

### 8.3 Pasos técnicos para producción (solo si 8.1 se cumple)

1. **Claves reales.** Crear claves API en la cuenta real de Binance
   (<https://www.binance.com/en/my/settings/api-management>), con permisos mínimos
   (solo trading spot, **sin** retiros) y restricción por IP.
2. **Cambiar la configuración a producción:**
   - `bot.binance.testnet: false`
   - `base-url` apuntando a la API de producción de Binance.
3. **Eliminar el workaround de SSL de Windows.** Los argumentos
   `-Djavax.net.ssl.trustStore=NUL -Djavax.net.ssl.trustStoreType=Windows-ROOT` son
   específicos de Windows y **darán error en un servidor Linux** (como un VPS). Quítalos
   en producción.
4. **Revisar el intervalo.** En real probablemente quieras volver a 1h (el timeframe
   que se valida en backtest), no el 1m que se usa en testnet por falta de histórico.
   Asegúrate de que la fuente de velas de producción da suficientes para la SMA200.
5. **Empezar con el mínimo.** Capital que puedas perder por completo. Recuerda el
   `minNotional` de Binance (~5–10 USDT por orden): con muy poco capital, las comisiones
   y los mínimos hacen la estrategia inviable.
6. **Gestión de secretos.** En el VPS, las claves van en variables de entorno o en un
   gestor de secretos, nunca en el repositorio (igual que la API key de Anthropic en
   XPL0DAY). Mantén el bot en un **repositorio separado** de proyectos públicos.
7. **Monitorización.** Logs persistentes, alertas si el bot se cae, y un **kill-switch
   accesible** en remoto para pararlo al instante.
8. **Despliegue.** Como servicio (systemd en Linux) o contenedor Docker, con reinicio
   automático y la base de datos PostgreSQL en su propio servicio.

### 8.4 Orden recomendado, en resumen

```
Backtest rentable en 3 regímenes
        │
        ▼
Validación out-of-sample (sin sobreajuste)
        │
        ▼
Semanas de paper trading en testnet (robustez OK)
        │
        ▼
Asesoría legal (si hay terceros de por medio)
        │
        ▼
Real con el mínimo, vigilado de cerca
```

---

## 9. Comandos de referencia rápida

```powershell
# Comprobar Java
java -version

# Comprobar PostgreSQL
Get-Service -Name postgresql*
Test-NetConnection -ComputerName localhost -Port 5432

# Variables de entorno (cada ventana nueva)
$env:BINANCE_API_KEY="..."
$env:BINANCE_API_SECRET="..."
$env:DB_USER="postgres"
$env:DB_PASSWORD="postgres"

# Observación (no opera)
java "-Dspring.profiles.active=testnet" "-Dbot.risk.kill-switch=true" "-Djavax.net.ssl.trustStore=NUL" "-Djavax.net.ssl.trustStoreType=Windows-ROOT" -jar "C:\Users\Usuario\Desktop\Binance Bot\target\trading-bot-0.1.0.jar"

# Ejecución en testnet (opera, dinero ficticio)
java "-Dspring.profiles.active=testnet" "-Dbot.risk.kill-switch=false" "-Djavax.net.ssl.trustStore=NUL" "-Djavax.net.ssl.trustStoreType=Windows-ROOT" -jar "C:\Users\Usuario\Desktop\Binance Bot\target\trading-bot-0.1.0.jar"

# Backtest
java "-Dspring.profiles.active=backtest" -jar "C:\Users\Usuario\Desktop\Binance Bot\target\trading-bot-0.1.0.jar"
```

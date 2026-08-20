# Snake Race — ARSW Lab #2 (Java 21, Virtual Threads)

**Escuela Colombiana de Ingeniería – Arquitecturas de Software**  
Laboratorio de programación concurrente: condiciones de carrera, sincronización y colecciones seguras.

---

## Requisitos

- **JDK 21** (Temurin recomendado)
- **Maven 3.9+**
- SO: Windows, macOS o Linux

---

## Cómo ejecutar

```bash
mvn clean verify
mvn -q -DskipTests exec:java -Dsnakes=4
```

- `-Dsnakes=N` → inicia el juego con **N** serpientes (por defecto 2).
- **Controles**:
  - **Flechas**: serpiente **0** (Jugador 1).
  - **WASD**: serpiente **1** (si existe).
  - **Espacio** o botón **Action**: Pausar / Reanudar.

---

## Reglas del juego (resumen)

- **N serpientes** corren de forma autónoma (cada una en su propio hilo).
- **Ratones**: al comer uno, la serpiente **crece** y aparece un **nuevo obstáculo**.
- **Obstáculos**: si la cabeza entra en un obstáculo hay **rebote**.
- **Teletransportadores** (flechas rojas): entrar por uno te **saca por su par**.
- **Rayos (Turbo)**: al pisarlos, la serpiente obtiene **velocidad aumentada** temporal.
- Movimiento con **wrap-around** (el tablero “se repite” en los bordes).

---

## Arquitectura (carpetas)

```
co.eci.snake
├─ app/                 # Bootstrap de la aplicación (Main)
├─ core/                # Dominio: Board, Snake, Direction, Position
├─ core/engine/         # GameClock (ticks, Pausa/Reanudar)
├─ concurrency/         # SnakeRunner (lógica por serpiente con virtual threads)
└─ ui/legacy/           # UI estilo legado (Swing) con grilla y botón Action
```

---

# Actividades del laboratorio

## Parte I — (Calentamiento) `wait/notify` en un programa multi-hilo

1. Toma el programa [**PrimeFinder**](https://github.com/ARSW-ECI/wait-notify-excercise).
2. Modifícalo para que **cada _t_ milisegundos**:
   - Se **pausen** todos los hilos trabajadores.
   - Se **muestre** cuántos números primos se han encontrado.
   - El programa **espere ENTER** para **reanudar**.
3. La sincronización debe usar **`synchronized`**, **`wait()`**, **`notify()` / `notifyAll()`** sobre el **mismo monitor** (sin _busy-waiting_).
4. Entrega en el reporte de laboratorio **las observaciones y/o comentarios** explicando tu diseño de sincronización (qué lock, qué condición, cómo evitas _lost wakeups_).

> Objetivo didáctico: practicar suspensión/continuación **sin** espera activa y consolidar el modelo de monitores en Java.

### Solución y observaciones (Parte I)

La solución vive en el repositorio [`wait-notify-excercise-ParradoHernandez`](https://github.com/juanhernandez2003/wait-notify-excercise-ParradoHernandez), rama `feature/parte1`: se agregó la clase `PauseMonitor` y se modificaron `PrimeFinderThread` y `Control`.

**Qué lock se usa.** Se introdujo la clase `PauseMonitor`, que actúa como el *único* monitor del programa: tanto los hilos trabajadores (`PrimeFinderThread`) como el hilo controlador (`Control`) sincronizan sobre esa misma instancia. Todo el estado compartido de la pausa vive dentro de ella —la bandera `paused`, el contador `pausedWorkers` de hilos efectivamente suspendidos y `liveWorkers` de hilos que aún no terminan su rango—, de modo que es imposible leer o escribir ese estado fuera del lock, que es la causa habitual de las condiciones de carrera en este tipo de ejercicios.

**Cuál es la condición.** Cada trabajador invoca `checkPause()` en cada iteración de su ciclo. Si `paused` está activa, el hilo entra al bloque `synchronized`, incrementa `pausedWorkers`, hace `notifyAll()` para avisarle al controlador que ya se detuvo y se bloquea en `while (paused) wait();`. La espera está siempre dentro de un `while` y no de un `if`, de manera que ante *spurious wakeups* o ante un `notifyAll()` dirigido a otra condición el hilo vuelve a evaluar el predicado antes de continuar. No hay espera activa: un hilo pausado queda en estado `WAITING` sobre el monitor y consume 0% de CPU.

**Cómo se evitan los *lost wakeups*.** Por tres decisiones concretas. Primera, la reanudación no depende de una señal "de flanco" sino del valor de `paused`: si un trabajador llega tarde al punto de chequeo encuentra la bandera en falso y simplemente sigue, sin quedarse dormido por una notificación que ya pasó. Segunda, `resumeAll()` pone `paused = false` **y** hace `notifyAll()` dentro del mismo bloque `synchronized`, así que ningún hilo puede colarse entre el cambio de la bandera y la notificación, porque para llegar a `wait()` primero debe adquirir ese mismo lock. Tercera, se usa `notifyAll()` y no `notify()`, porque en este monitor esperan hilos por dos condiciones distintas —los trabajadores por que se levante la pausa y el controlador por que todos estén pausados— y despertar al hilo equivocado perdería la señal para el resto.

**Consistencia del conteo.** El controlador no imprime el número de primos justo después de activar la bandera, sino que llama `awaitAllPaused()`, que espera con `wait()` hasta que `pausedWorkers == liveWorkers`. Así la cifra reportada corresponde a un estado estable y no a una foto tomada mientras los hilos siguen agregando resultados. Como contraparte, `workerFinished()` decrementa `liveWorkers` cuando un hilo agota su rango; sin eso el controlador esperaría indefinidamente por hilos que ya murieron, un interbloqueo sutil que aparece justo al final de la ejecución.

**Costo de sincronizar en cada iteración.** El punto de chequeo se ejecuta 30 millones de veces, así que `checkPause()` tiene un *camino rápido*: lee la bandera `paused`, declarada `volatile`, **sin** tomar el lock, y solo entra al bloque `synchronized` si hay una pausa pendiente. El costo normal por iteración es entonces una lectura volátil en lugar de una adquisición de monitor con contención entre tres hilos. Esto no es espera activa —el hilo no gira esperando nada, avanza haciendo trabajo útil— y no compromete la corrección: si la bandera se activa justo después de la lectura, el hilo se detiene en la iteración siguiente y el controlador lo espera en `awaitAllPaused()`. Dentro del bloque se reevalúa `paused` (patrón *double-checked*), correcto aquí precisamente porque la variable es `volatile`.

**Detalle adicional.** La lista `primes` de cada trabajador se protege con métodos `synchronized` sobre el propio hilo (`addPrime`, `getPrimeCount`). Aunque durante la pausa los trabajadores están congelados y la lectura sería segura, hacerlo explícito evita depender de esa suposición y garantiza la visibilidad de memoria entre el hilo que escribe y el controlador que lee.

**Verificación.** Con `NTHREADS = 3` y `MAXVALUE = 30000000` el programa se pausa cada _t_ ms, reporta el conteo acumulado, reanuda con ENTER y termina reportando 1.857.859 primos, que es el valor correcto de π(3·10⁷).

---

## Parte II — SnakeRace concurrente (núcleo del laboratorio)

### 1) Análisis de concurrencia

- Explica **cómo** el código usa hilos para dar autonomía a cada serpiente.
- **Identifica** y documenta en **`el reporte de laboratorio`**:
  - Posibles **condiciones de carrera**.
  - **Colecciones** o estructuras **no seguras** en contexto concurrente.
  - Ocurrencias de **espera activa** (busy-wait) o de sincronización innecesaria.

### 2) Correcciones mínimas y regiones críticas

- **Elimina** esperas activas reemplazándolas por **señales** / **estados** o mecanismos de la librería de concurrencia.
- Protege **solo** las **regiones críticas estrictamente necesarias** (evita bloqueos amplios).
- Justifica en **`el reporte de laboratorio`** cada cambio: cuál era el riesgo y cómo lo resuelves.

### 3) Control de ejecución seguro (UI)

- Implementa la **UI** con **Iniciar / Pausar / Reanudar** (ya existe el botón _Action_ y el reloj `GameClock`).
- Al **Pausar**, muestra de forma **consistente** (sin _tearing_):
  - La **serpiente viva más larga**.
  - La **peor serpiente** (la que **primero murió**).
- Considera que la suspensión **no es instantánea**; coordina para que el estado mostrado no quede “a medias”.

### 4) Robustez bajo carga

- Ejecuta con **N alto** (`-Dsnakes=20` o más) y/o aumenta la velocidad.
- El juego **no debe romperse**: sin `ConcurrentModificationException`, sin lecturas inconsistentes, sin _deadlocks_.
- Si habilitas **teleports** y **turbo**, verifica que las reglas no introduzcan carreras.

> Entregables detallados más abajo.

---

## Entregables

1. **Código fuente** funcionando en **Java 21**.
2. Todo de manera clara en **`**el reporte de laboratorio**`** con:
   - Data races encontradas y su solución.
   - Colecciones mal usadas y cómo se protegieron (o sustituyeron).
   - Esperas activas eliminadas y mecanismo utilizado.
   - Regiones críticas definidas y justificación de su **alcance mínimo**.
3. UI con **Iniciar / Pausar / Reanudar** y estadísticas solicitadas al pausar.

---

## Criterios de evaluación (10)

- (3) **Concurrencia correcta**: sin data races; sincronización bien localizada.
- (2) **Pausa/Reanudar**: consistencia visual y de estado.
- (2) **Robustez**: corre **con N alto** y sin excepciones de concurrencia.
- (1.5) **Calidad**: estructura clara, nombres, comentarios; sin _code smells_ obvios.
- (1.5) **Documentación**: **`reporte de laboratorio`** claro, reproducible;

---

## Tips y configuración útil

- **Número de serpientes**: `-Dsnakes=N` al ejecutar.
- **Tamaño del tablero**: cambiar el constructor `new Board(width, height)`.
- **Teleports / Turbo**: editar `Board.java` (métodos de inicialización y reglas en `step(...)`).
- **Velocidad**: ajustar `GameClock` (tick) o el `sleep` del `SnakeRunner` (incluye modo turbo).

---

## Cómo correr pruebas

```bash
mvn clean verify
```

Incluye compilación y ejecución de pruebas JUnit. Si tienes análisis estático, ejecútalo en `verify` o `site` según tu `pom.xml`.

---

## Créditos

Este laboratorio es una adaptación modernizada del ejercicio **SnakeRace** de ARSW. El enunciado de actividades se conserva para mantener los objetivos pedagógicos del curso.

**Base construida por el Ing. Javier Toquica.**

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

El juego corre sobre tres familias de hilos que comparten el mismo estado mutable. 
El EDT de Swing ejecuta el constructor de SnakeApp, el dibujado (paintComponent) y los listeners de teclado,
que llaman snake.turn() directamente. N hilos virtuales (Executors.newVirtualThreadPerTaskExecutor(),
con -Dsnakes=N) ejecutan un SnakeRunner cada uno: 
un bucle propio de decidir giro → board.step(snake) → dormir (80 ms, 40 ms en turbo). 
La autonomía de cada serpiente consiste en que cada una tiene su propio ciclo y su propio ritmo, sin coordinación 
entre ellas. Un hilo de reloj (GameClock, ScheduledExecutorService de un hilo) dispara repaint() cada 60 ms 
delegándolo al EDT.

Posibles condiciones de carrera. La más grave está en Snake.body (un ArrayDeque): el hilo de la serpiente
escribe con advance() mientras el EDT lee con snapshot() en cada repintado, sin ningún lock compartido, lo que
produce ConcurrentModificationException y cuerpos inconsistentes. Que advance() se invoque dentro del step()
sincronizado no protege nada, porque snapshot() no toma ese monitor. Snake.turn() es un check-then-act sobre
direction, escrito a la vez por el EDT (teclas) y por el propio runner (randomTurn): volatile garantiza
visibilidad pero no atomicidad, así que dos giros entrelazados pueden romper la invariante de no ir en reversa.
maxLength es un int sin volatile ni lock, sin garantía de visibilidad entre hilos. Board.step() lee head() y
direction() en secuencia, y el EDT puede cambiar la dirección entre ambas lecturas porque no participa del lock.
paintComponent() combina cinco lecturas independientes del mundo (mice, obstacles, turbo, teleports y el
snapshot de cada serpiente) tomadas en instantes distintos, lo que produce tearing. En GameClock, stop() no
cancela el ScheduledFuture, de modo que un start() posterior programa una segunda tarea periódica, y
togglePause() deriva el estado del texto del botón en lugar del estado real del reloj. Por último, SnakeApp
lanza los hilos, llama a clock.start() y hace setVisible(true) dentro de su propio constructor, publicando el
objeto antes de terminar de construirse. En contraste, el consumo de ratones y turbos sí está bien resuelto:
mice.remove(next) y turbo.remove(next) son check-and-act atómicos bajo el lock del tablero, por lo que dos
serpientes no pueden comer el mismo ratón.

Colecciones no seguras. Snake.body es un ArrayDeque compartido entre el hilo de la serpiente y el EDT sin
protección alguna: es el caso crítico. Los HashSet y el HashMap de Board (mice, obstacles, turbo y teleports)
sí quedan cubiertos, porque todos los accesos públicos son synchronized y los getters devuelven copias
defensivas, pero al precio de copiar las estructuras completas dentro del lock en cada frame. SnakeApp.snakes
es un ArrayList sin barrera de memoria explícita: hoy solo se lee después de construirse, pero cualquier alta o
baja de serpientes en ejecución provocaría ConcurrentModificationException. Las sustituciones razonables son
ConcurrentHashMap.newKeySet() y ConcurrentHashMap para el estado del tablero, y un snapshot inmutable publicado
atómicamente en lugar de exponer el Deque vivo de la serpiente.

Espera activa y sincronización innecesaria. GameClock mantiene la tarea periódica activa durante la pausa y en
cada tick comprueba si el estado es RUNNING para no hacer nada: es polling del estado, con ticks vacíos
indefinidos. Lo correcto es cancelar el ScheduledFuture al pausar y reprogramarlo al reanudar, o bloquear con
wait/notifyAll o con un Condition. El Thread.sleep() del SnakeRunner no es espera activa, pues libera la CPU,
pero es un temporizador rígido que no ofrece ningún mecanismo de pausa; resolverlo con un while (paused) {}
dentro del bucle sí introduciría busy-wait. En cuanto a la sincronización, Board usa un único lock global (this)
para step() y para los cuatro getters, lo que serializa por completo la simulación —con -Dsnakes=20 solo una
serpiente avanza a la vez aunque estén en extremos opuestos del tablero— y hace que el EDT compita por ese mismo
monitor en cada repintado. El diagnóstico de fondo es que la sincronización está en el lugar equivocado:
demasiado gruesa donde no hace falta, en las lecturas del render, e inexistente donde sí hace falta, en Snake.
A esto se suma copiar las colecciones dentro del lock y clonar cada serpiente completa en cada frame.

Snake.body (ArrayDeque): El hilo de la serpiente escribe (advance → addFirst/removeLast) mientras el EDT lee 
(snapshot() → new ArrayDeque<>(body)) en cada frame. Sin ninguna sincronización compartida → ConcurrentModificationException, 
NoSuchElementException o cuerpos "a medias". Hay que tener en cuenta el argumento fácil: advance se llama dentro de Board.step,
que es synchronized, pero snapshot() no toma ese monitor, así que el lock no protege nada aquí. Es el data race más
grave y el que revienta con -Dsnakes=20.
### 2) Correcciones mínimas y regiones críticas

- **Elimina** esperas activas reemplazándolas por **señales** / **estados** o mecanismos de la librería de concurrencia.
- Protege **solo** las **regiones críticas estrictamente necesarias** (evita bloqueos amplios).
- Justifica en **`el reporte de laboratorio`** cada cambio: cuál era el riesgo y cómo lo resuelves.

Eliminación de la espera activa. El GameClock era la única fuente de polling: durante la pausa la tarea
periódica seguía disparándose cada 60 ms y en cada disparo comprobaba el estado para no hacer nada. Ahora
pause() cancela el ScheduledFuture y resume() lo reprograma, de modo que en pausa no se ejecuta ningún tick.
Además la pausa antes no detenía la simulación: solo congelaba el repintado mientras las serpientes seguían
moviéndose. Se resolvió convirtiendo al GameClock en la única fuente de verdad del estado de la partida y
dándole un punto de suspensión, awaitIfPaused(), que los SnakeRunner invocan al inicio de cada iteración. Los
hilos quedan bloqueados en wait() sobre el monitor del reloj, sin consumir CPU, y se reanudan por señal con
notifyAll() desde resume(); la espera está dentro de un while sobre la condición, no de un if, para no perder
despertares ni caer en despertares espurios. stop() también hace notifyAll() y awaitIfPaused() devuelve false,
así que los hilos salen limpiamente de su bucle en vez de quedar bloqueados para siempre.

Regiones críticas de alcance mínimo. Board era synchronized en su totalidad: el mismo lock cubría step() y los
cuatro getters, así que el EDT competía con las N serpientes en cada frame y la simulación quedaba
completamente serializada. El estado del tablero pasó a colecciones concurrentes
(ConcurrentHashMap.newKeySet() y ConcurrentHashMap), con lo cual las lecturas ya no toman ningún lock y
devuelven una vista inmutable, y el lock explícito (un ReentrantLock) quedó reducido a lo estrictamente
necesario: la secuencia compuesta de step(), que consulta obstáculos, resuelve el teleport, consume ratón o
turbo, avanza la serpiente y repone ítems. Esa secuencia sí debe ser atómica frente a las demás serpientes,
porque si se liberara el lock a mitad dos de ellas podrían consumir el mismo ratón o avanzar sobre un mundo ya
obsoleto.

Protección del estado de la serpiente. El riesgo más grave estaba en Snake, que no tenía ninguna protección:
el hilo de la serpiente escribía el ArrayDeque con advance() mientras el EDT lo copiaba con snapshot(). Se
comprobó ejecutando el código original con 40 serpientes y un hilo que solo repinta: el render falla con
NullPointerException en pocos segundos. Ahora todo el estado de la serpiente (cuerpo, dirección y maxLength)
queda bajo su propio monitor intrínseco, que es el lock de grano más fino posible porque dos serpientes
distintas nunca se bloquean entre sí; snapshot() devuelve una copia inmutable tomada bajo ese lock, de modo que
el EDT jamás toca la estructura viva. turn() pasó a ser synchronized para que la comprobación de no ir en
reversa y la escritura de la dirección sean una sola operación atómica, ya que la invocan a la vez el EDT
(teclado) y el propio runner (giro aleatorio). Se añadió peek(), que devuelve cabeza y dirección leídas bajo el
mismo lock, para que el tablero no calcule la casilla destino con un estado mezclado. El orden de adquisición
es siempre Board y luego Snake, y ningún método de Snake llama al Board, por lo que no puede formarse un ciclo
ni un deadlock.

Correcciones en la UI. togglePause() deducía el estado del texto del botón; ahora consulta el estado real del
GameClock, con lo que el botón y la barra espaciadora no pueden desincronizarse. El executor de las serpientes
dejó de ser una variable local y es un campo que se interrumpe al cerrar la ventana, junto con el cierre del
reloj: antes nadie podía detener los hilos y el manejo de InterruptedException era código muerto. Por último,
el constructor ya no lanza hilos ni hace setVisible(true); eso se movió a start(), que se invoca cuando el
objeto ya está completamente construido, evitando la publicación insegura de this.

Verificación. Con 40 serpientes y un hilo que simula al EDT leyendo el tablero y las serpientes sin pausa
durante varios segundos no se produce ninguna excepción; al pausar, las cabezas de las 40 serpientes quedan
idénticas durante 800 ms y el reloj no ejecuta ni un solo tick; al reanudar, ambas cosas se restablecen; y tras
stop() todos los hilos terminan por sí solos dentro del timeout.

### 3) Control de ejecución seguro (UI)

- Implementa la **UI** con **Iniciar / Pausar / Reanudar** (ya existe el botón _Action_ y el reloj `GameClock`).
- Al **Pausar**, muestra de forma **consistente** (sin _tearing_):
  - La **serpiente viva más larga**.
  - La **peor serpiente** (la que **primero murió**).
- Considera que la suspensión **no es instantánea**; coordina para que el estado mostrado no quede “a medias”.

Reglas de muerte. El enunciado pide mostrar la peor serpiente, entendida como la primera que murió, pero el
juego original no tenía ninguna noción de muerte: dos serpientes podían ocupar la misma casilla sin
consecuencia. Se añadió la regla mínima que hace significativa esa estadística: si la cabeza entra en una
casilla ocupada por un cuerpo vivo, propio o ajeno, la serpiente muere. La comprobación de ocupación y el
avance ocurren dentro del mismo lock del tablero, de modo que dos serpientes no pueden cruzarse leyendo cada
una un estado anterior al movimiento de la otra. Al morir, la serpiente se marca como no viva, se registra en
la lista de orden de muertes y su hilo termina, en lugar de quedar girando o bloqueado. Los cadáveres se
siguen dibujando en gris pero no bloquean, para que el tablero no se sature con N alto. El choque contra
obstáculo se mantiene como rebote, tal como describe el enunciado.

Controles Iniciar / Pausar / Reanudar. La UI pasó de un único botón Action a dos: Iniciar, que arranca el
reloj y lanza un hilo virtual por serpiente y queda deshabilitado después, y Pausar/Reanudar, habilitado solo
una vez iniciada la partida. La barra espaciadora sigue funcionando y ya no puede desincronizarse del botón,
porque ambos consultan el estado real del GameClock. Debajo del tablero se añadió una barra de estado donde se
publican las estadísticas de la pausa.

Consistencia de las estadísticas. El problema es que pausar no detiene a las serpientes de inmediato: una
serpiente puede llevar hasta 80 ms dormida y despertar para completar su movimiento después de que la UI ya
haya leído los datos. Para coordinarlo, el GameClock lleva la cuenta de cuántos trabajadores hay registrados,
cuántos están efectivamente bloqueados en awaitIfPaused() y cuántos han terminado, y expone awaitAllPaused(),
que espera a que todos estén bloqueados o terminados. Solo cuando esa barrera se cumple se piden las
estadísticas al tablero. Estas se calculan con el lock del tablero tomado, así que ninguna serpiente puede
estar a mitad de un step() mientras se leen: todas las longitudes corresponden al mismo instante lógico. El
resultado se devuelve como un record inmutable, RaceStats, que se publica al EDT con invokeLater y no puede
cambiar mientras se dibuja; por eso no hay tearing. La espera de la quiescencia se hace en un hilo auxiliar y
no en el EDT, para no congelar la interfaz mientras dura, y tiene un timeout de dos segundos tras el cual la
lectura se marca como parcial en vez de bloquearse indefinidamente.

Verificación. Con 30 serpientes, la pausa se vuelve efectiva en unos 50 ms; las estadísticas leídas en ese
momento y las releídas 900 ms después son idénticas, igual que las cabezas de las 30 serpientes, lo que
confirma que nadie se movió después de la foto; el valor de la serpiente viva más larga coincide con el
recálculo hecho por fuera; al reanudar todas vuelven a moverse; y al detener, todos los hilos terminan solos.

### 4) Robustez bajo carga

- Ejecuta con **N alto** (`-Dsnakes=20` o más) y/o aumenta la velocidad.
- El juego **no debe romperse**: sin `ConcurrentModificationException`, sin lecturas inconsistentes, sin _deadlocks_.
- Si habilitas **teleports** y **turbo**, verifica que las reglas no introduzcan carreras.

Defectos encontrados al subir N. Ejecutando con 50 serpientes aparecieron tres problemas que no eran de
sincronización sino de reglas, pero que igualmente rompían la partida. El primero es el reparto inicial: la
fórmula original (2 + i*3 mod ancho, 2 + i*2 mod alto) repite posiciones en cuanto N supera unas pocas
decenas, de modo que varias serpientes nacían una encima de otra y morían en su primer movimiento. Se
sustituyó por Board.spawnPositions(n), que garantiza casillas distintas y libres. El segundo es el crecimiento
sin tope de los obstáculos: cada ratón comido añadía uno nuevo que jamás se retiraba, así que en una partida
larga el tablero terminaba saturado y las serpientes solo rebotaban; ahora hay un límite del 10% de las
casillas. El tercero es que randomEmpty() solo evitaba otros ítems, por lo que un obstáculo nuevo podía
aparecer justo encima de una serpiente; ahora también evita los cuerpos vivos. Además, el tablero se
dimensiona en función de N (mínimo unas 45 casillas por serpiente), porque con 50 serpientes en 35x28 la
densidad hace que la mayoría choque en el primer segundo: en 35x28 quedaban 4 vivas a los 6 segundos, y con el
tablero escalado a 60x48 quedan 11.

Eliminación del tearing en el render. El análisis del punto 1 señalaba que paintComponent hacía cinco lecturas
independientes del mundo tomadas en instantes distintos. Se añadió Board.snapshot(), que devuelve un record
inmutable WorldSnapshot con ratones, obstáculos, turbo, teleports y el cuerpo de cada serpiente capturados de
una sola vez bajo el lock. La UI dibuja a partir de esa única foto, de modo que un fotograma corresponde
siempre a un instante real de la partida y no a una mezcla de varios.

Teleports y turbo. Ambas reglas quedan dentro de la región crítica de step(), así que no introducen carreras:
la resolución del teleport, el consumo del ratón o del turbo y el avance de la serpiente ocurren sin soltar el
lock. El consumo es un check-and-act atómico (mice.remove y turbo.remove devuelven si el ítem estaba), por lo
que un mismo ratón o un mismo turbo no pueden ser consumidos por dos serpientes. El estado de turbo
(turboTicks) es una variable local de cada SnakeRunner, no compartida, así que no necesita sincronización.

Pruebas automáticas. Se añadieron siete pruebas JUnit 5 en src/test/java que se ejecutan con mvn clean verify:

- Las casillas iniciales nunca se repiten, ni siquiera con N alto.
- Con 40 hilos moviéndose y un hilo leyendo el mundo sin descanso durante tres segundos no se produce ninguna
  excepción de concurrencia.
- Dos serpientes vivas nunca comparten casilla en una foto coherente, verificado de forma continua durante
  tres segundos con 30 serpientes.
- El número de obstáculos se mantiene acotado en una partida larga.
- En pausa el reloj no ejecuta ni un solo tick, lo que demuestra que la espera es por señal y no por sondeo.
- Al pausar, las estadísticas y las posiciones de las 25 serpientes son idénticas al releerlas 600 ms después,
  y al reanudar vuelven a moverse.
- Al detener, todas las serpientes bloqueadas despiertan y sus hilos terminan.

Las tres pruebas del reloj llevan @Timeout, de modo que un deadlock o un despertar perdido harían fallar la
prueba por tiempo en lugar de colgar la construcción. Las siete pasan.

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

### Resumen de entregables

#### a) Data races encontradas y su solución

| # | Riesgo | Dónde estaba | Solución aplicada |
|---|--------|--------------|-------------------|
| 1 | El hilo de la serpiente escribía el cuerpo con `advance()` mientras el EDT lo copiaba con `snapshot()`. Falla observada: `NullPointerException` en el render a los pocos segundos con 40 serpientes | `Snake.body` (`ArrayDeque`, sin protección) | Todo el estado de la serpiente pasa a estar bajo su propio monitor; `snapshot()` devuelve una copia inmutable tomada bajo ese lock |
| 2 | *Check-then-act* sobre la dirección, escrito a la vez por el EDT (teclado) y por el runner (giro aleatorio): podía romperse la invariante de no ir en reversa | `Snake.turn()` | Método `synchronized`: comprobar y escribir es una sola operación atómica |
| 3 | `int` sin `volatile` ni lock: sin garantía de visibilidad entre hilos | `Snake.maxLength` | Queda bajo el mismo monitor de la serpiente |
| 4 | La dirección podía cambiar entre la lectura de la cabeza y la de la dirección, calculando la casilla destino con un estado mezclado | `Board.step()` | Nuevo `Snake.peek()`, que devuelve cabeza y dirección leídas bajo el mismo lock |
| 5 | *Tearing*: el fotograma mezclaba cinco lecturas del mundo tomadas en instantes distintos | `GamePanel.paintComponent()` | Nuevo `Board.snapshot()` → record inmutable `WorldSnapshot` capturado de una sola vez bajo el lock |
| 6 | `stop()` no cancelaba la tarea periódica, así que un `start()` posterior dejaba **dos** relojes vivos | `GameClock` | `pause()`/`stop()` cancelan el `ScheduledFuture`; las transiciones de estado ocurren bajo el monitor |
| 7 | El estado de pausa se deducía del **texto del botón**: la vista era la fuente de verdad y podía desincronizarse del reloj | `SnakeApp.togglePause()` | Se consulta `clock.state()`, única fuente de verdad |
| 8 | Publicación insegura: el constructor lanzaba los hilos y hacía `setVisible(true)`, exponiendo `this` antes de terminar de construirse | `SnakeApp` | El constructor solo arma la ventana; los hilos arrancan desde el botón *Iniciar* |

#### b) Colecciones mal usadas y cómo se protegieron o sustituyeron

| Estructura | Problema | Solución |
|------------|----------|----------|
| `Snake.body` (`ArrayDeque`) | Compartida entre el hilo de la serpiente y el EDT **sin protección alguna**. El caso crítico | Protegida por el monitor de la serpiente; hacia afuera solo se expone `List.copyOf(...)`, nunca la estructura viva |
| `Board.mice`, `obstacles`, `turbo` (`HashSet`) y `teleports` (`HashMap`) | Estaban cubiertos, pero al precio de un `synchronized` global que también bloqueaba cada lectura del render | Sustituidos por `ConcurrentHashMap.newKeySet()` y `ConcurrentHashMap`. Las lecturas ya no toman lock y devuelven vistas inmutables (`Set.copyOf` / `Map.copyOf`) |
| `SnakeApp.snakes` (`ArrayList`) | Compartida con el EDT sin barrera de memoria explícita; cualquier alta o baja en ejecución habría dado `ConcurrentModificationException` | Se construye y luego se publica como `List.copyOf(...)`: inmutable y de publicación segura |
| Reparto inicial de posiciones | No es una colección, pero repetía casillas con N alto y varias serpientes nacían superpuestas | `Board.spawnPositions(n)` garantiza casillas distintas y libres |

#### c) Esperas activas eliminadas y mecanismo utilizado

| Dónde | Qué hacía antes | Mecanismo que la sustituye |
|-------|-----------------|----------------------------|
| `GameClock` | Durante la pausa la tarea periódica seguía disparándose cada 60 ms y en cada disparo consultaba el estado para no hacer nada: *polling* | `pause()` cancela el `ScheduledFuture` y `resume()` lo reprograma. **Cero ticks durante la pausa** (verificado por prueba) |
| `SnakeRunner` | No existía forma de pausar: al pausar solo se congelaba el repintado y las serpientes seguían moviéndose. Resolverlo con `while (paused) {}` habría introducido *busy-wait* | `clock.awaitIfPaused()` bloquea el hilo con `wait()` sobre el monitor del reloj; `resume()` lo libera con `notifyAll()`. La espera está dentro de un `while` sobre la condición, no de un `if`, para no perder despertares |
| Lectura de estadísticas al pausar | Habría requerido sondear hasta que las serpientes se detuvieran, porque la suspensión no es instantánea | `clock.awaitAllPaused(timeout)`: barrera de quiescencia que espera, también con `wait()`, a que todos los trabajadores estén bloqueados o terminados |
| Parada de los hilos | El executor era una variable local: nadie podía detener las serpientes y el `catch (InterruptedException)` era código muerto | `stop()` hace `notifyAll()` y `awaitIfPaused()` devuelve `false`, de modo que cada hilo sale de su bucle por sí solo |

#### d) Regiones críticas definidas y justificación de su alcance mínimo

| Lock | Qué protege | Por qué es el alcance mínimo |
|------|-------------|------------------------------|
| `ReentrantLock` de `Board` | Solo la secuencia compuesta de `step()` (consultar obstáculo, resolver teleport, detectar choque, consumir ratón o turbo, avanzar y reponer ítems) y las fotos coherentes `stats()` / `snapshot()` | Esa secuencia **debe** ser atómica frente a las demás serpientes: si se soltara el lock a mitad, dos podrían comer el mismo ratón, cruzarse sin chocar o avanzar sobre un mundo obsoleto. Fuera de ella el lock no se toma: los cuatro getters de lectura son ahora libres de lock, así que el render ya no compite con la simulación |
| Monitor intrínseco de cada `Snake` | El cuerpo, la dirección y `maxLength` de **esa** serpiente | Es el grano más fino posible: dos serpientes distintas nunca se bloquean entre sí. La alternativa (un lock global de serpientes) serializaría movimientos independientes sin ninguna necesidad |
| Monitor de `GameClock` | El estado de la partida, la tarea programada y el conteo de trabajadores registrados, bloqueados y terminados | Son datos que solo tienen sentido leídos juntos: el estado decide si un hilo se bloquea y el conteo decide si la pausa ya es efectiva. Las esperas se hacen con `wait()`, que libera el monitor mientras tanto |

**Ausencia de deadlock.** El orden de adquisición es siempre `Board → Snake`, y ningún método de `Snake` invoca al `Board`, por lo que no puede formarse un ciclo. El monitor del `GameClock` nunca se mantiene tomado mientras se pide el lock del tablero: la barrera de quiescencia termina antes de leer las estadísticas. Las pruebas del reloj llevan `@Timeout`, de modo que un deadlock o un despertar perdido harían fallar la prueba por tiempo en lugar de colgar la construcción.

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

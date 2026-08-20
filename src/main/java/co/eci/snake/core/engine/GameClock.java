package co.eci.snake.core.engine;

import co.eci.snake.core.GameState;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reloj y estado global de la partida. Es la única fuente de verdad de Iniciar / Pausar / Reanudar,
 * tanto para el repintado como para los hilos de las serpientes.
 *
 * <p>Correcciones respecto de la versión original:</p>
 * <ul>
 *   <li><b>Sin ticks vacíos.</b> Antes la tarea periódica seguía disparándose durante la pausa y en
 *       cada disparo consultaba el estado para no hacer nada: era polling. Ahora {@code pause()}
 *       cancela el {@link ScheduledFuture} y {@code resume()} lo reprograma.</li>
 *   <li><b>Sin doble programación.</b> {@code stop()} cancela la tarea, de modo que un {@code start()}
 *       posterior no puede dejar dos tareas periódicas vivas a la vez.</li>
 *   <li><b>Pausa real, sin espera activa.</b> Los hilos de las serpientes llaman a
 *       {@link #awaitIfPaused()} y quedan <i>bloqueados</i> en {@code wait()} sobre el monitor,
 *       no girando en un bucle. {@code resume()} y {@code stop()} despiertan a todos con
 *       {@code notifyAll()}, así que no hay lost wakeups.</li>
 * </ul>
 */
public final class GameClock implements AutoCloseable {

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "game-clock");
        t.setDaemon(true);
        return t;
      });

  private final long periodMillis;
  private final Runnable tick;

  /** Monitor único que protege el estado y sobre el que esperan las serpientes. */
  private final Object monitor = new Object();
  private GameState state = GameState.STOPPED;   // guarded by monitor
  private ScheduledFuture<?> task;               // guarded by monitor

  // Contabilidad de trabajadores, para saber cuándo la pausa se hizo efectiva de verdad.
  private int workers;    // guarded by monitor
  private int parked;     // guarded by monitor
  private int finished;   // guarded by monitor

  public GameClock(long periodMillis, Runnable tick) {
    if (periodMillis <= 0) throw new IllegalArgumentException("periodMillis must be > 0");
    this.periodMillis = periodMillis;
    this.tick = Objects.requireNonNull(tick, "tick");
  }

  public void start() {
    synchronized (monitor) {
      if (state != GameState.STOPPED) return;
      state = GameState.RUNNING;
      schedule();
      monitor.notifyAll();
    }
  }

  public void pause() {
    synchronized (monitor) {
      if (state != GameState.RUNNING) return;
      state = GameState.PAUSED;
      cancel();
    }
  }

  public void resume() {
    synchronized (monitor) {
      if (state != GameState.PAUSED) return;
      state = GameState.RUNNING;
      schedule();
      monitor.notifyAll();   // libera a las serpientes bloqueadas
    }
  }

  public void stop() {
    synchronized (monitor) {
      state = GameState.STOPPED;
      cancel();
      monitor.notifyAll();   // despierta a las serpientes para que terminen
    }
  }

  public GameState state() {
    synchronized (monitor) {
      return state;
    }
  }

  public boolean isRunning() {
    return state() == GameState.RUNNING;
  }

  /**
   * Punto de suspensión de los hilos trabajadores. Bloquea mientras la partida esté en pausa y
   * devuelve {@code false} cuando la partida se detuvo, para que el hilo salga de su bucle.
   *
   * <p>El {@code while} (y no un {@code if}) alrededor del {@code wait()} es intencional: protege
   * frente a despertares espurios y frente a un {@code pause()} que llegue justo después de un
   * {@code notifyAll()}.</p>
   */
  public boolean awaitIfPaused() throws InterruptedException {
    synchronized (monitor) {
      if (state == GameState.PAUSED) {
        parked++;
        monitor.notifyAll();          // avisa a quien esté esperando la quiescencia
        try {
          while (state == GameState.PAUSED) {
            monitor.wait();
          }
        } finally {
          parked--;
        }
      }
      return state == GameState.RUNNING;
    }
  }

  /** Declara cuántos hilos trabajadores van a suspenderse en {@link #awaitIfPaused()}. */
  public void registerWorkers(int n) {
    synchronized (monitor) {
      workers += n;
      monitor.notifyAll();
    }
  }

  /** Un trabajador terminó (por muerte de su serpiente o por parada); ya no se le espera. */
  public void workerFinished() {
    synchronized (monitor) {
      finished++;
      monitor.notifyAll();
    }
  }

  /**
   * Espera a que la pausa sea <b>efectiva</b>: que todos los trabajadores estén bloqueados o hayan
   * terminado. La suspensión no es instantánea (una serpiente puede llevar hasta 80 ms dormida), así
   * que sin esta barrera las estadísticas se leerían mientras alguien todavía se mueve.
   *
   * <p>No debe invocarse desde el EDT: bloquea. En la UI se llama desde un hilo auxiliar y el
   * resultado se publica con {@code invokeLater}.</p>
   *
   * @return {@code true} si se alcanzó la quiescencia dentro del tiempo dado
   */
  public boolean awaitAllPaused(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    synchronized (monitor) {
      while (parked + finished < workers) {
        long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) return false;
        monitor.wait(remainingMs);
      }
      return true;
    }
  }

  private void schedule() {
    assert Thread.holdsLock(monitor);
    if (task == null || task.isCancelled() || task.isDone()) {
      task = scheduler.scheduleAtFixedRate(tick, 0, periodMillis, TimeUnit.MILLISECONDS);
    }
  }

  private void cancel() {
    assert Thread.holdsLock(monitor);
    if (task != null) {
      task.cancel(false);
      task = null;
    }
  }

  @Override
  public void close() {
    stop();
    scheduler.shutdownNow();
  }
}

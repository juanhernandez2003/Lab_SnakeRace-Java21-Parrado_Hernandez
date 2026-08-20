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
      while (state == GameState.PAUSED) {
        monitor.wait();
      }
      return state == GameState.RUNNING;
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

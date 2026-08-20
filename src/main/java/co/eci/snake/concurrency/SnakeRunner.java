package co.eci.snake.concurrency;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ciclo autónomo de una serpiente, ejecutado en su propio hilo virtual.
 *
 * <p>La única corrección de concurrencia aquí es el punto de suspensión: antes el runner no tenía
 * forma de pausarse, por lo que las serpientes seguían moviéndose con la imagen congelada. Ahora
 * cada iteración empieza consultando al {@link GameClock}, que <b>bloquea</b> al hilo con
 * {@code wait()} mientras la partida esté en pausa. No hay espera activa: el hilo no consume CPU
 * mientras espera, y se reanuda por señal ({@code notifyAll}), no por sondeo.</p>
 */
public final class SnakeRunner implements Runnable {
  private final Snake snake;
  private final Board board;
  private final GameClock clock;
  private final int baseSleepMs = 80;
  private final int turboSleepMs = 40;
  private int turboTicks = 0;

  public SnakeRunner(Snake snake, Board board, GameClock clock) {
    this.snake = Objects.requireNonNull(snake, "snake");
    this.board = Objects.requireNonNull(board, "board");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void run() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        // Suspensión por señal: si la partida está pausada el hilo queda bloqueado aquí;
        // si fue detenida, awaitIfPaused devuelve false y el bucle termina.
        if (!clock.awaitIfPaused()) return;

        maybeTurn();
        var res = board.step(snake);
        if (res == Board.MoveResult.HIT_OBSTACLE) {
          randomTurn();
        } else if (res == Board.MoveResult.ATE_TURBO) {
          turboTicks = 100;
        }
        int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
        if (turboTicks > 0) turboTicks--;
        Thread.sleep(sleep);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void maybeTurn() {
    double p = (turboTicks > 0) ? 0.05 : 0.10;
    if (ThreadLocalRandom.current().nextDouble() < p) randomTurn();
  }

  private void randomTurn() {
    var dirs = Direction.values();
    snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
  }
}

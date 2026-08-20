package co.eci.snake.core.engine;

import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.GameState;
import co.eci.snake.core.Position;
import co.eci.snake.core.RaceStats;
import co.eci.snake.core.Snake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pausa sin espera activa, quiescencia y parada ordenada.
 *
 * <p>Todas llevan {@code @Timeout}: si alguna corrección introdujera un deadlock o un despertar
 * perdido, la prueba falla por tiempo en vez de colgar la construcción.</p>
 */
class GameClockTest {

  private static List<Snake> spawn(Board board, int n) {
    var positions = board.spawnPositions(n);
    var snakes = new ArrayList<Snake>(n);
    for (int i = 0; i < n; i++) {
      var p = positions.get(i);
      snakes.add(Snake.of("S" + i, p.x(), p.y(), Direction.values()[i % Direction.values().length]));
    }
    var shared = List.copyOf(snakes);
    board.register(shared);
    return shared;
  }

  private static List<Position> heads(List<Snake> snakes) {
    var heads = new ArrayList<Position>(snakes.size());
    for (Snake s : snakes) heads.add(s.head());
    return heads;
  }

  @Test
  @Timeout(15)
  @DisplayName("En pausa no se ejecuta ningún tick: la espera es por señal, no por sondeo")
  void pauseStopsTicksCompletely() throws Exception {
    AtomicLong ticks = new AtomicLong();
    try (GameClock clock = new GameClock(10, ticks::incrementAndGet)) {
      clock.start();
      Thread.sleep(200);
      assertTrue(ticks.get() > 0, "el reloj no arrancó");

      clock.pause();
      Thread.sleep(50);
      long afterPause = ticks.get();
      Thread.sleep(500);
      assertEquals(afterPause, ticks.get(), "el reloj siguió tickeando durante la pausa");

      clock.resume();
      Thread.sleep(200);
      assertTrue(ticks.get() > afterPause, "el reloj no se reanudó");
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("Al pausar, las estadísticas son consistentes y nadie se mueve después de la foto")
  void pauseIsQuiescentAndStatsAreStable() throws Exception {
    Board board = new Board(60, 45);
    List<Snake> snakes = spawn(board, 25);
    GameClock clock = new GameClock(16, () -> { });
    ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    try {
      clock.registerWorkers(snakes.size());
      clock.start();
      snakes.forEach(s -> exec.submit(new SnakeRunner(s, board, clock)));
      Thread.sleep(1500);

      clock.pause();
      assertTrue(clock.awaitAllPaused(3000), "la pausa no se hizo efectiva a tiempo");

      RaceStats first = board.stats();
      List<Position> headsBefore = heads(snakes);
      Thread.sleep(600);

      assertEquals(first, board.stats(), "las estadísticas cambiaron durante la pausa");
      assertEquals(headsBefore, heads(snakes), "una serpiente se movió después de la pausa");

      clock.resume();
      Thread.sleep(500);
      assertNotEquals(headsBefore, heads(snakes), "no se reanudó el movimiento");
    } finally {
      clock.close();
      exec.shutdownNow();
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("Detener despierta a todas las serpientes y sus hilos terminan (sin deadlock)")
  void stopTerminatesEveryThread() throws Exception {
    Board board = new Board(60, 45);
    List<Snake> snakes = spawn(board, 25);
    GameClock clock = new GameClock(16, () -> { });
    ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    try {
      clock.registerWorkers(snakes.size());
      clock.start();
      snakes.forEach(s -> exec.submit(new SnakeRunner(s, board, clock)));
      Thread.sleep(800);

      clock.pause();                       // se detienen bloqueadas en wait()
      clock.awaitAllPaused(3000);
      clock.stop();                        // debe despertarlas para que terminen

      exec.shutdown();
      assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS),
          "quedaron hilos vivos tras detener la partida");
      assertEquals(GameState.STOPPED, clock.state());
    } finally {
      clock.close();
      exec.shutdownNow();
    }
  }
}

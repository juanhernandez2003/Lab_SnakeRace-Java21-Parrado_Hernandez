package co.eci.snake.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariantes del tablero bajo concurrencia. Todas las pruebas lanzan N hilos reales contra el mismo
 * Board y comprueban propiedades que solo se sostienen si las regiones críticas son correctas.
 */
class BoardConcurrencyTest {

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

  @Test
  @DisplayName("Las casillas iniciales nunca se repiten, ni siquiera con N alto")
  void spawnPositionsAreDistinct() {
    Board board = new Board(35, 28);
    List<Position> positions = board.spawnPositions(50);
    assertEquals(50, positions.size(), "faltan posiciones");
    assertEquals(50, new HashSet<>(positions).size(), "hay posiciones repetidas");
  }

  @Test
  @DisplayName("Con 40 hilos moviéndose y un hilo leyendo no hay excepciones de concurrencia")
  void noConcurrencyExceptionsUnderLoad() throws Exception {
    Board board = new Board(60, 45);
    List<Snake> snakes = spawn(board, 40);

    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    ExecutorService movers = Executors.newVirtualThreadPerTaskExecutor();
    for (Snake s : snakes) {
      movers.submit(() -> {
        try {
          while (!stop.get()) {
            board.step(s);
            Thread.sleep(2);          // ritmo realista, como el SnakeRunner
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        }
      });
    }
    // Simula el EDT repintando sin descanso.
    Thread painter = new Thread(() -> {
      try {
        while (!stop.get()) {
          board.snapshot();
          board.stats();
        }
      } catch (Throwable t) {
        failure.compareAndSet(null, t);
      }
    }, "painter");
    painter.start();

    Thread.sleep(3000);
    stop.set(true);
    movers.shutdownNow();
    painter.join(2000);

    assertNull(failure.get(), () -> "excepción bajo carga: " + failure.get());
  }

  @Test
  @DisplayName("Dos serpientes vivas nunca comparten casilla en una foto coherente")
  void liveSnakesNeverOverlap() throws Exception {
    Board board = new Board(60, 45);
    List<Snake> snakes = spawn(board, 30);

    AtomicBoolean stop = new AtomicBoolean(false);
    ExecutorService movers = Executors.newVirtualThreadPerTaskExecutor();
    for (Snake s : snakes) {
      movers.submit(() -> {
        try {
          while (!stop.get()) { board.step(s); Thread.sleep(2); }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      });
    }

    try {
      long end = System.nanoTime() + 3_000_000_000L;
      while (System.nanoTime() < end) {
        WorldSnapshot world = board.snapshot();
        Set<Position> seen = new HashSet<>();
        for (WorldSnapshot.SnakeView v : world.snakes()) {
          if (!v.alive()) continue;
          for (Position p : v.body()) {
            assertTrue(seen.add(p), "dos serpientes vivas ocupan " + p);
          }
        }
      }
    } finally {
      stop.set(true);
      movers.shutdownNow();
    }
  }

  @Test
  @DisplayName("El número de obstáculos se mantiene acotado en una partida larga")
  void obstaclesStayBounded() throws Exception {
    Board board = new Board(35, 28);
    List<Snake> snakes = spawn(board, 8);

    AtomicBoolean stop = new AtomicBoolean(false);
    ExecutorService movers = Executors.newVirtualThreadPerTaskExecutor();
    for (Snake s : snakes) {
      movers.submit(() -> {
        try {
          while (!stop.get()) { board.step(s); Thread.sleep(2); }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      });
    }
    Thread.sleep(3000);
    stop.set(true);
    movers.shutdownNow();
    movers.awaitTermination(2, TimeUnit.SECONDS);

    int cells = 35 * 28;
    assertTrue(board.obstacles().size() <= cells / 10 + 1,
        "los obstáculos crecieron sin control: " + board.obstacles().size());
  }
}

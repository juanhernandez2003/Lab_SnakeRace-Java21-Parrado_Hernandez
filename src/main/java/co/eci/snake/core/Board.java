package co.eci.snake.core;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mundo compartido por todas las serpientes.
 *
 * <p>Antes la clase entera era {@code synchronized}: un único lock global cubría tanto la mutación
 * de {@code step} como los cuatro getters de lectura, de modo que el EDT competía con las N
 * serpientes en cada repintado y la simulación quedaba serializada por completo.</p>
 *
 * <p>Ahora el estado vive en colecciones concurrentes, de forma que <b>las lecturas no toman ningún
 * lock</b>, y el lock explícito protege únicamente la región crítica estrictamente necesaria: la
 * secuencia compuesta de {@code step} (consultar obstáculo, resolver teleport, consumir ratón o
 * turbo, avanzar la serpiente y reponer ítems), que debe ser atómica frente a las demás
 * serpientes.</p>
 */
public final class Board {
  private final int width;
  private final int height;

  private final Set<Position> mice = ConcurrentHashMap.newKeySet();
  private final Set<Position> obstacles = ConcurrentHashMap.newKeySet();
  private final Set<Position> turbo = ConcurrentHashMap.newKeySet();
  private final Map<Position, Position> teleports = new ConcurrentHashMap<>();

  /** Región crítica del mundo: solo protege la transición de un movimiento completo. */
  private final ReentrantLock lock = new ReentrantLock();

  public enum MoveResult { MOVED, ATE_MOUSE, HIT_OBSTACLE, ATE_TURBO, TELEPORTED }

  public Board(int width, int height) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Board dimensions must be positive");
    this.width = width;
    this.height = height;
    for (int i = 0; i < 6; i++) mice.add(randomEmpty());
    for (int i = 0; i < 4; i++) obstacles.add(randomEmpty());
    for (int i = 0; i < 3; i++) turbo.add(randomEmpty());
    createTeleportPairs(2);
  }

  public int width() { return width; }
  public int height() { return height; }

  // Lecturas sin lock: las colecciones son concurrentes y se publica una vista inmutable, así que el
  // render nunca puede provocar ConcurrentModificationException ni frenar a las serpientes.
  public Set<Position> mice() { return Set.copyOf(mice); }
  public Set<Position> obstacles() { return Set.copyOf(obstacles); }
  public Set<Position> turbo() { return Set.copyOf(turbo); }
  public Map<Position, Position> teleports() { return Map.copyOf(teleports); }

  /**
   * Avanza una serpiente una casilla. Toda la secuencia se ejecuta bajo el lock del tablero porque es
   * una acción compuesta sobre varias estructuras: si se liberara el lock a mitad, dos serpientes
   * podrían consumir el mismo ratón o avanzar sobre un estado del mundo ya obsoleto.
   */
  public MoveResult step(Snake snake) {
    Objects.requireNonNull(snake, "snake");
    lock.lock();
    try {
      var view = snake.peek();
      var dir = view.direction();
      Position next = new Position(view.position().x() + dir.dx, view.position().y() + dir.dy)
          .wrap(width, height);

      if (obstacles.contains(next)) return MoveResult.HIT_OBSTACLE;

      boolean teleported = false;
      Position target = teleports.get(next);
      if (target != null) {
        next = target;
        teleported = true;
      }

      boolean ateMouse = mice.remove(next);
      boolean ateTurbo = turbo.remove(next);

      snake.advance(next, ateMouse);

      if (ateMouse) {
        mice.add(randomEmpty());
        obstacles.add(randomEmpty());
        if (ThreadLocalRandom.current().nextDouble() < 0.2) turbo.add(randomEmpty());
      }

      if (ateTurbo) return MoveResult.ATE_TURBO;
      if (ateMouse) return MoveResult.ATE_MOUSE;
      if (teleported) return MoveResult.TELEPORTED;
      return MoveResult.MOVED;
    } finally {
      lock.unlock();
    }
  }

  private void createTeleportPairs(int pairs) {
    for (int i = 0; i < pairs; i++) {
      Position a = randomEmpty();
      Position b = randomEmpty();
      teleports.put(a, b);
      teleports.put(b, a);
    }
  }

  /** Solo se invoca desde el constructor o con el lock ya tomado. */
  private Position randomEmpty() {
    var rnd = ThreadLocalRandom.current();
    Position p;
    int guard = 0;
    do {
      p = new Position(rnd.nextInt(width), rnd.nextInt(height));
      guard++;
      if (guard > width * height * 2) break;
    } while (mice.contains(p) || obstacles.contains(p) || turbo.contains(p) || teleports.containsKey(p));
    return p;
  }
}

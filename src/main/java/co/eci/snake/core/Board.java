package co.eci.snake.core;

import java.util.ArrayList;
import java.util.List;
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

  /** Serpientes de la partida y orden en que fueron muriendo. Ambas listas se tocan solo bajo el lock. */
  private final List<Snake> snakes = new ArrayList<>();
  private final List<Snake> deathOrder = new ArrayList<>();

  public enum MoveResult { MOVED, ATE_MOUSE, HIT_OBSTACLE, ATE_TURBO, TELEPORTED, DIED }

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

      // Choque: la cabeza entra en una casilla ocupada por un cuerpo vivo (propio o ajeno).
      // La comprobación y el avance ocurren bajo el mismo lock, de modo que dos serpientes no
      // pueden "cruzarse" leyendo ambas un estado anterior al movimiento de la otra.
      if (collides(snake, next)) {
        snake.kill();
        deathOrder.add(snake);
        return MoveResult.DIED;
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

  /** Registra las serpientes de la partida. Se invoca una sola vez, antes de arrancar los hilos. */
  public void register(List<Snake> participants) {
    Objects.requireNonNull(participants, "participants");
    lock.lock();
    try {
      snakes.clear();
      snakes.addAll(participants);
      deathOrder.clear();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Estadísticas coherentes de la carrera.
   *
   * <p>Se calculan con el lock del tablero tomado, de modo que ninguna serpiente puede estar a mitad
   * de un {@code step()} mientras se leen: o el movimiento ya terminó, o todavía no ha empezado.
   * Todas las longitudes corresponden por tanto al mismo instante lógico y el record resultante,
   * al ser inmutable, viaja al EDT sin posibilidad de cambiar mientras se dibuja.</p>
   */
  public RaceStats stats() {
    lock.lock();
    try {
      RaceStats.SnakeStat longest = null;
      int alive = 0;
      for (Snake s : snakes) {
        if (!s.isAlive()) continue;
        alive++;
        int len = s.length();
        if (longest == null || len > longest.length()) {
          longest = new RaceStats.SnakeStat(s.name(), len);
        }
      }
      RaceStats.SnakeStat worst = deathOrder.isEmpty()
          ? null
          : new RaceStats.SnakeStat(deathOrder.get(0).name(), deathOrder.get(0).length());
      return new RaceStats(longest, worst, alive, snakes.size());
    } finally {
      lock.unlock();
    }
  }

  /** Solo se invoca con el lock tomado. Los cadáveres no bloquean: solo cuentan los cuerpos vivos. */
  private boolean collides(Snake mover, Position next) {
    for (Snake other : snakes) {
      if (!other.isAlive()) continue;
      if (other.occupies(next)) return true;
    }
    return mover.occupies(next);
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

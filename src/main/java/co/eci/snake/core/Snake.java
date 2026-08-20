package co.eci.snake.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Estado de una serpiente.
 *
 * <p>Región crítica: el cuerpo ({@code body}), la dirección y {@code maxLength} son mutados por el
 * hilo de la serpiente y leídos por el EDT (repintado y teclado). Todo ese estado queda protegido
 * por el monitor intrínseco de la propia serpiente, que es el lock de grano más fino posible: dos
 * serpientes distintas nunca se bloquean entre sí.</p>
 *
 * <p>Orden de adquisición de locks del sistema: Board -> Snake. Ningún método de Snake invoca al
 * Board, por lo que no puede formarse un ciclo y no hay riesgo de deadlock.</p>
 */
public final class Snake {

  /** Lectura atómica de cabeza + dirección, para que quien decide el movimiento vea un par coherente. */
  public record Head(Position position, Direction direction) {}

  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  private final String name;
  private final Deque<Position> body = new ArrayDeque<>();
  private Direction direction;
  private int maxLength = 5;
  private boolean alive = true;

  private Snake(String name, Position start, Direction dir) {
    this.name = name;
    body.addFirst(start);
    this.direction = dir;
  }

  public static Snake of(int x, int y, Direction dir) {
    return of("Serpiente " + SEQ.getAndIncrement(), x, y, dir);
  }

  public static Snake of(String name, int x, int y, Direction dir) {
    return new Snake(Objects.requireNonNull(name, "name"), new Position(x, y),
        Objects.requireNonNull(dir, "dir"));
  }

  /** Inmutable, por lo que puede leerse desde cualquier hilo sin sincronización. */
  public String name() {
    return name;
  }

  public synchronized boolean isAlive() {
    return alive;
  }

  /** Marca la serpiente como muerta. Solo lo invoca el Board, con su lock tomado. */
  public synchronized void kill() {
    alive = false;
  }

  /** ¿Esta serpiente ocupa la casilla dada? Se consulta bajo el lock del tablero para detectar choques. */
  public synchronized boolean occupies(Position p) {
    return body.contains(p);
  }

  public synchronized Direction direction() {
    return direction;
  }

  /**
   * Cambia la dirección respetando la invariante de no invertir el sentido.
   *
   * <p>Antes era un check-then-act sin protección: el EDT (teclas) y el propio hilo de la serpiente
   * (giro aleatorio) podían entrelazarse y dejarla yendo en reversa. Al ser {@code synchronized},
   * leer la dirección actual, validarla y escribir la nueva es una sola operación atómica.</p>
   */
  public synchronized void turn(Direction dir) {
    Objects.requireNonNull(dir, "dir");
    if ((direction == Direction.UP && dir == Direction.DOWN) ||
        (direction == Direction.DOWN && dir == Direction.UP) ||
        (direction == Direction.LEFT && dir == Direction.RIGHT) ||
        (direction == Direction.RIGHT && dir == Direction.LEFT)) {
      return;
    }
    this.direction = dir;
  }

  public synchronized Position head() {
    return body.peekFirst();
  }

  /**
   * Cabeza y dirección leídas bajo el mismo lock. Evita que la dirección cambie entre ambas lecturas
   * y que el tablero calcule la casilla destino a partir de un estado mezclado.
   */
  public synchronized Head peek() {
    return new Head(body.peekFirst(), direction);
  }

  /**
   * Copia inmutable del cuerpo para el render.
   *
   * <p>Antes devolvía {@code new ArrayDeque<>(body)} sin sincronizar mientras el hilo de la serpiente
   * insertaba y removía: era la causa de {@code ConcurrentModificationException} y de cuerpos a
   * medias. La copia se hace bajo el lock y se publica como lista inmutable, de modo que el EDT nunca
   * toca la estructura viva.</p>
   */
  public synchronized List<Position> snapshot() {
    return List.copyOf(new ArrayList<>(body));
  }

  public synchronized int length() {
    return body.size();
  }

  public synchronized void advance(Position newHead, boolean grow) {
    Objects.requireNonNull(newHead, "newHead");
    body.addFirst(newHead);
    if (grow) maxLength++;
    while (body.size() > maxLength) body.removeLast();
  }
}

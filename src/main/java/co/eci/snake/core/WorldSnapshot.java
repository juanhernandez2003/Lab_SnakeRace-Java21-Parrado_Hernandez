package co.eci.snake.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fotografía completa y coherente del mundo, tomada en un único instante bajo el lock del tablero.
 *
 * <p>Resuelve el <i>tearing</i> detectado en el análisis: antes el repintado hacía cinco lecturas
 * independientes (ratones, obstáculos, turbo, teleports y el cuerpo de cada serpiente), cada una
 * atómica por separado pero tomadas en momentos distintos, de modo que un mismo fotograma mezclaba
 * varios estados del mundo. Ahora la UI pide una sola vez este record inmutable y dibuja a partir de
 * él, así que lo que se ve en pantalla corresponde siempre a un instante real de la partida.</p>
 */
public record WorldSnapshot(
    Set<Position> mice,
    Set<Position> obstacles,
    Set<Position> turbo,
    Map<Position, Position> teleports,
    List<SnakeView> snakes) {

  /** Estado de una serpiente dentro de la foto. */
  public record SnakeView(String name, boolean alive, List<Position> body) {}
}

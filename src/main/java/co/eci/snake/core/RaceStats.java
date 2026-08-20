package co.eci.snake.core;

/**
 * Fotografía coherente de la carrera, tomada en un único instante bajo el lock del tablero.
 *
 * <p>Es un record inmutable a propósito: una vez construido se puede publicar al EDT sin ningún tipo
 * de sincronización adicional y sin riesgo de que los datos cambien mientras se dibujan. Eso es lo
 * que evita el <i>tearing</i> que pide el enunciado: los valores no se leen campo por campo desde la
 * UI, sino que se calculan todos juntos y se entregan ya congelados.</p>
 *
 * @param longestAlive serpiente viva más larga, o {@code null} si no queda ninguna viva
 * @param firstDead    primera serpiente que murió, o {@code null} si todavía no ha muerto ninguna
 * @param alive        número de serpientes vivas en ese instante
 * @param total        número total de serpientes de la partida
 */
public record RaceStats(SnakeStat longestAlive, SnakeStat firstDead, int alive, int total) {

  /** Nombre y longitud de una serpiente en el instante de la foto. */
  public record SnakeStat(String name, int length) {}

  public String describe() {
    String mejor = (longestAlive == null)
        ? "no queda ninguna viva"
        : longestAlive.name() + " (" + longestAlive.length() + " celdas)";
    String peor = (firstDead == null)
        ? "ninguna ha muerto"
        : firstDead.name() + " (murió primero, con " + firstDead.length() + " celdas)";
    return "Más larga viva: " + mejor + "   |   Peor: " + peor
        + "   |   Vivas: " + alive + "/" + total;
  }
}

package co.eci.snake.ui.legacy;

import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.GameState;
import co.eci.snake.core.Position;
import co.eci.snake.core.RaceStats;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UI legado (Swing).
 *
 * <p>Correcciones de concurrencia introducidas en este punto:</p>
 * <ul>
 *   <li><b>Sin fuga de {@code this}.</b> El constructor solo arma la ventana; los hilos y el reloj
 *       arrancan desde el botón Iniciar, cuando el objeto ya está completamente construido.</li>
 *   <li><b>Control Iniciar / Pausar / Reanudar</b> con estadísticas consistentes al pausar: se espera
 *       a que la suspensión sea efectiva antes de leerlas, y se leen como una sola foto atómica.</li>
 *   <li><b>Estado en un solo lugar.</b> {@code togglePause()} consulta el estado real del
 *       {@link GameClock} en vez de deducirlo del texto del botón, que era una fuente de verdad
 *       ubicada en la vista y podía desincronizarse (botón vs. barra espaciadora).</li>
 *   <li><b>Apagado ordenado.</b> El executor de las serpientes ahora es un campo, se interrumpe al
 *       cerrar la ventana y el reloj se cierra con él; antes era una variable local y los hilos no
 *       se podían detener.</li>
 * </ul>
 */
public final class SnakeApp extends JFrame {

  private final Board board;
  private final GamePanel gamePanel;
  private final JButton startButton;
  private final JButton pauseButton;
  private final JLabel statusLabel;
  private final GameClock clock;
  private final List<Snake> snakes;
  private final ExecutorService snakeExecutor = Executors.newVirtualThreadPerTaskExecutor();
  /** Hilo auxiliar para esperar la quiescencia sin bloquear el EDT. */
  private final ExecutorService uiWorker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ui-worker");
    t.setDaemon(true);
    return t;
  });

  public SnakeApp() {
    super("The Snake Race");
    this.board = new Board(35, 28);

    int n = Integer.getInteger("snakes", 2);
    var built = new java.util.ArrayList<Snake>(n);
    for (int i = 0; i < n; i++) {
      int x = 2 + (i * 3) % board.width();
      int y = 2 + (i * 2) % board.height();
      var dir = Direction.values()[i % Direction.values().length];
      built.add(Snake.of("Serpiente " + i, x, y, dir));
    }
    // Publicación segura: la lista queda inmutable antes de compartirse con el EDT y con los runners.
    this.snakes = List.copyOf(built);
    board.register(this.snakes);

    this.gamePanel = new GamePanel(board, () -> snakes);
    this.startButton = new JButton("Iniciar");
    this.pauseButton = new JButton("Pausar");
    this.pauseButton.setEnabled(false);
    this.statusLabel = new JLabel("Listo. Pulsa Iniciar.");
    this.statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    this.clock = new GameClock(60, () -> SwingUtilities.invokeLater(gamePanel::repaint));

    var controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
    controls.add(startButton);
    controls.add(pauseButton);

    var south = new JPanel(new BorderLayout());
    south.add(controls, BorderLayout.NORTH);
    south.add(statusLabel, BorderLayout.SOUTH);

    setLayout(new BorderLayout());
    add(gamePanel, BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);

    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    pack();
    setLocationRelativeTo(null);

    startButton.addActionListener((ActionEvent e) -> startRace());
    pauseButton.addActionListener((ActionEvent e) -> togglePause());
    bindKeys();

    addWindowListener(new WindowAdapter() {
      @Override public void windowClosing(WindowEvent e) { shutdown(); }
    });
  }

  /** Muestra la ventana. Se invoca cuando el objeto ya está construido, para no publicar {@code this} a medias. */
  public void showUi() {
    setVisible(true);
  }

  /** Iniciar: arranca el reloj y lanza un hilo virtual por serpiente. Solo tiene efecto una vez. */
  private void startRace() {
    if (clock.state() != GameState.STOPPED) return;
    clock.registerWorkers(snakes.size());
    clock.start();
    snakes.forEach(s -> snakeExecutor.submit(new SnakeRunner(s, board, clock)));
    startButton.setEnabled(false);
    pauseButton.setEnabled(true);
    statusLabel.setText("En curso.");
  }

  private void shutdown() {
    clock.close();              // detiene el reloj y despierta a las serpientes bloqueadas
    snakeExecutor.shutdownNow();
    uiWorker.shutdownNow();
  }

  /**
   * Pausar / Reanudar.
   *
   * <p>Al pausar no se leen las estadísticas de inmediato: la suspensión no es instantánea, así que
   * un hilo auxiliar espera a que todas las serpientes estén efectivamente bloqueadas
   * ({@code awaitAllPaused}) y solo entonces pide al tablero una foto coherente. El record resultante
   * es inmutable y se publica en el EDT con {@code invokeLater}, de modo que lo que se muestra nunca
   * queda a medias ni bloquea la interfaz mientras se espera.</p>
   */
  private void togglePause() {
    if (clock.state() == GameState.RUNNING) {
      clock.pause();
      pauseButton.setEnabled(false);
      statusLabel.setText("Pausando…");
      uiWorker.submit(() -> {
        boolean quiesced;
        try {
          quiesced = clock.awaitAllPaused(2000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
        RaceStats stats = board.stats();      // foto atómica bajo el lock del tablero
        String prefix = quiesced ? "PAUSA — " : "PAUSA (parcial) — ";
        SwingUtilities.invokeLater(() -> {
          statusLabel.setText(prefix + stats.describe());
          pauseButton.setText("Reanudar");
          pauseButton.setEnabled(true);
          gamePanel.repaint();                // último repintado con el mundo ya quieto
        });
      });
    } else if (clock.state() == GameState.PAUSED) {
      clock.resume();
      pauseButton.setText("Pausar");
      statusLabel.setText("En curso.");
    }
  }

  private void bindKeys() {
    InputMap im = gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    ActionMap am = gamePanel.getActionMap();

    im.put(KeyStroke.getKeyStroke("SPACE"), "pause");
    am.put("pause", action(this::togglePause));

    var player = snakes.get(0);
    im.put(KeyStroke.getKeyStroke("LEFT"), "left");
    im.put(KeyStroke.getKeyStroke("RIGHT"), "right");
    im.put(KeyStroke.getKeyStroke("UP"), "up");
    im.put(KeyStroke.getKeyStroke("DOWN"), "down");
    am.put("left", action(() -> player.turn(Direction.LEFT)));
    am.put("right", action(() -> player.turn(Direction.RIGHT)));
    am.put("up", action(() -> player.turn(Direction.UP)));
    am.put("down", action(() -> player.turn(Direction.DOWN)));

    if (snakes.size() > 1) {
      var p2 = snakes.get(1);
      im.put(KeyStroke.getKeyStroke('A'), "p2-left");
      im.put(KeyStroke.getKeyStroke('D'), "p2-right");
      im.put(KeyStroke.getKeyStroke('W'), "p2-up");
      im.put(KeyStroke.getKeyStroke('S'), "p2-down");
      am.put("p2-left", action(() -> p2.turn(Direction.LEFT)));
      am.put("p2-right", action(() -> p2.turn(Direction.RIGHT)));
      am.put("p2-up", action(() -> p2.turn(Direction.UP)));
      am.put("p2-down", action(() -> p2.turn(Direction.DOWN)));
    }
  }

  private static AbstractAction action(Runnable r) {
    return new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) { r.run(); }
    };
  }

  public static final class GamePanel extends JPanel {
    private final Board board;
    private final Supplier snakesSupplier;
    private final int cell = 20;

    @FunctionalInterface
    public interface Supplier {
      List<Snake> get();
    }

    public GamePanel(Board board, Supplier snakesSupplier) {
      this.board = board;
      this.snakesSupplier = snakesSupplier;
      setPreferredSize(new Dimension(board.width() * cell + 1, board.height() * cell + 40));
      setBackground(Color.WHITE);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      var g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      g2.setColor(new Color(220, 220, 220));
      for (int x = 0; x <= board.width(); x++)
        g2.drawLine(x * cell, 0, x * cell, board.height() * cell);
      for (int y = 0; y <= board.height(); y++)
        g2.drawLine(0, y * cell, board.width() * cell, y * cell);

      // Obstáculos
      g2.setColor(new Color(255, 102, 0));
      for (var p : board.obstacles()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillRect(x + 2, y + 2, cell - 4, cell - 4);
        g2.setColor(Color.RED);
        g2.drawLine(x + 4, y + 4, x + cell - 6, y + 4);
        g2.drawLine(x + 4, y + 8, x + cell - 6, y + 8);
        g2.drawLine(x + 4, y + 12, x + cell - 6, y + 12);
        g2.setColor(new Color(255, 102, 0));
      }

      // Ratones
      g2.setColor(Color.BLACK);
      for (var p : board.mice()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillOval(x + 4, y + 4, cell - 8, cell - 8);
        g2.setColor(Color.WHITE);
        g2.fillOval(x + 8, y + 8, cell - 16, cell - 16);
        g2.setColor(Color.BLACK);
      }

      // Teleports (flechas rojas)
      Map<Position, Position> tp = board.teleports();
      g2.setColor(Color.RED);
      for (var entry : tp.entrySet()) {
        Position from = entry.getKey();
        int x = from.x() * cell, y = from.y() * cell;
        int[] xs = { x + 4, x + cell - 4, x + cell - 10, x + cell - 10, x + 4 };
        int[] ys = { y + cell / 2, y + cell / 2, y + 4, y + cell - 4, y + cell / 2 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Turbo (rayos)
      g2.setColor(Color.BLACK);
      for (var p : board.turbo()) {
        int x = p.x() * cell, y = p.y() * cell;
        int[] xs = { x + 8, x + 12, x + 10, x + 14, x + 6, x + 10 };
        int[] ys = { y + 2, y + 2, y + 8, y + 8, y + 16, y + 10 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Serpientes: se dibuja la copia inmutable devuelta por snapshot(), nunca la estructura viva.
      var currentSnakes = snakesSupplier.get();
      int idx = 0;
      for (Snake s : currentSnakes) {
        List<Position> body = s.snapshot();
        boolean alive = s.isAlive();
        for (int i = 0; i < body.size(); i++) {
          var p = body.get(i);
          Color base = !alive ? new Color(140, 140, 140)
              : (idx == 0) ? new Color(0, 170, 0) : new Color(0, 160, 180);
          int shade = Math.max(0, 40 - i * 4);
          g2.setColor(new Color(
              Math.min(255, base.getRed() + shade),
              Math.min(255, base.getGreen() + shade),
              Math.min(255, base.getBlue() + shade)));
          g2.fillRect(p.x() * cell + 2, p.y() * cell + 2, cell - 4, cell - 4);
        }
        idx++;
      }
      g2.dispose();
    }
  }

  public static void launch() {
    SwingUtilities.invokeLater(() -> new SnakeApp().showUi());
  }
}

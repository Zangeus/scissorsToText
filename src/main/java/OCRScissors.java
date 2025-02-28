import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Paths;

import net.sourceforge.tess4j.*;

public class OCRScissors {
    private static final Color FILL_COLOR = new Color(30, 30, 30, 50);
    private static final Color BORDER_COLOR = new Color(255, 20, 147);
    private static final Color SHADOW_COLOR = new Color(255, 255, 255, 30);
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1.5f);
    private static final int ARC_RADIUS = 8;

    private final JFrame overlay = new JFrame();
    private final ITesseract tesseract;
    private Point startDrag;
    private Rectangle selectionRect;

    public OCRScissors() {
        tesseract = new Tesseract(); // Инициализируем сначала объект
        configureTesseract(); // Затем настраиваем
        createOverlay();
    }

    private void configureTesseract() {
        try {
            // Проверка существования ресурсов
            URL libUrl = getClass().getResource("/win32-x86-64");
            if (libUrl == null) {
                throw new RuntimeException("Native libraries not found in resources");
            }

            URL tessDataUrl = getClass().getResource("/tessdata");
            if (tessDataUrl == null) {
                throw new RuntimeException("Tessdata not found in resources");
            }

            // Настройка путей
            System.setProperty("jna.library.path",
                    Paths.get(libUrl.toURI()).toString());

            tesseract.setDatapath(Paths.get(tessDataUrl.toURI()).toString());
            tesseract.setLanguage("rus+eng");

        } catch (Exception e) {
            throw new RuntimeException("Tesseract init error", e);
        }
    }

    private void createOverlay() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        configureOverlayFrame();
        overlay.add(createOverlayPanel());
        overlay.setVisible(true);
    }

    private void configureOverlayFrame() {
        overlay.setUndecorated(true);
        overlay.setBackground(new Color(0, 0, 0, 50));
        overlay.setExtendedState(JFrame.MAXIMIZED_BOTH);
        overlay.setAlwaysOnTop(true);
    }

    private JPanel createOverlayPanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (selectionRect != null) {
                    drawSelectionArea((Graphics2D) g);
                }
            }
        };

        panel.setOpaque(false);
        addMouseHandlers(panel);
        return panel;
    }

    private void drawSelectionArea(Graphics2D g2d) {
        // Фон выделения
        g2d.setColor(FILL_COLOR);
        g2d.fillRect(selectionRect.x, selectionRect.y,
                selectionRect.width, selectionRect.height);

        // Основная рамка
        g2d.setColor(BORDER_COLOR);
        g2d.setStroke(BORDER_STROKE);
        g2d.drawRoundRect(selectionRect.x, selectionRect.y,
                selectionRect.width, selectionRect.height,
                ARC_RADIUS, ARC_RADIUS);

        // Эффект тени
        g2d.setColor(SHADOW_COLOR);
        g2d.drawRoundRect(selectionRect.x + 1, selectionRect.y + 1,
                selectionRect.width, selectionRect.height,
                ARC_RADIUS, ARC_RADIUS);
    }

    private void addMouseHandlers(JPanel panel) {
        panel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                startDrag = e.getPoint();
                selectionRect = new Rectangle();
            }

            public void mouseReleased(MouseEvent e) {
                overlay.dispose();
                processSelection();
            }
        });

        panel.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                updateSelectionRect(e.getPoint());
                panel.repaint();
            }
        });
    }

    private void updateSelectionRect(Point endPoint) {
        int x = Math.min(startDrag.x, endPoint.x);
        int y = Math.min(startDrag.y, endPoint.y);
        selectionRect.setRect(x, y,
                Math.abs(endPoint.x - startDrag.x),
                Math.abs(endPoint.y - startDrag.y));
    }

    private void processSelection() {
        new Thread(() -> {
            try {
                BufferedImage capture = new Robot().createScreenCapture(selectionRect);
                String result = tesseract.doOCR(preprocessImage(capture));
                copyToClipboard(cleanText(result));
            } catch (Exception e) {
                System.out.println(e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    overlay.dispose();
                    System.exit(0);
                });
            }
        }).start();
    }

    private BufferedImage preprocessImage(BufferedImage original) {
        BufferedImage scaled = new BufferedImage(
                original.getWidth() * 2,
                original.getHeight() * 2,
                BufferedImage.TYPE_INT_RGB);

        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(original, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private String cleanText(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}\\p{Punct}\\s]", "").trim();
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(OCRScissors::new);
    }
}
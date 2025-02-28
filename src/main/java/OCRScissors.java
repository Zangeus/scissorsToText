import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;
import net.sourceforge.tess4j.*;

public class OCRScissors {
    private JFrame overlay;
    private Point startDrag;
    private Rectangle selectionRect;

    static {
        // Указываем путь к нативным библиотекам из ресурсов
        System.setProperty("jna.library.path",
                new File(OCRScissors.class.getResource("/win32-x86-64").getPath()).getAbsolutePath());
    }

    public OCRScissors() {
        createOverlay();
    }

    private void createOverlay() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        overlay = new JFrame();
        overlay.setUndecorated(true);
        overlay.setBackground(new Color(0, 0, 0, 50));
        overlay.setExtendedState(JFrame.MAXIMIZED_BOTH);
        overlay.setAlwaysOnTop(true);

        JPanel panel = getJPanel();

        panel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                startDrag = e.getPoint();
                selectionRect = new Rectangle();
            }

            public void mouseReleased(MouseEvent e) {
                overlay.setVisible(false);
                processSelection();
            }
        });

        panel.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                int x = Math.min(startDrag.x, e.getX());
                int y = Math.min(startDrag.y, e.getY());
                int width = Math.abs(e.getX() - startDrag.x);
                int height = Math.abs(e.getY() - startDrag.y);

                selectionRect.setBounds(x, y, width, height);
                panel.repaint();
            }
        });

        overlay.add(panel);
        overlay.setVisible(true);
    }

    private JPanel getJPanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;

                // Прозрачный фон панели
                g2d.setColor(new Color(0, 0, 0, 0));
                g2d.fillRect(0, 0, getWidth(), getHeight());

                if (selectionRect != null) {
                    // 1. Тонкая подсветка области
                    g2d.setColor(new Color(30, 30, 30, 50)); // AliceBlue с прозрачностью
                    g2d.fillRect(selectionRect.x, selectionRect.y,
                            selectionRect.width, selectionRect.height);

                    // 2. Минималистичная рамка
                    g2d.setColor(new Color(173, 216, 230)); // CornflowerBlue
                    g2d.setStroke(new BasicStroke(1.5f));

                    // Рамка с закругленными углами
                    int arc = 8; // Радиус скругления
                    g2d.drawRoundRect(
                            selectionRect.x,
                            selectionRect.y,
                            selectionRect.width,
                            selectionRect.height,
                            arc,
                            arc
                    );

                    // 3. Тонкая тень для глубины
                    g2d.setColor(new Color(255, 255, 255, 30));
                    g2d.drawRoundRect(
                            selectionRect.x + 1,
                            selectionRect.y + 1,
                            selectionRect.width,
                            selectionRect.height,
                            arc,
                            arc
                    );
                }
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    private void processSelection() {
        try {
            // 1. Получаем скриншот
            Robot robot = new Robot();
            BufferedImage capture = robot.createScreenCapture(selectionRect);

            // 2. Сохраняем для отладки
            ImageIO.write(capture, "PNG", new File("debug_capture.png"));

            // 3. Настройка Tesseract
            ITesseract tess = new Tesseract();

            // Правильный путь к tessdata
            String tessDataPath = Paths.get(
                    OCRScissors.class.getResource("/tessdata").toURI()
            ).toAbsolutePath().toString();

            tess.setDatapath(tessDataPath);
            tess.setLanguage("rus+eng");

            // 4. Предобработка изображения
            BufferedImage processed = enhanceImage(capture);

            // 5. Распознавание
            String result = tess.doOCR(processed);

            // 6. Очистка и копирование
            result = result.replaceAll("[^\\p{L}\\p{N}\\p{Punct}\\s]", "");
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(result.trim()), null);

            JOptionPane.showMessageDialog(null, "Успешно распознано:\n" + result);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Критическая ошибка:\n" + ex.getMessage());
            ex.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

    private BufferedImage enhanceImage(BufferedImage original) {
        // Увеличение разрешения и улучшение контраста
        BufferedImage enhanced = new BufferedImage(
                original.getWidth() * 2,
                original.getHeight() * 2,
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g = enhanced.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(original, 0, 0, enhanced.getWidth(), enhanced.getHeight(), null);
        g.dispose();

        return enhanced;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(OCRScissors::new);
    }
}
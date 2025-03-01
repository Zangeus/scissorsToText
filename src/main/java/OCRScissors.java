import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class OCRScissors {
    private static final Color FILL_COLOR = new Color(30, 30, 30, 50);
    private static final Color BORDER_COLOR = new Color(255, 20, 147);
    private static final Color SHADOW_COLOR = new Color(255, 255, 255, 30);
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1.5f);
    private static final int ARC_RADIUS = 8;

    private final JFrame overlay = new JFrame();
    private Point startDrag;
    private Rectangle selectionRect;

    public OCRScissors() {
        checkTesseractInstallation();
        createOverlay();
    }

    private void checkTesseractInstallation() {
        try {
            Process process = new ProcessBuilder("tesseract", "--version")
                    .redirectErrorStream(true)
                    .start();

            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!version.toLowerCase().contains("tesseract")) {
                throw new RuntimeException("Требуется Tesseract. Найдено: " + version);
            }
        } catch (Exception e) {
            showErrorAndExit(e.getMessage());
        }
    }

    private void createOverlay() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        overlay.setUndecorated(true);
        overlay.setBackground(new Color(0, 0, 0, 50));
        overlay.setExtendedState(JFrame.MAXIMIZED_BOTH);
        overlay.setAlwaysOnTop(true);

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
        overlay.add(panel);
        overlay.setVisible(true);
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
        if (selectionRect == null || selectionRect.width == 0 || selectionRect.height == 0) {
            showErrorDialog("Error", new RuntimeException("Invalid selection area"));
            return;
        }
        new Thread(() -> {
            try {
                BufferedImage capture = new Robot().createScreenCapture(selectionRect);
                String result = runTesseract(capture);
                copyToClipboard(cleanText(result));
            } catch (Exception e) {
                showErrorDialog("OCR Error", e);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    overlay.dispose();
                    System.exit(0);
                });
            }
        }).start();
    }

    private void showErrorDialog(String title, Exception e) {
        String message = e.getMessage() + "\n" +
                "StackTrace: " + getStackTrace(e);

        JOptionPane.showMessageDialog(null,
                message,
                title,
                JOptionPane.ERROR_MESSAGE);
    }

    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private String runTesseract(BufferedImage image) throws Exception {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("ocr_temp_", ".png");
            saveImageWithDPI(image, tempFile);

            List<String> command = new ArrayList<>();
            command.add("tesseract");
            command.add(tempFile.getAbsolutePath()); // Убрать кавычки
            command.add("stdout");
            command.add("-l");
            command.add("rus+eng");
            command.add("--dpi");
            command.add("300");
            command.add("-c");
            command.add("debug_file=/dev/null"); // Для Linux/Mac

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            List<String> lines = getStrings(process);

            // Проверяем статус выполнения
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Tesseract error code: " + exitCode +
                        "\nOutput: " + String.join("\n", lines));
            }

            if (lines.isEmpty()) {
                throw new RuntimeException("No text recognized");
            }

            return String.join("\n", lines).trim();

        } finally {
            // Гарантированное удаление временного файла
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                } catch (IOException e) {
                    System.err.println("Failed to delete temp file: " + e.getMessage());
                }
            }
        }
    }

    private static List<String> getStrings(Process process) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())))
        {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("Estimating") &&
                        !line.isEmpty() &&
                        !line.startsWith("Tesseract")) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private void saveImageWithDPI(BufferedImage image, File file) throws IOException {
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    new ImageTypeSpecifier(image),
                    param
            );

            setDPI(metadata);

            writer.write(metadata, new IIOImage(image, null, metadata), param);
            writer.dispose();
        }
    }

    private void setDPI(IIOMetadata metadata) throws IIOInvalidTreeException {
        String metadataFormat = "javax_imageio_1.0";
        IIOMetadataNode root = new IIOMetadataNode(metadataFormat);
        IIOMetadataNode horiz = new IIOMetadataNode("HorizontalPixelSize");
        horiz.setAttribute("value", Double.toString(25.4 / 300));

        IIOMetadataNode vert = new IIOMetadataNode("VerticalPixelSize");
        vert.setAttribute("value", Double.toString(25.4 / 300));

        IIOMetadataNode dim = new IIOMetadataNode("Dimension");
        dim.appendChild(horiz);
        dim.appendChild(vert);

        root.appendChild(dim);
        metadata.mergeTree(metadataFormat, root);
    }

    private void showErrorAndExit(String message) {
        JOptionPane.showMessageDialog(null,
                message,
                "Ошибка Tesseract",
                JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    private String cleanText(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}\\p{Punct}\\s]", "").trim();
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new OCRScissors();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null,
                        "Fatal error: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
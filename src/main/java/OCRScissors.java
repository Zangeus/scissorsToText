import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;

public class OCRScissors {
    private static final Color FILL_COLOR = new Color(30, 30, 30, 50);
    private static final Color BORDER_COLOR = new Color(255, 20, 147);
    private static final Color SHADOW_COLOR = new Color(255, 255, 255, 30);
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1.5f);
    private static final int ARC_RADIUS = 8;
    private static final int DPI = 300;
    private static final double INCH_TO_MM = 25.4;

    private final JFrame overlay = new JFrame();
    private Point startDrag;
    private Rectangle selectionRect;

    public OCRScissors() {
        checkTesseractInstallation();
        initUI();
    }

    private void checkTesseractInstallation() {
        try {
            Process process = new ProcessBuilder("tesseract", "--version").start();
            if (process.waitFor() != 0) {
                throw new RuntimeException("Tesseract не установлен или произошла ошибка");
            }
        } catch (IOException | InterruptedException e) {
            showErrorAndExit("Ошибка при проверке Tesseract: " + e.getMessage());
        }
    }

    private void initUI() {
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
        setupMouseHandlers(panel);
        overlay.add(panel);
        overlay.setVisible(true);
    }

    private void drawSelectionArea(Graphics2D g2d) {
        g2d.setColor(FILL_COLOR);
        g2d.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);

        g2d.setColor(BORDER_COLOR);
        g2d.setStroke(BORDER_STROKE);
        g2d.drawRoundRect(selectionRect.x, selectionRect.y,
                selectionRect.width, selectionRect.height, ARC_RADIUS, ARC_RADIUS);

        g2d.setColor(SHADOW_COLOR);
        g2d.drawRoundRect(selectionRect.x + 1, selectionRect.y + 1,
                selectionRect.width, selectionRect.height, ARC_RADIUS, ARC_RADIUS);
    }

    private void setupMouseHandlers(JPanel panel) {
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startDrag = e.getPoint();
                selectionRect = new Rectangle();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                overlay.dispose();
                processSelection();
            }
        });

        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
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
        if (selectionRect.isEmpty()) {
            showError("Ошибка", "Неверная область выделения");
            return;
        }

        new Thread(() -> {
            try {
                BufferedImage capture = new Robot().createScreenCapture(selectionRect);
                String result = runTesseract(capture);
                copyToClipboard(cleanText(result));
            } catch (Exception e) {
                showError("OCR Ошибка", e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    overlay.dispose();
                    System.exit(0);
                });
            }
        }).start();
    }

    private String runTesseract(BufferedImage image) throws Exception {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("ocr_temp_", ".png");
            saveImageWithDPI(image, tempFile);

            Process process = new ProcessBuilder(
                    "tesseract", tempFile.getAbsolutePath(), "stdout",
                    "-l", "rus+eng", "--dpi", String.valueOf(DPI),
                    "-c", "debug_file=/dev/null"
            ).redirectErrorStream(true).start();

            String output = readProcessOutput(process);

            if (process.waitFor() != 0) {
                throw new RuntimeException("Ошибка Tesseract: код " + process.exitValue());
            }

            return output.trim();
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile.toPath());
            }
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            reader.lines()
                    .filter(line -> !line.startsWith("Estimating") &&
                            !line.isEmpty() &&
                            !line.startsWith("Tesseract"))
                    .forEach(line -> output.append(line).append('\n'));
        }
        return output.toString();
    }

    private void saveImageWithDPI(BufferedImage image, File file) throws IOException {
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
            writer.setOutput(ios);

            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    new ImageTypeSpecifier(image),
                    writer.getDefaultWriteParam()
            );
            setDPI(metadata);

            writer.write(new IIOImage(image, null, metadata));
            writer.dispose();
        }
    }

    private void setDPI(IIOMetadata metadata) throws IIOInvalidTreeException {
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_1.0");
        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");

        addPixelSizeNode(dimension, "HorizontalPixelSize");
        addPixelSizeNode(dimension, "VerticalPixelSize");

        root.appendChild(dimension);
        metadata.mergeTree("javax_imageio_1.0", root);
    }

    private void addPixelSizeNode(IIOMetadataNode parent, String nodeName) {
        IIOMetadataNode node = new IIOMetadataNode(nodeName);
        node.setAttribute("value", Double.toString(INCH_TO_MM / DPI));
        parent.appendChild(node);
    }

    private void showErrorAndExit(String message) {
        showError("Фатальная ошибка", message);
        System.exit(1);
    }

    private String cleanText(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}\\p{Punct}\\s]", "").trim();
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(OCRScissors::new);
    }
}
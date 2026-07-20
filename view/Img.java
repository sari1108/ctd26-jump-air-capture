import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

// Lightweight image-utility class using only standard JDK APIs (no external library).
public class Img {

    private BufferedImage img;

    public Img read(String path,
                    Dimension targetSize,
                    boolean keepAspect,
                    Object interpolation /*ignored*/) {

        try {
            img = ImageIO.read(new File(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load image: " + path);
        }
        if (img == null) throw new IllegalArgumentException("Unsupported image: " + path);

        if (targetSize != null) {
            int tw = targetSize.width, th = targetSize.height;
            int w = img.getWidth(), h = img.getHeight();

            int nw, nh;
            if (keepAspect) {
                double s = Math.min(tw / (double) w, th / (double) h);
                nw = (int) Math.round(w * s);
                nh = (int) Math.round(h * s);
            } else { nw = tw; nh = th; }

            BufferedImage dst = new BufferedImage(
                    nw, nh,
                    img.getColorModel().hasAlpha()
                            ? BufferedImage.TYPE_INT_ARGB
                            : BufferedImage.TYPE_INT_RGB);

            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            img = dst;
        }
        return this;
    }

    public Img read(String path) { return read(path, null, false, null); }

    // --- Extensions used by our UI, kept inside this same class so every bit of
    // drawing still goes through Img: a blank canvas to draw shapes on, wrapping an
    // already-in-memory image, filled shapes, and resizing an already-loaded image. ---

    public static Img blank(int width, int height) {
        Img i = new Img();
        i.img = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        return i;
    }

    public static Img of(BufferedImage existing) {
        Img i = new Img();
        i.img = existing;
        return i;
    }

    public void fillRect(int x, int y, int w, int h, Color color) {
        if (img == null) throw new IllegalStateException("Image not loaded.");
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    public void fillOval(int x, int y, int w, int h, Color color) {
        if (img == null) throw new IllegalStateException("Image not loaded.");
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(x, y, w, h);
        g.dispose();
    }

    public Img scaledCopy(int newWidth, int newHeight) {
        if (img == null) throw new IllegalStateException("Image not loaded.");
        BufferedImage dst = new BufferedImage(Math.max(1, newWidth), Math.max(1, newHeight),
                img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return of(dst);
    }

    public int getWidth() { return img.getWidth(); }
    public int getHeight() { return img.getHeight(); }

    public void drawOn(Img other, int x, int y) {
        if (img == null || other.img == null)
            throw new IllegalStateException("Both images must be loaded.");

        if (x + img.getWidth()  > other.img.getWidth()
         || y + img.getHeight() > other.img.getHeight())
            throw new IllegalArgumentException("Patch exceeds destination bounds.");

        Graphics2D g = other.img.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(img, x, y, null);
        g.dispose();
    }

    public void putText(String txt, int x, int y, float fontSize,
                        Color color, int thickness /* > 0 draws bold */) {

        if (img == null) throw new IllegalStateException("Image not loaded.");

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        // A fixed, known font instead of whatever a throwaway Graphics context's
        // default happens to be - that was giving wildly inconsistent sizes for the
        // same fontSize depending on when it was called.
        int style = thickness > 0 ? Font.BOLD : Font.PLAIN;
        g.setFont(new Font("SansSerif", style, Math.max(8, Math.round(fontSize * 16))));
        g.drawString(txt, x, y);
        g.dispose();
    }

    public void show() {
        if (img == null) throw new IllegalStateException("Image not loaded.");

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Image");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(new JLabel(new ImageIcon(img)));
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }

    public BufferedImage get() { return img; }
}

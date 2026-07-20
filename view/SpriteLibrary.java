import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

// Loads and caches the animation frames + timing (frames_per_sec, is_loop) for
// every piece type/color/visual-state from a sprites folder tree like
// assets/pieces2/<TypeCode><ColorLetter>/states/<state>/{config.json,sprites/1..N.png}.
public class SpriteLibrary {
    private static final class Animation {
        final BufferedImage[] frames;
        final int fps;
        final boolean loop;

        Animation(BufferedImage[] frames, int fps, boolean loop) {
            this.frames = frames;
            this.fps = fps;
            this.loop = loop;
        }
    }

    private final Map<String, Animation> animations = new HashMap<>();

    public SpriteLibrary(String basePath) {
        for (PieceType type : PieceType.values()) {
            if (type == PieceType.EMPTY) continue;
            for (PieceColor color : new PieceColor[]{PieceColor.WHITE, PieceColor.BLACK}) {
                for (PieceVisualState state : PieceVisualState.values()) {
                    load(basePath, type, color, state);
                }
            }
        }
    }

    public BufferedImage frameFor(PieceType type, PieceColor color, PieceVisualState state, long elapsedMillis) {
        Animation anim = animations.get(key(type, color, state));
        if (anim == null || anim.frames.length == 0) return null;
        int index = (int) (elapsedMillis * anim.fps / 1000);
        index = anim.loop ? index % anim.frames.length : Math.min(index, anim.frames.length - 1);
        return anim.frames[index];
    }

    private void load(String basePath, PieceType type, PieceColor color, PieceVisualState state) {
        String dir = basePath + "/" + folderName(type, color) + "/states/" + stateName(state);

        int fps = 8;
        boolean loop = true;
        try {
            String json = new String(Files.readAllBytes(Paths.get(dir + "/config.json")));
            fps = extractInt(json, "frames_per_sec", fps);
            loop = extractBool(json, "is_loop", loop);
        } catch (IOException ignored) {
            // no config for this state - fall back to defaults above
        }

        boolean dark = color == PieceColor.BLACK;

        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 1; ; i++) {
            File file = new File(dir + "/sprites/" + i + ".png");
            if (!file.exists()) break;
            try {
                frames.add(toBlackAndWhite(ImageIO.read(file), dark));
            } catch (IOException e) {
                break;
            }
        }

        animations.put(key(type, color, state), new Animation(frames.toArray(new BufferedImage[0]), fps, loop));
    }

    // Desaturates a sprite to grayscale (keeping its own shading/linework as relative
    // brightness), then squeezes it into a narrow dark range (black pieces) or a
    // narrow light range (white pieces) - guarantees real black/white regardless of
    // how bright/dark the source artwork's own palette happens to be.
    private static BufferedImage toBlackAndWhite(BufferedImage src, boolean dark) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = argb >>> 24;
                if (a == 0) continue;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                int rangeLow = dark ? 12 : 205;
                int rangeSpan = 55;
                int newLum = rangeLow + (int) Math.round((lum / 255.0) * rangeSpan);
                int gray = (newLum << 16) | (newLum << 8) | newLum;
                out.setRGB(x, y, (a << 24) | gray);
            }
        }
        return out;
    }

    private String key(PieceType type, PieceColor color, PieceVisualState state) {
        return folderName(type, color) + "/" + stateName(state);
    }

    private String folderName(PieceType type, PieceColor color) {
        return "" + type.getCode() + (color == PieceColor.WHITE ? "W" : "B");
    }

    private String stateName(PieceVisualState state) {
        switch (state) {
            case IDLE: return "idle";
            case MOVE: return "move";
            case JUMP: return "jump";
            case SHORT_REST: return "short_rest";
            case LONG_REST: return "long_rest";
            default: throw new IllegalArgumentException("Unknown state: " + state);
        }
    }

    private static int extractInt(String json, String key, int defaultValue) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    private static boolean extractBool(String json, String key, boolean defaultValue) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultValue;
    }
}

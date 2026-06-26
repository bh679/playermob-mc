package games.brennan.playermob.skin;

/**
 * Heuristic detection of a skin's arm model (slim/Alex vs wide/Steve) from its pixels — used for
 * local-folder skins, where there is no Mojang profile metadata to read the model from.
 *
 * <p>The test mirrors the well-established approach (e.g. bs-community/skinview-utils
 * {@code inferModelType}): a slim 64×64 skin leaves four small "unused" areas of the arm regions
 * blank, because the wide model's 4-pixel arms occupy them but the slim model's 3-pixel arms do not.
 * The inner (first) skin layer is not allowed to contain transparent pixels, so a transparent pixel
 * anywhere in those areas means the skin must be slim. A skin that fills those areas with a single
 * flat colour (all-black or all-white — a common "filled" export) is also treated as slim. Anything
 * else is wide. Legacy 64×32 skins (and any non-square / non-64-multiple image) are always wide.</p>
 *
 * <p>Pure (no Minecraft, no AWT) — operates over an abstract {@code argb(x, y)} accessor returning
 * {@code 0xAARRGGBB}, so it is unit-tested with synthetic pixel arrays and reused by the client
 * texture loader over either {@code BufferedImage} or {@code NativeImage}.</p>
 */
public final class SkinModelDetector {

    /** Reads the pixel at {@code (x, y)} as a packed {@code 0xAARRGGBB} int. */
    @FunctionalInterface
    public interface Pixels {
        int argb(int x, int y);
    }

    /** The four arm areas (x, y, w, h) at 64×64 scale that a slim skin leaves unused. */
    private static final int[][] SLIM_UNUSED = {
        {50, 16, 2, 4},
        {54, 20, 2, 12},
        {42, 48, 2, 4},
        {46, 52, 2, 12},
    };

    private SkinModelDetector() {}

    /**
     * @return true if {@code (width × height)} pixels read through {@code px} describe a slim-arm skin.
     *     Only square images whose width is a positive multiple of 64 can be slim; everything else
     *     (legacy 64×32, odd sizes) is wide.
     */
    public static boolean isSlim(int width, int height, Pixels px) {
        if (width <= 0 || width != height || width % 64 != 0) {
            return false;
        }
        int scale = width / 64;
        boolean anyTransparent = false;
        boolean allBlack = true;
        boolean allWhite = true;
        for (int[] area : SLIM_UNUSED) {
            int x0 = area[0] * scale;
            int y0 = area[1] * scale;
            int w = area[2] * scale;
            int h = area[3] * scale;
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    int argb = px.argb(x, y);
                    int alpha = (argb >>> 24) & 0xFF;
                    int rgb = argb & 0xFFFFFF;
                    if (alpha == 0) {
                        anyTransparent = true;
                    }
                    if (rgb != 0x000000) {
                        allBlack = false;
                    }
                    if (rgb != 0xFFFFFF) {
                        allWhite = false;
                    }
                }
            }
        }
        return anyTransparent || allBlack || allWhite;
    }
}

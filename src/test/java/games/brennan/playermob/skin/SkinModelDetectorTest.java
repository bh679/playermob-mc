package games.brennan.playermob.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure oracle for {@link SkinModelDetector} — the slim/wide arm-model heuristic over synthetic pixels. */
class SkinModelDetectorTest {

    /** A 64×64 ARGB canvas pre-filled with an opaque mid-grey (so the default reads as wide). */
    private static final class Canvas {
        final int size = 64;
        final int[] px = new int[size * size];

        Canvas() {
            java.util.Arrays.fill(px, 0xFF808080);
        }

        void fill(int x0, int y0, int w, int h, int argb) {
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    px[y * size + x] = argb;
                }
            }
        }

        boolean isSlim() {
            return SkinModelDetector.isSlim(size, size, (x, y) -> px[y * size + x]);
        }
    }

    @Test
    void opaqueNonUniformArmAreasReadWide() {
        // The four "unused" areas stay opaque grey → not transparent, not all-black, not all-white.
        assertFalse(new Canvas().isSlim());
    }

    @Test
    void aTransparentPixelInAnUnusedAreaReadsSlim() {
        Canvas c = new Canvas();
        c.fill(50, 16, 1, 1, 0x00000000);   // single transparent pixel in the first unused area
        assertTrue(c.isSlim());
    }

    @Test
    void allUnusedAreasBlackReadsSlim() {
        Canvas c = new Canvas();
        c.fill(50, 16, 2, 4, 0xFF000000);
        c.fill(54, 20, 2, 12, 0xFF000000);
        c.fill(42, 48, 2, 4, 0xFF000000);
        c.fill(46, 52, 2, 12, 0xFF000000);
        assertTrue(c.isSlim());
    }

    @Test
    void allUnusedAreasWhiteReadsSlim() {
        Canvas c = new Canvas();
        c.fill(50, 16, 2, 4, 0xFFFFFFFF);
        c.fill(54, 20, 2, 12, 0xFFFFFFFF);
        c.fill(42, 48, 2, 4, 0xFFFFFFFF);
        c.fill(46, 52, 2, 12, 0xFFFFFFFF);
        assertTrue(c.isSlim());
    }

    @Test
    void partiallyBlackAreasDoNotReadSlim() {
        Canvas c = new Canvas();
        // Only three of the four areas black → not "all black", and no transparency → wide.
        c.fill(50, 16, 2, 4, 0xFF000000);
        c.fill(54, 20, 2, 12, 0xFF000000);
        c.fill(42, 48, 2, 4, 0xFF000000);
        assertFalse(c.isSlim());
    }

    @Test
    void legacyAndOddSizesAreAlwaysWide() {
        // 64×32 legacy skins cannot be slim.
        assertFalse(SkinModelDetector.isSlim(64, 32, (x, y) -> 0x00000000));
        // Non-64-multiple / non-square → wide.
        assertFalse(SkinModelDetector.isSlim(50, 50, (x, y) -> 0x00000000));
        assertFalse(SkinModelDetector.isSlim(0, 0, (x, y) -> 0));
    }

    @Test
    void hdSkinsScaleTheAreas() {
        // A 128×128 (scale 2) skin with a transparent pixel in a scaled unused area reads slim.
        int size = 128;
        int[] px = new int[size * size];
        java.util.Arrays.fill(px, 0xFF808080);
        px[(16 * 2) * size + (50 * 2)] = 0x00000000;   // scaled (50,16)
        assertTrue(SkinModelDetector.isSlim(size, size, (x, y) -> px[y * size + x]));
    }
}

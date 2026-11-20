package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * One life's evidence of the player <em>writing</em> — saving text in a book &amp; quill, and signing
 * books — distilled into the echo's {@code Writing} trait (0–10): how likely it is to pick up a
 * book &amp; quill it passes and to stop and write in it.
 *
 * <p><b>Scored as a rate with a prior at 0</b>, like {@link ReadingTally}:
 * {@code 10·w / (w + BLANK_WEIGHT·blankLeft + PRIOR)}, where {@code w = written + SIGN_WEIGHT·signed}.
 * Writing pushes it up; a signed book counts a little extra. A blank book &amp; quill left behind
 * (dropped, burned — not put away in a container) pulls it down, but only very slightly
 * ({@link #BLANK_WEIGHT}): even prolific authors leave far more blank books than they fill.</p>
 *
 * <p>Immutable and pure (no world) so the scoring unit-tests — see {@code WritingTallyTest}.</p>
 */
public final class WritingTally {

    /** Weight of the 0 prior: one edit alone scores ~3. */
    static final double PRIOR = 2.0;
    /** Extra weight a signature adds on top of the writing it finished. */
    static final double SIGN_WEIGHT = 0.5;
    /** How much one blank book &amp; quill left behind counts against — a tenth of one edit. */
    static final double BLANK_WEIGHT = 0.1;

    /** The tally's compound inside a saved {@link PlayerLifeRecord}. */
    public static final String TAG = "Writing";
    /** The 0–10 trait in an echo's snapshot and a PlayerMob's save data (top-level int). */
    public static final String TRAIT_TAG = "Writing";
    static final String TAG_WRITTEN = "Written";
    static final String TAG_SIGNED = "Signed";
    static final String TAG_BLANK_LEFT = "BlankLeft";

    public static final WritingTally EMPTY = new WritingTally(0, 0, 0);

    /** Edits that saved new or changed text into a book &amp; quill. */
    private final int written;
    /** Books signed. */
    private final int signed;
    /** Blank books &amp; quills that left the inventory (not into a container). */
    private final int blankLeft;

    private WritingTally(int written, int signed, int blankLeft) {
        this.written = Math.max(0, written);
        this.signed = Math.max(0, signed);
        this.blankLeft = Math.max(0, blankLeft);
    }

    public int written() { return written; }
    public int signed() { return signed; }
    public int blankLeft() { return blankLeft; }

    public boolean isEmpty() {
        return written == 0 && signed == 0 && blankLeft == 0;
    }

    // ---- Credits ----------------------------------------------------------

    /** The player saved new or changed text in a book &amp; quill. */
    public WritingTally withWritten() {
        return new WritingTally(written + 1, signed, blankLeft);
    }

    /** The player signed a book (the text it was signed with counts as writing too). */
    public WritingTally withSigned() {
        return new WritingTally(written + 1, signed + 1, blankLeft);
    }

    /** {@code count} blank books &amp; quills left the player's inventory. */
    public WritingTally withBlankLeft(int count) {
        return count <= 0 ? this : new WritingTally(written, signed, blankLeft + count);
    }

    // ---- Scoring ----------------------------------------------------------

    double raw() {
        double w = written + SIGN_WEIGHT * signed;
        return 10.0 * w / (w + BLANK_WEIGHT * blankLeft + PRIOR);
    }

    /** The echo's Writing trait, 0–10. A life that never wrote reads 0. */
    public int score() {
        long rounded = Math.round(raw());
        return (int) Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, rounded));
    }

    /** Operator-facing breakdown for the debug readout. */
    public String breakdown() {
        return String.format(Locale.ROOT, "written %d, signed %d, blank left %d → %d (%.1f)",
            written, signed, blankLeft, score(), raw());
    }

    // ---- NBT --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_WRITTEN, written);
        tag.putInt(TAG_SIGNED, signed);
        tag.putInt(TAG_BLANK_LEFT, blankLeft);
        return tag;
    }

    /** Read back; missing keys are 0, so an absent tag loads as {@link #EMPTY}. */
    public static WritingTally load(CompoundTag tag) {
        return new WritingTally(NbtCompat.getIntOr(tag, TAG_WRITTEN, 0),
            NbtCompat.getIntOr(tag, TAG_SIGNED, 0), NbtCompat.getIntOr(tag, TAG_BLANK_LEFT, 0));
    }
}

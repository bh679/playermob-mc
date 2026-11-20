package games.brennan.playermob.player;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One player's live writing bookkeeping: turns book edits, signatures and the once-a-second count
 * of blank books &amp; quills they carry into {@link WritingTally} updates. Nothing is persisted.
 *
 * <p>Pure — every input is a primitive or a list of strings — so the rules unit-test without a
 * world; see {@code WritingTrackerTest}.</p>
 */
public final class WritingTracker {

    private final Consumer<UnaryOperator<WritingTally>> sink;

    /** Blank quills carried at the last sample; -1 before the first. */
    private int blankCarried = -1;
    /** Blank quills that stopped being blank since the last sample because the player wrote in them. */
    private int filledSinceSample;

    public WritingTracker(Consumer<UnaryOperator<WritingTally>> sink) {
        this.sink = sink;
    }

    /**
     * The player saved a book &amp; quill: {@code before} is its text until now, {@code after} what
     * they saved. Counts as writing only when there is text and it changed.
     */
    public void onBookSaved(List<String> before, List<String> after) {
        if (!DraftBooks.hasWriting(after) || before.equals(after)) {
            return;
        }
        if (!DraftBooks.hasWriting(before)) {
            filledSinceSample++; // a blank quill just became a draft — it didn't leave
        }
        sink.accept(WritingTally::withWritten);
    }

    /** The player signed a book they were writing ({@code wasBlank} if it had no text before). */
    public void onSigned(boolean wasBlank) {
        if (wasBlank) {
            filledSinceSample++;
        }
        sink.accept(WritingTally::withSigned);
    }

    /**
     * One second's count of blank books &amp; quills carried. Fewer than last time — and not because
     * they were written in, or put away into an open container — means some were left behind.
     */
    public void sampleBlank(int carried, boolean containerOpen) {
        if (blankCarried >= 0 && !containerOpen) {
            int left = blankCarried - carried - filledSinceSample;
            if (left > 0) {
                sink.accept(t -> t.withBlankLeft(left));
            }
        }
        blankCarried = carried;
        filledSinceSample = 0;
    }
}

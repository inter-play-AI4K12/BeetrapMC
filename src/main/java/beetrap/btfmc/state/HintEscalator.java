package beetrap.btfmc.state;

/**
 * Escalates help over time so Bip stops spoon-feeding. Call {@link #tick()} once per game tick;
 * it returns the tier index (0-based) that should fire this tick, or -1 for none. Each tier fires
 * at most once. Call {@link #reset()} whenever the player makes progress, so an engaged player who
 * acts quickly never sees the later (more explicit) tiers.
 *
 * Tiers are meant to read as: 0 = gentle nudge, 1 = clearer hint, 2 = explicit answer.
 */
public final class HintEscalator {

    private final long[] thresholds; // idle ticks required before each tier fires
    private long idleTicks;
    private int firedTier;

    /**
     * @param thresholds ascending idle-tick thresholds, one per tier (20 ticks = 1 second).
     */
    public HintEscalator(long... thresholds) {
        this.thresholds = thresholds.clone();
    }

    public void reset() {
        this.idleTicks = 0;
        this.firedTier = 0;
    }

    /** Advance one tick; returns the tier to fire now, or -1 if nothing should fire. */
    public int tick() {
        this.idleTicks++;
        if(this.firedTier < this.thresholds.length
                && this.idleTicks >= this.thresholds[this.firedTier]) {
            return this.firedTier++;
        }
        return -1;
    }
}

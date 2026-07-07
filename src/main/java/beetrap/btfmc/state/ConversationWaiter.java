package beetrap.btfmc.state;

/**
 * Tracks whether a chat-triggered LLM reply has genuinely finished, so a phase machine can safely
 * move on. Chat replies and scripted beats share one serialized call queue to the agent service
 * (see RemotePhysicalAgent), so a fixed timer can't reliably predict real latency — and a reply
 * might itself contain a follow-up question, so "Bip went quiet" alone doesn't mean the exchange is
 * over. Prefers the model's own "done" signal (see Agent#consumeConversationDone); if that never
 * arrives, falls back to a patient grace window after Bip goes quiet, then a hard safety timeout.
 */
final class ConversationWaiter {

    private final int settleTicks;      // Bip must be quiet this long after replying before it "counts"
    private final int graceTicks;       // after settling without "done", wait this long for more chat
    private final int hardTimeoutTicks; // absolute cap regardless of any signal

    private boolean waiting;
    private boolean sawBusy;
    private long quietSinceTick = -1;
    private long startTick = -1;
    private boolean inGrace;
    private long graceStartTick = -1;

    ConversationWaiter(int settleTicks, int graceTicks, int hardTimeoutTicks) {
        this.settleTicks = settleTicks;
        this.graceTicks = graceTicks;
        this.hardTimeoutTicks = hardTimeoutTicks;
    }

    /** Call once when the player sends a chat message expected to trigger a reply. */
    void beginWaiting(long now) {
        this.waiting = true;
        this.sawBusy = false;
        this.quietSinceTick = -1;
        this.inGrace = false;
        this.graceStartTick = -1;
        this.startTick = now;
    }

    /** Call if the player sends ANOTHER chat message while already waiting (e.g. answering a
     * follow-up Bip just asked) — extends the wait for a new reply cycle instead of letting the
     * grace window from the PREVIOUS reply prematurely fire. */
    void extendWaiting() {
        if(!this.waiting) {
            return;
        }
        this.sawBusy = false;
        this.quietSinceTick = -1;
        this.inGrace = false;
        this.graceStartTick = -1;
    }

    boolean isWaiting() {
        return this.waiting;
    }

    /** True if Bip was ever actually observed replying (busy) during this wait — lets a caller
     * distinguish "a reply genuinely happened" from "the hard timeout fired with nothing heard at
     * all" once {@link #tick} returns true. */
    boolean everSawReply() {
        return this.sawBusy;
    }

    /**
     * Advance the tracker one tick.
     * @return true the moment it's time to proceed (done-signaled, grace elapsed with no further
     *         engagement, or the hard timeout) — true at most once per {@link #beginWaiting}.
     */
    boolean tick(long now, boolean agentBusy, boolean conversationDone) {
        if(!this.waiting) {
            return false;
        }
        if(conversationDone) {
            this.waiting = false;
            return true;
        }
        if(agentBusy) {
            this.sawBusy = true;
            this.quietSinceTick = -1;
            this.inGrace = false;
        } else if(this.sawBusy && this.quietSinceTick < 0) {
            this.quietSinceTick = now;
        }
        boolean settled = this.sawBusy && this.quietSinceTick >= 0
                && now - this.quietSinceTick >= this.settleTicks;
        if(settled && !this.inGrace) {
            this.inGrace = true;
            this.graceStartTick = now;
        }
        if(this.inGrace && now - this.graceStartTick >= this.graceTicks) {
            this.waiting = false;
            return true;
        }
        if(now - this.startTick >= this.hardTimeoutTicks) {
            this.waiting = false;
            return true;
        }
        return false;
    }
}

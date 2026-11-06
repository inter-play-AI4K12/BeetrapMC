package beetrap.btfmc.state;

/**
 * Waits for a line Bip was just told to say to actually finish. Scripted beats travel through the
 * same serialized call queue to the agent service as chat replies, and TTS length varies, so a
 * fixed delay can't predict when the player has heard it. Done once Bip has been seen busy and then
 * stayed quiet for {@code settleTicks}; {@code maxWaitTicks} is a safety net for a beat that never
 * produces speech (e.g. a feature-gated beat that resolves to nothing).
 */
final class BipSpeechSettle {

    private final int settleTicks;
    private final int maxWaitTicks;

    private boolean active;
    private boolean sawBusy;
    private long startTick;
    private long quietSinceTick = -1;

    BipSpeechSettle(int settleTicks, int maxWaitTicks) {
        this.settleTicks = settleTicks;
        this.maxWaitTicks = maxWaitTicks;
    }

    /** Call right after emitting the beat whose speech should be waited for. */
    void begin(long now) {
        this.active = true;
        this.sawBusy = false;
        this.startTick = now;
        this.quietSinceTick = -1;
    }

    boolean isActive() {
        return this.active;
    }

    /** @return true at most once per {@link #begin}: the moment the line has finished. */
    boolean tick(long now, boolean agentBusy) {
        if(!this.active) {
            return false;
        }
        if(agentBusy) {
            this.sawBusy = true;
            this.quietSinceTick = -1;
        } else if(this.sawBusy && this.quietSinceTick < 0) {
            this.quietSinceTick = now;
        }
        boolean settled = this.sawBusy && this.quietSinceTick >= 0
                && now - this.quietSinceTick >= this.settleTicks;
        if(settled || now - this.startTick >= this.maxWaitTicks) {
            this.active = false;
            return true;
        }
        return false;
    }
}

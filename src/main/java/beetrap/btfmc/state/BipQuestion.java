package beetrap.btfmc.state;

import java.util.function.IntConsumer;

/**
 * One "ask, then wait for the player's chat answer" exchange, for activities where Bip asks a
 * thinking question before explaining. The caller emits the question beat, then calls
 * {@link #begin}. This waits for the question to finish being spoken, then listens: if the player
 * stays silent it nudges (up to {@link #MAX_NUDGES} times, via the supplied callback) and finally
 * gives up; if they answer, it lets Bip's chat reply play out (see {@link ConversationWaiter})
 * before reporting the exchange finished, so the follow-up explanation never talks over it.
 */
final class BipQuestion {

    private static final int NUDGE_DELAY = 400;     // ~20s of silence before each nudge / giving up
    private static final int MAX_NUDGES = 2;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_ASKING = 1;      // question still being spoken
    private static final int PHASE_LISTENING = 2;   // waiting for the player to type an answer
    private static final int PHASE_REPLYING = 3;    // answer in; Bip's chat reply playing out
    private static final int PHASE_FINISHED = 4;

    private final IntConsumer nudger;
    private final BipSpeechSettle askSpeech = new BipSpeechSettle(10, 300);
    private final ConversationWaiter replyWaiter = new ConversationWaiter(30, 200, 1200);

    private int phase = PHASE_IDLE;
    private long listenSinceTick;
    private int nudges;
    private boolean playerAnswered;

    /** @param nudger emits nudge tier {@code 0..MAX_NUDGES-1} when the player stays silent. */
    BipQuestion(IntConsumer nudger) {
        this.nudger = nudger;
    }

    /** Call right after emitting the question beat. */
    void begin(long now) {
        this.phase = PHASE_ASKING;
        this.askSpeech.begin(now);
        this.nudges = 0;
        this.playerAnswered = false;
    }

    /** True while the exchange is running (the player's chat counts as an answer to it). */
    boolean isRunning() {
        return this.phase == PHASE_ASKING || this.phase == PHASE_LISTENING
                || this.phase == PHASE_REPLYING;
    }

    boolean playerAnswered() {
        return this.playerAnswered;
    }

    void onPlayerChat(long now) {
        if(this.phase == PHASE_ASKING || this.phase == PHASE_LISTENING) {
            this.playerAnswered = true;
            this.phase = PHASE_REPLYING;
            this.replyWaiter.beginWaiting(now);
        } else if(this.phase == PHASE_REPLYING) {
            // They kept talking (e.g. answering a follow-up in Bip's reply) — wait for that too.
            this.replyWaiter.extendWaiting();
        }
    }

    /** @return true at most once per {@link #begin}: the moment the exchange has finished. */
    boolean tick(long now, boolean agentBusy, boolean conversationDone) {
        switch(this.phase) {
            case PHASE_ASKING -> {
                if(this.askSpeech.tick(now, agentBusy)) {
                    this.phase = PHASE_LISTENING;
                    this.listenSinceTick = now;
                }
            }
            case PHASE_LISTENING -> {
                if(now - this.listenSinceTick >= NUDGE_DELAY) {
                    if(this.nudges < MAX_NUDGES) {
                        this.nudger.accept(this.nudges++);
                        this.listenSinceTick = now;
                    } else {
                        this.phase = PHASE_FINISHED;
                        return true;
                    }
                }
            }
            case PHASE_REPLYING -> {
                if(this.replyWaiter.tick(now, agentBusy, conversationDone)) {
                    this.phase = PHASE_FINISHED;
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }
}

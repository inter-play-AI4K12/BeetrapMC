package beetrap.btfmc.state;

import beetrap.btfmc.BipFeatures;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A queue of Bip steps an activity state runs one after another: say a line and wait for it to be
 * spoken, ask a question and wait for the answer (nudging if the player is quiet), wait for the
 * player to do something (nudging if they're stuck), open the floor for questions, pause, or run a
 * game action. Python owns every word (activities.py); this only owns the order and the waiting.
 *
 * The owning state calls {@link #tick()} every game tick and forwards chat and multiple-choice
 * answers. Steps can be queued at any time, so a state can react to game events by queueing more.
 */
final class BipScript {

    /** What the script needs from the state that runs it. */
    interface Host {
        long ticks();

        boolean agentBusy();

        boolean consumeConversationDone();

        void emitBeat(String beat, Map<String, Object> details);
    }

    final Host host;
    private final Deque<Step> steps = new ArrayDeque<>();
    private Step current;
    private String lastChat = "";

    BipScript(Host host) {
        this.host = host;
    }

    BipScript then(Step... more) {
        Collections.addAll(this.steps, more);
        return this;
    }

    boolean isRunning() {
        return this.current != null || !this.steps.isEmpty();
    }

    /** Advance the running step; returns true while steps are still queued or running. */
    boolean tick() {
        boolean busy = this.host.agentBusy();
        boolean done = this.host.consumeConversationDone();
        while(true) {
            if(this.current == null) {
                this.current = this.steps.pollFirst();
                if(this.current == null) {
                    return false;
                }
                this.current.start(this);
            }
            if(!this.current.tick(this, busy, done)) {
                return true;
            }
            this.current = null;
        }
    }

    void onPlayerChat() {
        this.onPlayerChat("");
    }

    /** Forward a chat message; steps can read it via {@link #lastChat()} (e.g. "nope" ends Q&A). */
    void onPlayerChat(String message) {
        this.lastChat = message == null ? "" : message;
        if(this.current != null) {
            this.current.onPlayerChat(this);
        }
    }

    String lastChat() {
        return this.lastChat;
    }

    void onChoice(String questionId, int option) {
        if(this.current != null) {
            this.current.onChoice(this, questionId, option);
        }
    }

    /** Ground truth for the chat LLM while the current step runs, or null. */
    String chatContext() {
        return this.current == null ? null : this.current.chatContext();
    }

    long now() {
        return this.host.ticks();
    }

    // --- Steps ----------------------------------------------------------------------------------

    abstract static class Step {
        void start(BipScript script) { }

        /** @return true once this step is finished. */
        abstract boolean tick(BipScript script, boolean busy, boolean conversationDone);

        void onPlayerChat(BipScript script) { }

        void onChoice(BipScript script, String questionId, int option) { }

        String chatContext() {
            return null;
        }
    }

    private static final Supplier<Map<String, Object>> NO_DETAILS = Map::of;

    /** Bip says one line; finished once it has been spoken. */
    static Step say(String beat) {
        return say(beat, NO_DETAILS);
    }

    /** Bip says one line whose placeholders are filled from details computed when it starts. */
    static Step say(String beat, Supplier<Map<String, Object>> details) {
        return new Step() {
            private final BipSpeechSettle speech = new BipSpeechSettle(20, 300);

            @Override
            void start(BipScript script) {
                script.host.emitBeat(beat, details.get());
                this.speech.begin(script.now());
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                return this.speech.tick(script.now(), busy);
            }
        };
    }

    /**
     * Bip says {@code askBeat} and waits for a chat answer, nudging with "{nudgePrefix}N" if the
     * player is quiet (see {@link BipQuestion}). Skipped entirely when Q&A is switched off.
     */
    static Step ask(String askBeat, String nudgePrefix, Supplier<Map<String, Object>> details,
            String context) {
        return new Step() {
            private BipQuestion question;

            @Override
            void start(BipScript script) {
                if(!BipFeatures.INTERACTIVE_QA) {
                    return;
                }
                Map<String, Object> d = details.get();
                this.question = new BipQuestion(
                        tier -> script.host.emitBeat(nudgePrefix + tier, d));
                script.host.emitBeat(askBeat, d);
                this.question.begin(script.now());
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                return this.question == null
                        || this.question.tick(script.now(), busy, conversationDone);
            }

            @Override
            void onPlayerChat(BipScript script) {
                if(this.question != null) {
                    this.question.onPlayerChat(script.now());
                }
            }

            @Override
            String chatContext() {
                return context;
            }
        };
    }

    /** "{topic}_ask" with "{topic}_nudge_N" nudges. */
    static Step ask(String topic, Supplier<Map<String, Object>> details, String context) {
        return ask(topic + "_ask", topic + "_nudge_", details, context);
    }

    static Step ask(String topic, String context) {
        return ask(topic, NO_DETAILS, context);
    }

    /**
     * Wait for the player to tell Bip something in chat (e.g. a flower they found), then let Bip's
     * reply play out. While they're quiet, nudge every {@code nudgeEvery} ticks (up to
     * {@code maxNudges}); give up after {@code giveUpAfter} ticks. {@code onChat} runs on their
     * first message (e.g. to remember which flower they were pointing at).
     */
    static Step awaitChat(long nudgeEvery, int maxNudges, IntConsumer nudger, long giveUpAfter,
            Runnable onChat, String context) {
        return new Step() {
            private final ConversationWaiter waiter = new ConversationWaiter(30, 200, 1200);
            private long startTick;
            private long lastNudgeTick;
            private int nudges;
            private boolean heard;

            @Override
            void start(BipScript script) {
                this.startTick = script.now();
                this.lastNudgeTick = this.startTick;
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                long now = script.now();
                if(this.heard) {
                    return this.waiter.tick(now, busy, conversationDone);
                }
                if(this.nudges < maxNudges && now - this.lastNudgeTick >= nudgeEvery) {
                    nudger.accept(this.nudges++);
                    this.lastNudgeTick = now;
                }
                return now - this.startTick >= giveUpAfter;
            }

            @Override
            void onPlayerChat(BipScript script) {
                if(!this.heard) {
                    this.heard = true;
                    onChat.run();
                    this.waiter.beginWaiting(script.now());
                } else {
                    this.waiter.extendWaiting();
                }
            }

            @Override
            String chatContext() {
                return context;
            }
        };
    }

    /**
     * Wait until {@code done} is true. While waiting, nudge every {@code nudgeEvery} ticks (up to
     * {@code maxNudges}, via {@code nudger}); give up after {@code giveUpAfter} ticks (0 = never).
     */
    static Step waitFor(BooleanSupplier done, long nudgeEvery, int maxNudges, IntConsumer nudger,
            long giveUpAfter, String context) {
        return new Step() {
            private long startTick;
            private long lastNudgeTick;
            private int nudges;

            @Override
            void start(BipScript script) {
                this.startTick = script.now();
                this.lastNudgeTick = this.startTick;
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                if(done.getAsBoolean()) {
                    return true;
                }
                long now = script.now();
                if(this.nudges < maxNudges && now - this.lastNudgeTick >= nudgeEvery) {
                    nudger.accept(this.nudges++);
                    this.lastNudgeTick = now;
                }
                return giveUpAfter > 0 && now - this.startTick >= giveUpAfter;
            }

            @Override
            String chatContext() {
                return context;
            }
        };
    }

    private static final long LISTEN_MAX = 2400;   // ~2 min: question time never runs forever

    // "nope", "no thanks", "i'm good", "let's go"... (a message with a "?" is a real question)
    private static final java.util.regex.Pattern NO_MORE_QUESTIONS = java.util.regex.Pattern.compile(
            "^(no+|nope|nah|no questions?|no thanks?|not really|nothing|none|i'?m good|im good|"
                    + "all good|i'?m ready|ready|let'?s go|lets go|let'?s start|start|go)\\b.*");

    // "what's next?", "can we move on", "i'm done"... (asking to move on counts even with a "?")
    private static final java.util.regex.Pattern MOVE_ON = java.util.regex.Pattern.compile(
            "^(what'?s next|what is next|whats next|next|i'?m done|done|can we (go|move on|start|"
                    + "continue)|let'?s (move on|continue))\\b.*");

    static boolean isNoMoreQuestions(String message) {
        String m = message.trim().toLowerCase(java.util.Locale.ROOT);
        return !m.isEmpty() && (MOVE_ON.matcher(m).matches()
                || (!m.contains("?") && NO_MORE_QUESTIONS.matcher(m).matches()));
    }

    /**
     * Open the floor: wait {@code quietTicks} for the player to ask something. Each time they chat,
     * let Bip's reply play out, then wait {@code quietTicks} again for a follow-up. Ends right after
     * Bip's reply if the player says they have no questions, and after {@link #LISTEN_MAX} at most.
     */
    static Step listen(long quietTicks, String context) {
        return new Step() {
            // Short grace: the quiet window below already gives the player time for a follow-up.
            private final ConversationWaiter waiter = new ConversationWaiter(30, 40, 1200);
            private long startTick;
            private long quietSinceTick;
            private boolean wrapUp;

            @Override
            void start(BipScript script) {
                this.startTick = script.now();
                this.quietSinceTick = this.startTick;
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                long now = script.now();
                if(this.waiter.isWaiting()) {
                    if(this.waiter.tick(now, busy, conversationDone)) {
                        if(this.wrapUp) {
                            return true;
                        }
                        this.quietSinceTick = now;
                    }
                    return false;
                }
                return now - this.quietSinceTick >= quietTicks
                        || now - this.startTick >= LISTEN_MAX;
            }

            @Override
            void onPlayerChat(BipScript script) {
                this.wrapUp = isNoMoreQuestions(script.lastChat());
                if(this.waiter.isWaiting()) {
                    this.waiter.extendWaiting();
                } else {
                    this.waiter.beginWaiting(script.now());
                }
            }

            @Override
            String chatContext() {
                return context;
            }
        };
    }

    private static final long HANDOFF_AFTER_CLOCKS = 40;   // brief beat once the clocks land
    private static final long HANDOFF_MAX = 240;           // fallback if no give_clocks arrives

    /**
     * Bip flies over and hands across the time-travel clocks ("clock_handoff"), finished a moment
     * after they land in the player's hand. With the hand-off feature off, the clocks just appear.
     */
    static Step clocks(BeetrapStateManager stateManager) {
        return new Step() {
            private long startTick;
            private long clocksGivenTick = -1;

            @Override
            void start(BipScript script) {
                this.startTick = script.now();
                if(BipFeatures.TIME_TRAVEL_HANDOVER) {
                    script.host.emitBeat("clock_handoff", Map.of());
                } else {
                    stateManager.giveTimeTravelItemsToAllPlayers();
                }
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                long now = script.now();
                if(this.clocksGivenTick < 0 && stateManager.timeTravelItemsGiven()) {
                    this.clocksGivenTick = now;
                }
                if(this.clocksGivenTick >= 0) {
                    return now - this.clocksGivenTick >= HANDOFF_AFTER_CLOCKS;
                }
                if(now - this.startTick >= HANDOFF_MAX) {
                    stateManager.giveTimeTravelItemsToAllPlayers();
                    return true;
                }
                return false;
            }
        };
    }

    /** Do nothing for a moment (a natural breath between lines). */
    static Step pause(long ticks) {
        return new Step() {
            private long startTick;

            @Override
            void start(BipScript script) {
                this.startTick = script.now();
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                return script.now() - this.startTick >= ticks;
            }
        };
    }

    /** Run a game action (show a screen, unlock pollinating, end the activity...) instantly. */
    static Step run(Runnable action) {
        return new Step() {
            @Override
            void start(BipScript script) {
                action.run();
            }

            @Override
            boolean tick(BipScript script, boolean busy, boolean conversationDone) {
                return true;
            }
        };
    }
}

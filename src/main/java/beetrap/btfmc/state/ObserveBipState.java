package beetrap.btfmc.state;

import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.flower.FlowerTraits;
import beetrap.btfmc.flower.FlowerTraits.Trait;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Game 1 — Observe the flowers (Python activity {@code observe}). Just getting to know the garden:
 * Bip shows the player the data panel and what every row means using a real flower, then lets
 * them explore and tell him what they find (suggesting a standout flower if they're quiet for a
 * minute), asks for a totally different flower, and takes questions. No pollinating yet.
 */
public class ObserveBipState extends BeetrapState {

    private static final long POINT_NUDGE_EVERY = 400;     // ~20s
    private static final long POINT_GIVE_UP = 1200;        // ~1 min: explain with Bip's flower
    private static final long SUGGEST_EVERY = 1200;        // ~1 min of quiet before a suggestion
    private static final long FIND_GIVE_UP = 3600;         // ~3 min: move on anyway
    private static final long QUESTIONS_QUIET = 400;       // ~20s of silence closes question time

    private long ticks;
    private final BipScript script = this.bipScript("observe", () -> this.ticks);
    private boolean finished;

    private int targetCount;           // flowers the player has aimed at so far
    private int targetCountAtPoint;
    private Flower shownFlower;        // the flower Bip flies to first
    private Flower panelFlower;        // the flower whose panel Bip explains
    private Flower firstFind;          // what the player told Bip about first
    private Flower lastSuggestion;
    private final Set<Flower> suggested = new HashSet<>();

    public ObserveBipState(BeetrapState parent) {
        super(parent);
        this.stateManager.setBipActivity("observe");
        this.buildScript();
    }

    private void buildScript() {
        this.script.then(BipScript.pause(5));
        if(!Beetrapfabricmc.BIP_INTRODUCED) {
            Beetrapfabricmc.BIP_INTRODUCED = true;
            this.script.then(BipScript.ask("hello", "hello_nudge_", Map::of,
                    "CONTEXT: you just asked the player's name. Greet them by name in one line."));
        }
        this.script.then(
                BipScript.say("welcome"),
                BipScript.run(() -> this.shownFlower = this.nearestFlowerToPlayer()),
                BipScript.say("show_flower", () -> this.details(this.shownFlower)),
                BipScript.run(() -> this.targetCountAtPoint = this.targetCount),
                BipScript.say("point_at"),
                BipScript.waitFor(() -> this.targetCount > this.targetCountAtPoint,
                        POINT_NUDGE_EVERY, 2,
                        tier -> this.emit("point_nudge_" + tier, this.details(this.shownFlower)),
                        POINT_GIVE_UP,
                        "CONTEXT: you asked the player to point their crosshair at a flower so its "
                                + "data panel shows on the right. Help them do that."),
                BipScript.run(() -> {
                    Flower aimed = this.stateManager.getLastTargetedFlower();
                    this.panelFlower = aimed != null ? aimed : this.shownFlower;
                }),
                BipScript.say("panel_found"),
                BipScript.say("panel_color", () -> this.details(this.panelFlower)),
                BipScript.say("panel_smell", () -> this.details(this.panelFlower)),
                BipScript.say("panel_nectar", () -> this.details(this.panelFlower)),
                BipScript.say("panel_water", () -> this.details(this.panelFlower)),
                BipScript.say("panel_sun"),
                BipScript.say("panel_number"),
                BipScript.say("panel_meaning"),
                BipScript.say("explore_ask"),
                BipScript.awaitChat(SUGGEST_EVERY, 2,
                        tier -> this.suggest("explore_suggest_" + tier, this.standoutFlower()),
                        FIND_GIVE_UP,
                        () -> this.firstFind = this.currentFind(),
                        "CONTEXT: the player is exploring the garden to find a flower that "
                                + "interests them, then telling you about it. When they do, react "
                                + "with excitement using the real numbers of the flower on their "
                                + "DATA PANEL (say what the numbers mean in words) and ask what "
                                + "made them pick it. If they ask for help, fly to a flower with a "
                                + "standout number and describe it by color."),
                BipScript.run(() -> {
                    if(this.firstFind == null) {
                        this.firstFind = this.lastSuggestion != null
                                ? this.lastSuggestion : this.panelFlower;
                    }
                }),
                BipScript.say("explore_different"),
                BipScript.awaitChat(SUGGEST_EVERY, 2,
                        tier -> this.suggest("different_suggest_" + tier, this.differentFlower()),
                        FIND_GIVE_UP,
                        () -> { },
                        "CONTEXT: you asked the player to find a flower totally different from "
                                + "their first pick. Compare the flower on their DATA PANEL with "
                                + "their first pick in simple words, using the real numbers. If "
                                + "they ask for help, fly to a very different flower."),
                BipScript.say("questions"),
                BipScript.listen(QUESTIONS_QUIET,
                        "CONTEXT: question time about the flowers and the data panel. Answer "
                                + "simply using the real numbers. Pollinating comes next stage."),
                BipScript.say("wrap"),
                BipScript.say("next_tease"),
                BipScript.run(() -> {
                    this.finished = true;
                    this.stateManager.completeBipStage();
                }));
    }

    // --- Flowers Bip talks about ------------------------------------------------------------

    private Vec3d playerPos() {
        return this.world.getPlayers().isEmpty()
                ? Vec3d.ZERO : this.world.getPlayers().getFirst().getPos();
    }

    private Flower nearestFlowerToPlayer() {
        Vec3d player = this.playerPos();
        Flower best = null;
        double bestDistance = Double.MAX_VALUE;
        for(Flower f : this) {
            if(f.hasWithered()) {
                continue;
            }
            Vec3d position = this.flowerManager.getFlowerMinecraftPosition(this, f);
            if(position != null && position.distanceTo(player) < bestDistance) {
                bestDistance = position.distanceTo(player);
                best = f;
            }
        }
        return best;
    }

    /** The flower the player is telling Bip about: what's on their data panel right now. */
    private Flower currentFind() {
        Flower aimed = this.stateManager.getLastTargetedFlower();
        return aimed != null ? aimed : this.lastSuggestion;
    }

    private Trait standoutTrait(Flower f) {
        Trait best = Trait.NECTAR;
        double bestScore = -1;
        for(Trait t : Trait.values()) {
            double score = Math.abs(FlowerTraits.value(f, t) - 4.0);
            if(score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    /** A flower with one really high or really low number, not suggested before. */
    private Flower standoutFlower() {
        Flower best = null;
        double bestScore = -1;
        for(Flower f : this) {
            if(f.hasWithered() || this.suggested.contains(f)) {
                continue;
            }
            double score = Math.abs(FlowerTraits.value(f, this.standoutTrait(f)) - 4.0);
            if(score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /** The flower whose numbers are furthest from the player's first find. */
    private Flower differentFlower() {
        Flower from = this.firstFind != null ? this.firstFind : this.panelFlower;
        Flower best = null;
        double bestDistance = -1;
        for(Flower f : this) {
            if(f.hasWithered() || this.suggested.contains(f) || f.equals(from)) {
                continue;
            }
            double d = from == null ? 0 : f.distanceTo(from);
            if(d > bestDistance) {
                bestDistance = d;
                best = f;
            }
        }
        return best;
    }

    private void suggest(String beat, Flower f) {
        if(f == null) {
            return;
        }
        this.suggested.add(f);
        this.lastSuggestion = f;
        Map<String, Object> details = this.details(f);
        Trait t = this.standoutTrait(f);
        details.put("trait_label", FlowerTraits.label(t));
        details.put("trait_word", FlowerTraits.levelWord(FlowerTraits.value(f, t)));
        this.emit(beat, details);
    }

    private Map<String, Object> details(Flower f) {
        Map<String, Object> details = new LinkedHashMap<>();
        if(f == null) {
            return details;
        }
        details.put("flower_id", f.getNumber());
        details.put("color", this.flowerManager.getFlowerMinecraftColor(f));
        details.put("smell_word", FlowerTraits.word(f, Trait.SMELL));
        details.put("nectar_word", FlowerTraits.word(f, Trait.NECTAR));
        details.put("water_word", FlowerTraits.word(f, Trait.WATER));
        return details;
    }

    private void emit(String beat, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("activity", "observe");
        event.put("beat", beat);
        event.putAll(details);
        this.stateManager.recordAgentEvent("activity_beat", event);
    }

    // --- State plumbing ------------------------------------------------------------------------

    @Override
    public void tick() {
        this.script.tick();
        ++this.ticks;
    }

    @Override
    public boolean hasNextState() {
        return false;
    }

    @Override
    public BeetrapState getNextState() {
        return null;
    }

    @Override
    public String describeCurrentTaskForAgent() {
        return this.script.chatContext();
    }

    @Override
    public void onPlayerChat(ServerPlayerEntity player, String message) {
        this.script.onPlayerChat(message);
    }

    @Override
    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        if(exists && this.flowerManager.getFlowerByEntityId(this, id) != null) {
            ++this.targetCount;
        }
        // Show the data panel, but no beehive item: pollinating is next stage's lesson.
        super.onPlayerTargetNewEntity(player, false, id);
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }

    boolean isFinished() {
        return this.finished;
    }
}

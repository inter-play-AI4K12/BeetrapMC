package beetrap.btfmc.state;

import beetrap.btfmc.Beetrapfabricmc;
import beetrap.btfmc.flower.Flower;
import java.util.Map;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Bip's introduction, right after /game new 3 and the consent screens: he says hi, trades names,
 * explains what the whole game is about (a garden that picks flowers for you, like apps pick videos)
 * and the plan for the stages, and takes questions. Then the stage menu (or, in Base 2, the first
 * tutorial stage) follows. Python activity: {@code intro}.
 */
public class BipIntroState extends BeetrapState {

    private static final long QUESTIONS_QUIET = 300;   // ~15s of silence closes the question time

    private long ticks;
    private final BipScript script = this.bipScript("intro", () -> this.ticks);
    private boolean finished;

    public BipIntroState(BeetrapState parent) {
        super(parent);
        this.stateManager.setBipActivity("intro");
        Beetrapfabricmc.BIP_INTRODUCED = true;
        this.script.then(
                BipScript.pause(5),
                BipScript.say("hello"),
                BipScript.ask("ask_name", "ask_name_nudge_", Map::of,
                        "CONTEXT: you just asked the player's name. Greet them by name in one "
                                + "short line."),
                BipScript.say("purpose_garden"),
                BipScript.say("purpose_apps"),
                BipScript.say("purpose_goal"),
                BipScript.say("plan"),
                BipScript.say("questions"),
                BipScript.listen(QUESTIONS_QUIET,
                        "CONTEXT: the player may ask about the game before it starts. Answer "
                                + "simply and warmly. Don't explain recommendation systems or "
                                + "filter bubbles in detail yet, say you'll find out together."),
                BipScript.say("go"),
                BipScript.run(() -> {
                    Beetrapfabricmc.BIP_INTRO_DONE = true;
                    this.finished = true;
                }));
    }

    @Override
    public void tick() {
        this.script.tick();
        ++this.ticks;
    }

    @Override
    public boolean hasNextState() {
        return this.finished;
    }

    @Override
    public BeetrapState getNextState() {
        return new MenuState(this);
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
        super.onPlayerTargetNewEntity(player, false, id);
    }

    @Override
    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {
    }

    @Override
    public boolean timeTravelAvailable() {
        return false;
    }
}

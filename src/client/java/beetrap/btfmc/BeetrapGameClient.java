package beetrap.btfmc;

import static beetrap.btfmc.BeetrapfabricmcClient.beetrapLog;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_MULTIPLE_CHOICE_SCREEN_SHOWN;
import static beetrap.btfmc.networking.BeetrapLogS2CPayload.BEETRAP_LOG_ID_TEXT_SCREEN_SHOWN;
import static beetrap.btfmc.networking.BeginSubActivityS2CPayload.SUB_ACTIVITY_NULL;
import static beetrap.btfmc.networking.BeginSubActivityS2CPayload.SUB_ACTIVITY_PRESS_B_TO_INCREASE_POLLINATION_RADIUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

import beetrap.btfmc.networking.PlayerPollinateC2SPayload;
import beetrap.btfmc.networking.PlayerTargetNewEntityC2SPayload;
import beetrap.btfmc.networking.PlayerTimeTravelRequestC2SPayload;
import beetrap.btfmc.networking.PlayerTimeTravelRequestC2SPayload.Operations;
import beetrap.btfmc.networking.PollinationCircleRadiusIncreaseRequestC2SPayload;
import beetrap.btfmc.networking.RestartGameC2SPayload;
import beetrap.btfmc.screen.MultipleChoiceScreen;
import beetrap.btfmc.screen.ScreenQueue;
import beetrap.btfmc.screen.TextInputScreen;
import beetrap.btfmc.screen.TextScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ActionResult.Pass;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.HitResult.Type;
import net.minecraft.world.World;

public class BeetrapGameClient {

    private final MinecraftClient client;
    private final ScreenQueue sq;
    private Entity targetedEntity;
    private Entity glowingEntity;
    private int currentSubActivity;
    private boolean bPressed;
    private boolean hPressed;
    private String lastShownHint;

    public BeetrapGameClient() {
        this.client = MinecraftClient.getInstance();
        this.sq = new ScreenQueue();
        this.currentSubActivity = SUB_ACTIVITY_NULL;
        this.hPressed = false;
        this.lastShownHint = null;
    }

    private void onSubActivity1() {
        if(this.currentSubActivity != SUB_ACTIVITY_PRESS_B_TO_INCREASE_POLLINATION_RADIUS) {
            return;
        }

        if(glfwGetKey(this.client.getWindow().getHandle(), GLFW_KEY_B) == GLFW_PRESS) {
            if(!this.bPressed) {
                ClientPlayNetworking.send(
                        new PollinationCircleRadiusIncreaseRequestC2SPayload(0.1));
            }

            this.bPressed = true;
        } else {
            this.bPressed = false;
        }
    }

    private void handleHKeyPress() {
        if(glfwGetKey(this.client.getWindow().getHandle(), GLFW_KEY_K) == GLFW_PRESS) {
            if(!this.hPressed && this.lastShownHint != null) {
                this.sq.push(new TextScreen(this.sq, this.lastShownHint));
            }

            this.hPressed = true;
        } else {
            this.hPressed = false;
        }
    }

    public void onStartWorldTick(ClientWorld clientWorld) {
        if(this.updateTargetEntity()) {
            this.updateGlowingEntity();

            PlayerTargetNewEntityC2SPayload ptnec2sp;

            if(this.targetedEntity != null) {
                ptnec2sp = new PlayerTargetNewEntityC2SPayload(true, this.targetedEntity.getId());
            } else {
                ptnec2sp = new PlayerTargetNewEntityC2SPayload(false, 0);
            }

            ClientPlayNetworking.send(ptnec2sp);
        }

        if(this.sq.shouldShowNext()) {
            Screen s = this.sq.pop();

            if(s instanceof TextScreen ts) {
                beetrapLog(BEETRAP_LOG_ID_TEXT_SCREEN_SHOWN, ts.toString());
            } else if(s instanceof MultipleChoiceScreen mcs) {
                beetrapLog(BEETRAP_LOG_ID_MULTIPLE_CHOICE_SCREEN_SHOWN, mcs.toString());
            }

            this.client.setScreen(s);
        }

        this.onSubActivity1();
        this.handleHKeyPress();
    }

    private boolean updateTargetEntity() {
        HitResult hit = client.crosshairTarget;

        if(hit == null) {
            return false;
        }

        switch(hit.getType()) {
            case Type.MISS, Type.BLOCK -> {
                if(this.targetedEntity == null) {
                    return false;
                }

                this.targetedEntity = null;
            }

            case Type.ENTITY -> {
                EntityHitResult entityHit = (EntityHitResult)hit;
                Entity entity = entityHit.getEntity();

                if(this.targetedEntity == entity) {
                    return false;
                }

                this.targetedEntity = entity;
                ClientGlowingEntityManager.addGlowingEntity(this.targetedEntity);
            }
        }

        return true;
    }

    private void updateGlowingEntity() {
        if(this.targetedEntity == this.glowingEntity) {
            return;
        }

        if(this.glowingEntity != null) {
            ClientGlowingEntityManager.removeGlowingEntity(this.glowingEntity);
        }

        this.glowingEntity = this.targetedEntity;

        if(this.targetedEntity != null) {
            ClientGlowingEntityManager.addGlowingEntity(this.glowingEntity);
        }
    }

    public ActionResult onPlayerUseItem(PlayerEntity player, World world, Hand hand) {
        ActionResult ar = new Pass();

        switch(player.getInventory().selectedSlot) {
            case 0 -> {
                ClientPlayNetworking.send(
                        new PlayerTimeTravelRequestC2SPayload(-1, Operations.ADD));
            }

            case 4 -> {
                PlayerPollinateC2SPayload ppc2sp;

                if(this.targetedEntity != null) {
                    ppc2sp = new PlayerPollinateC2SPayload(true, this.targetedEntity.getId());
                } else {
                    ppc2sp = new PlayerPollinateC2SPayload(false, 0);
                }

                ClientPlayNetworking.send(ppc2sp);
            }

            case 6 -> {
                ClientPlayNetworking.send(new RestartGameC2SPayload());
            }

            case 8 -> {
                ClientPlayNetworking.send(new PlayerTimeTravelRequestC2SPayload(1, Operations.ADD));
            }
        }

        return ar;
    }

    public void onEntityPositionUpdate(int entityId, double x, double y, double z) {
        Entity e = this.client.world.getEntityById(entityId);

        if(e == null) {
            return;
        }

        e.setPosition(x, y, z);
    }

    public void showTextScreen(String s) {
        this.lastShownHint = s;
        this.sq.push(new TextScreen(this.sq, s));
    }

    public void showMultipleChoiceScreen(String questionId, String question, String[] choices) {
        this.sq.push(new MultipleChoiceScreen(this.sq, questionId, question, choices));
    }

    public void showTextInputScreen(String screenId, String prompt) {
        this.sq.push(new TextInputScreen(this.sq, screenId, prompt));
    }

    public void beginSubActivity(int subActivityId) {
        this.currentSubActivity = subActivityId;
    }
}

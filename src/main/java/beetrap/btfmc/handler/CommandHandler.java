package beetrap.btfmc.handler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandManager.RegistrationEnvironment;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CommandHandler {

    private static final Logger LOG = LogManager.getLogger(CommandHandler.class);

    private CommandHandler() {
        throw new AssertionError();
    }

    private static int gameNewCommand(CommandContext<ServerCommandSource> commandContext) {
        try {
            Integer aiLevel = commandContext.getArgument("ai_level", Integer.class);

            MinecraftServer server = commandContext.getSource().getServer();
            ServerWorld world = server.getOverworld();

            for(ServerPlayerEntity player : world.getPlayers()) {
                player.changeGameMode(GameMode.ADVENTURE);
                player.getAbilities().allowFlying = true;
                player.sendAbilitiesUpdate();
            }

            // BIP_INTRODUCED is a JVM-wide static, so without this reset it stays true for the rest
            // of the server process once Bip has ever greeted anyone — silently skipping the
            // greeting/name-ask on every later playthrough in the same session.
            beetrap.btfmc.Beetrapfabricmc.BIP_INTRODUCED = false;
            beetrap.btfmc.Beetrapfabricmc.BIP_INTRO_DONE = false;
            beetrap.btfmc.TutorialFlow.reset();
            BeetrapGameHandler.createGame(server, aiLevel == null ? 0 : aiLevel);
        } catch(Throwable t) {
            LOG.error(t);
        }

        return 0;
    }

    /**
     * Researcher shortcut: /game stage 1-5 jumps straight to that stage (1-4 tutorial, 5 actual
     * game) in a fresh garden, keeping Bip's session; /game stage 0 replays the introduction.
     */
    private static int gameStageCommand(CommandContext<ServerCommandSource> commandContext) {
        try {
            int stage = commandContext.getArgument("stage", Integer.class);
            MinecraftServer server = commandContext.getSource().getServer();
            beetrap.btfmc.TutorialFlow.removePortal();
            if(stage <= 0) {
                beetrap.btfmc.Beetrapfabricmc.BIP_INTRO_DONE = false;
                beetrap.btfmc.TutorialFlow.pendingActivity = beetrap.btfmc.TutorialFlow.NONE;
            } else {
                beetrap.btfmc.Beetrapfabricmc.BIP_INTRO_DONE = true;
                beetrap.btfmc.TutorialFlow.pendingActivity = Math.min(stage, 5) - 1;
            }
            BeetrapGameHandler.restartGame(server, 3);
        } catch(Throwable t) {
            LOG.error(t);
        }
        return 0;
    }

    private static int gameDestroyCommand(
            CommandContext<ServerCommandSource> commandSourceCommandContext) {
        BeetrapGameHandler.destroyGame();
        return 0;
    }

    private static void registerCommands0(CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess commandRegistryAccess,
            RegistrationEnvironment registrationEnvironment) {
        dispatcher.register(CommandManager.literal("game")
                .then(CommandManager.literal("new")
                        .then(CommandManager.argument("ai_level", IntegerArgumentType.integer())
                                .executes(CommandHandler::gameNewCommand)
                        )
                )
                .then(CommandManager.literal("destroy")
                        .executes(CommandHandler::gameDestroyCommand)
                )
                .then(CommandManager.literal("stage")
                        .then(CommandManager.argument("stage", IntegerArgumentType.integer(0, 5))
                                .executes(CommandHandler::gameStageCommand)
                        )
                )
        );

    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register(CommandHandler::registerCommands0);
    }
}

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

            BeetrapGameHandler.createGame(server, aiLevel == null ? 0 : aiLevel);
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
        );

    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register(CommandHandler::registerCommands0);
    }
}

package com.ishland.c2me.server.utils.common;

import com.ishland.c2me.base.mixin.access.IServerChunkManager;
import com.ishland.c2me.notickvd.common.IChunkTicketManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.text.Text;

public class C2MECommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("c2me")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(
                                CommandManager.literal("notick")
                                        .requires(unused -> com.ishland.c2me.notickvd.ModuleEntryPoint.enabled)
                                        .executes(C2MECommands::noTickCommand)
                        )
                        .then(
                                CommandManager.literal("debug")
                                        .requires(unused -> FabricLoader.getInstance().isDevelopmentEnvironment())
                        )
        );
    }

    private static int noTickCommand(CommandContext<ServerCommandSource> ctx) {
        final ServerChunkManager chunkManager = ctx.getSource().getWorld().toServerWorld().getChunkManager();
        final ChunkTicketManager ticketManager = ((IServerChunkManager) chunkManager).getTicketManager();
        final int noTickOnlyChunks = ((IChunkTicketManager) ticketManager).getNoTickOnlyChunks().size();
        final int noTickPendingTicketUpdates = ((IChunkTicketManager) ticketManager).getNoTickPendingTicketUpdates();
        ctx.getSource().sendFeedback(() -> Text.of(String.format("No-tick chunks: %d", noTickOnlyChunks)), true);
        ctx.getSource().sendFeedback(() -> Text.of(String.format("No-tick chunk pending ticket updates: %d", noTickPendingTicketUpdates)), true);

        return 0;
    }

}

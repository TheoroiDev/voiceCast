package com.theo.voicecast.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.theo.voicecast.VoiceCast;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server admin/debug commands (voiceCast#29):
 * {@code /voicecast status | engine list | engine default <id> | enabled <bool>
 * | whitelist add|remove <player> | whitelist list | reload}.
 *
 * <p>Read-only subcommands (status, engine list, whitelist list) are permission
 * level 0; everything that mutates state is level 2. Registered via the
 * architectury {@link CommandRegistrationEvent} so both loaders share the tree.
 */
public final class VoiceCastServerCommands {
    private static boolean registered;

    private VoiceCastServerCommands() {}

    public static void register() {
        if (registered) return;
        registered = true;
        CommandRegistrationEvent.EVENT.register(VoiceCastServerCommands::build);
    }

    private static void build(CommandDispatcher<CommandSourceStack> dispatcher,
                              CommandBuildContext buildContext, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("voicecast")
                // Client-only commands: executed by the client dispatcher (which
                // intercepts before the packet is sent). These level-0 stubs
                // exist so the server-synced completion tree offers them too —
                // without them the client-only children are executable but
                // invisible in chat autocomplete.
                .then(Commands.literal("settings").executes(VoiceCastServerCommands::clientOnly))
                .then(Commands.literal("verbose").executes(VoiceCastServerCommands::clientOnly))
                .then(Commands.literal("debugwav").executes(VoiceCastServerCommands::clientOnly))
                .then(Commands.literal("status").executes(VoiceCastServerCommands::status))
                .then(Commands.literal("engine")
                        .then(Commands.literal("list").executes(VoiceCastServerCommands::engineList))
                        .then(Commands.literal("default")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(VoiceCastServerCommands::engineDefault))))
                .then(Commands.literal("enabled")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(VoiceCastServerCommands::setEnabled)))
                .then(Commands.literal("whitelist")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(VoiceCastServerCommands::whitelistList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> whitelistChange(ctx, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> whitelistChange(ctx, false)))))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(VoiceCastServerCommands::reload)));
    }

    /** Reached only from a server console (clients intercept these locally). */
    private static int clientOnly(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.translatable("voicecast.cmd.client_only"));
        return 0;
    }

    private static VoiceCastServer server() {
        return VoiceCastServer.INSTANCE;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        VoiceCastServer s = server();
        List<String> lines = new ArrayList<>();
        lines.add("voicecast server: enabled=" + s.enabled()
                + ", defaultEngine=" + s.defaultEngineId()
                + ", sessions=" + s.sessionCount());
        for (Map.Entry<String, String> e : s.engineStateSnapshot().entrySet()) {
            lines.add("  engine " + e.getKey() + " = " + e.getValue());
        }
        lines.add("catalog: " + s.catalogModelIds().size() + " model(s)");
        send(ctx, lines);
        return Command.SINGLE_SUCCESS;
    }

    private static int engineList(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = new ArrayList<>();
        lines.add("models (declaration order = language-default precedence):");
        for (String line : server().catalogSummary()) {
            lines.add("  " + line);
        }
        send(ctx, lines);
        return Command.SINGLE_SUCCESS;
    }

    private static int engineDefault(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        if (!server().isValidEngineId(id)) {
            ctx.getSource().sendFailure(Component.literal("Unknown engine '" + id
                    + "' (catalog: " + String.join(", ", server().catalogModelIds()) + ")"));
            return 0;
        }
        server().setDefaultEngine(id);
        send(ctx, List.of("default engine set to " + id));
        return Command.SINGLE_SUCCESS;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        server().setEnabled(value);
        send(ctx, List.of("voicecast enabled=" + value));
        return Command.SINGLE_SUCCESS;
    }

    private static int whitelistList(CommandContext<CommandSourceStack> ctx) {
        List<String> entries = new ArrayList<>(server().whitelistEntries());
        ctx.getSource().sendSuccess(() -> Component.literal(
                entries.isEmpty() ? "whitelist: (empty — everyone allowed)"
                        : "whitelist: " + String.join(", ", entries)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int whitelistChange(CommandContext<CommandSourceStack> ctx, boolean add)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        boolean changed = add
                ? server().whitelistAdd(player.getUUID())
                : server().whitelistRemove(player.getUUID());
        send(ctx, List.of((add ? "whitelist add " : "whitelist remove ")
                + player.getName().getString() + (changed ? "" : " (no change)")));
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        server().reloadConfig();
        send(ctx, List.of("voicecast server config reloaded (default engine "
                + server().defaultEngineId() + ", enabled=" + server().enabled() + ")"));
        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        for (String line : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        VoiceCast.LOGGER.debug("/voicecast command: {}", lines);
    }
}

package com.ultimateimprovements.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the shape of the {@code /ui} Brigadier tree: the subcommand name is a
 * {@code word} and everything after it a {@code greedyString}, so the full
 * {@code String[]} (args[0] = subcommand) must survive in the parse tree — this
 * is what the console/RCON path feeds into {@code SubCommandRegistry}.
 */
class PaperCommandTreeTest {

    private static CommandDispatcher<CommandSourceStack> tree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ui")
                .then(Commands.argument("sub", StringArgumentType.word())
                        .then(Commands.argument("args", StringArgumentType.greedyString())));
        dispatcher.register(root);
        return dispatcher;
    }

    @Test
    void parsesSubcommandAndTail() throws Exception {
        String input = "ui auth forcelogin rizer001";
        var context = tree().parse(input, null).getContext().build(input);
        assertEquals("auth", context.getArgument("sub", String.class));
        assertEquals("forcelogin rizer001", context.getArgument("args", String.class));
    }

    @Test
    void parsesSubcommandWithoutTail() throws Exception {
        String input = "ui auth";
        var context = tree().parse(input, null).getContext().build(input);
        assertEquals("auth", context.getArgument("sub", String.class));
    }
}

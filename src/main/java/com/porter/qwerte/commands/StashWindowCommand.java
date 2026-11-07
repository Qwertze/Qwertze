package com.porter.qwerte.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.porter.qwerte.modules.StashWindow;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

/** {@code .stash-window} (or {@code .stash}) opens the Stash Manager window once the chat has closed. */
public class StashWindowCommand extends Command {
    public StashWindowCommand() {
        super("stash-window", "Opens the Stash Manager window.", "stash");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> {
            StashWindow module = StashWindow.get();
            if (module == null) return 0;
            module.openLater();
            return SINGLE_SUCCESS;
        });
    }
}

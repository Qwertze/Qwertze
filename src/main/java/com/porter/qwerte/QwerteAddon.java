package com.porter.qwerte;

import com.mojang.logging.LogUtils;
import com.porter.qwerte.commands.StashWindowCommand;
import com.porter.qwerte.modules.AutoDirt;
import com.porter.qwerte.modules.AutoTill;
import com.porter.qwerte.modules.ContainerTweaks;
import com.porter.qwerte.modules.Printer;
import com.porter.qwerte.modules.ResetPearl;
import com.porter.qwerte.modules.StashManager;
import com.porter.qwerte.modules.StashOverlay;
import com.porter.qwerte.modules.StashSorter;
import com.porter.qwerte.modules.StashWindow;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class QwerteAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Qwerte");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Qwerte Addon");

        // Modules
        Modules.get().add(new AutoTill());
        Modules.get().add(new AutoDirt());
        Modules.get().add(new StashSorter());
        Modules.get().add(new StashOverlay());
        Modules.get().add(new StashManager());
        Modules.get().add(new ResetPearl());
        Modules.get().add(new StashWindow());
        Modules.get().add(new ContainerTweaks());
        Modules.get().add(new Printer());

        // Commands
        Commands.add(new StashWindowCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.porter.qwerte";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Qwertze", "Qwerte");
    }
}

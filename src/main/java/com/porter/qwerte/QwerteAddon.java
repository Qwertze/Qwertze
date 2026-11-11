package com.porter.qwerte;

import com.mojang.logging.LogUtils;
import com.porter.qwerte.commands.StashWindowCommand;
import com.porter.qwerte.modules.AutoDirt;
import com.porter.qwerte.modules.AutoTill;
import com.porter.qwerte.modules.ContainerTweaks;
import com.porter.qwerte.modules.Messenger;
import com.porter.qwerte.modules.OrderNotifications;
import com.porter.qwerte.modules.Printer;
import com.porter.qwerte.modules.ResetPearl;
import com.porter.qwerte.modules.StashManager;
import com.porter.qwerte.modules.StashOverlay;
import com.porter.qwerte.modules.StashSorter;
import com.porter.qwerte.modules.StashWindow;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.MeteorClient;
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
        StartupChecks.add(new AutoTill());
        StartupChecks.add(new AutoDirt());
        StartupChecks.add(new StashSorter());
        StartupChecks.add(new StashOverlay());
        StartupChecks.add(new StashManager());
        StartupChecks.add(new ResetPearl());
        StartupChecks.add(new StashWindow());
        StartupChecks.add(new Messenger());
        StartupChecks.add(new ContainerTweaks());
        StartupChecks.add(new Printer());

        // On by default; a saved state (loaded after the addons start) overrides it, so turning it off sticks
        OrderNotifications notifications = new OrderNotifications();
        StartupChecks.add(notifications);
        notifications.toggle();

        StartupChecks.init();

        // The stash area draft in the world (follows the draft of the Stash window; not a module)
        MeteorClient.EVENT_BUS.subscribe(new com.porter.qwerte.manager.gui.AreaOutline());

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

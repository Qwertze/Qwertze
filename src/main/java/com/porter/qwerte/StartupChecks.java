package com.porter.qwerte;

import com.porter.qwerte.compat.NbtRead;
import com.porter.qwerte.tweaks.MigrationSource;
import com.porter.qwerte.tweaks.MigrationSource.Entry;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Things that can only be checked once every addon has started and Meteor has loaded the saved modules, which is just
 * before the first tick: moving Container Tweaks' saved state to its new name, and finding Qwerte modules that another
 * addon's module of the same name pushed out (Meteor keeps only one module per name).
 */
public final class StartupChecks {
    private static final List<Module> OURS = new ArrayList<>();
    private static final long MAX_FILE = 32L << 20;
    private static final long MAX_HEAP = 64L << 20;
    private static boolean done;
    private static String warning;

    private StartupChecks() {}

    /** Records a module as ours and adds it to Meteor. */
    public static void add(Module module) {
        OURS.add(module);
        Modules.get().add(module);
    }

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(new StartupChecks());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!done) {
            done = true;
            try {
                migrate();
            } catch (Throwable e) {
                QwerteAddon.LOG.error("Container Tweaks: couldn't move the saved state to its new name, starting from defaults", e);
            }
            collisions();
        }
        if (warning != null && MeteorClient.mc.level != null && MeteorClient.mc.player != null) {
            ChatUtils.warning("%s", warning);
            warning = null;
        }
    }

    /** Meteor saves modules.nbt without compression; a compressed file is read too. */
    private static CompoundTag read(File file, NbtAccounter accounter) throws Exception {
        boolean gzip;
        try (InputStream probe = Files.newInputStream(file.toPath())) {
            gzip = probe.read() == 0x1f && probe.read() == 0x8b;
        }
        if (gzip) return NbtIo.readCompressed(file.toPath(), accounter);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            return NbtIo.read(in, accounter);
        }
    }

    private static void migrate() throws Exception {
        Module module = Modules.get().get(MigrationSource.NEW_NAME);
        if (module == null || !OURS.contains(module)) return;

        File file = new File(MeteorClient.FOLDER, "modules.nbt");
        if (!file.exists()) return;
        long size = Files.size(file.toPath());
        if (size > MAX_FILE) {
            QwerteAddon.LOG.warn("Container Tweaks: modules.nbt is {} bytes, too large to read for the migration, starting from defaults", size);
            return;
        }
        CompoundTag root = read(file, NbtAccounter.create(MAX_HEAP));

        List<Entry> entries = new ArrayList<>();
        List<CompoundTag> tags = new ArrayList<>();
        for (Tag t : NbtRead.compounds(root, "modules")) {
            if (!(t instanceof CompoundTag c)) continue;
            Set<String> groups = new HashSet<>();
            Set<String> settings = new HashSet<>();
            if (c.get("settings") instanceof CompoundTag s) {
                ListTag groupList = NbtRead.compounds(s, "groups");
                for (Tag g : groupList) {
                    if (!(g instanceof CompoundTag gc)) continue;
                    groups.add(NbtRead.string(gc, "name"));
                    for (Tag st : NbtRead.compounds(gc, "settings")) {
                        if (st instanceof CompoundTag sc) settings.add(NbtRead.string(sc, "name"));
                    }
                }
            }
            entries.add(new Entry(NbtRead.string(c, "name"), groups, settings));
            tags.add(c);
        }

        MigrationSource.Decision decision = MigrationSource.choose(entries);
        if (decision.source() == null) {
            QwerteAddon.LOG.info("Container Tweaks: {} ({})", decision.reason(), MigrationSource.NEW_NAME);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equalsIgnoreCase(decision.source()) && MigrationSource.isOurs(entries.get(i))) {
                module.fromTag(tags.get(i));
                QwerteAddon.LOG.info("Container Tweaks: {} (now {})", decision.reason(), MigrationSource.NEW_NAME);
                return;
            }
        }
    }

    private static void collisions() {
        List<String> lines = new ArrayList<>();
        for (Module ours : OURS) {
            Module current = Modules.get().get(ours.name);
            if (current == ours) continue;
            String other = current == null ? "none" : current.getClass().getName();
            QwerteAddon.LOG.warn("Qwerte module '{}' was replaced by another addon's module of the same name ({}), so it is not available", ours.name, other);
            lines.add(ours.name);
        }
        if (!lines.isEmpty()) {
            warning = "Another addon replaced Qwerte module(s) with the same name: " + String.join(", ", lines) + ". See the log for which.";
        }
    }
}

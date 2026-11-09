package com.porter.qwerte.printer.source;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.printer.schematic.LitematicReader;
import com.porter.qwerte.printer.schematic.SchematicFile;
import com.porter.qwerte.printer.schematic.Transform;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Chooses and loads what to build. Litematica modes degrade in steps, with one set of notes for the user:
 * 1. full mode, Litematica's in-memory placement (no file access);
 * 2. the placement's own file, with the placement's origin, rotation and mirror;
 * 3. the file named in the settings, with the settings' origin, rotation and mirror.
 * Call from the client thread; the result arrives on the client thread (a file is read on a worker). Returns a future
 * to cancel, or null when the answer was immediate.
 */
public final class SourcePicker {
    private SourcePicker() {
    }

    public enum Mode { LITEMATICA_LATEST, LITEMATICA_BY_NAME, FILE }

    /**
     * @param name     a file name (FILE; the step-3 fallback) or a placement name (LITEMATICA_BY_NAME)
     * @param fileName the file name setting, used by FILE and by the step-3 fallback
     * @param root     the schematics folder (gameDir/schematics)
     */
    public record Config(Mode mode, String name, String fileName, int ox, int oy, int oz, Transform.Rot rot, Transform.Mir mir, Path root, Duration timeout) {
    }

    /** The outcome: a source with notes inside it, or an error fit for chat. */
    public record Picked(BuildSource source, String error) {
        public boolean ok() {
            return source != null;
        }
    }

    public static Future<?> pick(Config cfg, Consumer<Picked> done) {
        List<String> notes = new ArrayList<>();
        if (cfg.mode() == Mode.FILE) return fromSettings(cfg, notes, done);

        if (!LitematicaBridge.available()) {
            notes.add(LitematicaBridge.reason() + " Reading the file by name with this module's origin, rotation and mirror instead.");
            return fromSettings(cfg, notes, done);
        }
        LitematicaBridge.Placement p = cfg.mode() == Mode.LITEMATICA_BY_NAME ? LitematicaBridge.byName(cfg.name()) : LitematicaBridge.latest();
        if (p == null) {
            done.accept(new Picked(null, cfg.mode() == Mode.LITEMATICA_BY_NAME
                ? "No Litematica placement is named \"" + LitematicReader.clean(cfg.name(), 40) + "\" (or several match)."
                : "Litematica has no schematic placement; load one first."));
            return null;
        }

        List<String> why = new ArrayList<>();
        try {
            BuildSource live = LitematicaSource.live(p, notes, why);
            if (live != null) {
                done.accept(new Picked(live, null));
                return null;
            }
        } catch (RuntimeException e) {
            QwerteAddon.LOG.warn("printer: reading the Litematica placement failed", e);
            why.add("Reading it failed.");
        }
        String reason = why.isEmpty() ? "" : why.get(0) + " ";

        // step 2: the placement's file, if it lies in an allowed folder
        SchematicFile.Resolved file = p.file() == null ? new SchematicFile.Resolved(null, "Litematica gave no file.")
            : SchematicFile.resolveAllowed(roots(cfg), p.file());
        if (file.ok()) {
            notes.add(reason + "Reading the placement's file instead; its origin, rotation and mirror still come from Litematica.");
            return SchematicFile.loadAsync(file.path(), cfg.timeout(), SourcePicker::onClient, r -> {
                if (!r.ok()) {
                    done.accept(new Picked(null, r.error()));
                    return;
                }
                try {
                    done.accept(new Picked(LitematicaSource.fromFile(p, r.data(), notes), null));
                } catch (RuntimeException e) {
                    QwerteAddon.LOG.warn("printer: setting up the file source failed", e);
                    done.accept(new Picked(null, "The schematic could not be set up."));
                }
            });
        }
        notes.add(reason + "The placement's file can't be used (" + file.error() + ") so the file named in the settings is read with the settings' origin, rotation and mirror.");
        return fromSettings(cfg, notes, done);
    }

    private static List<Path> roots(Config cfg) {
        List<Path> roots = new ArrayList<>();
        roots.add(cfg.root());
        roots.add(LitematicaBridge.baseDir());
        return roots;
    }

    private static void onClient(Runnable r) {
        meteordevelopment.meteorclient.MeteorClient.mc.execute(r);
    }

    private static Future<?> fromSettings(Config cfg, List<String> notes, Consumer<Picked> done) {
        String name = cfg.mode() == Mode.FILE ? cfg.name() : cfg.fileName();
        SchematicFile.Resolved file = SchematicFile.resolve(cfg.root(), name);
        if (!file.ok()) {
            done.accept(new Picked(null, file.error()));
            return null;
        }
        String label = file.path().getFileName().toString();
        return FileSource.loadAsync(file.path(), label, cfg.ox(), cfg.oy(), cfg.oz(), cfg.rot(), cfg.mir(), notes, cfg.timeout(),
            l -> done.accept(new Picked(l.source(), l.error())));
    }
}

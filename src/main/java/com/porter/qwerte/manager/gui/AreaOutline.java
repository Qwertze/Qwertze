package com.porter.qwerte.manager.gui;

import com.porter.qwerte.stash.AreaDraft;
import com.porter.qwerte.stash.AreaEdit;
import com.porter.qwerte.stash.CraftBounds;
import com.porter.qwerte.stash.StashIndex;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import org.joml.Vector3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The stash area draft in the world (AREA-CONTRACT.md "In-game Stash window"): the box in an edit colour with its W x H x D size over it, for as long
 * as the draft exists. The draft is dropped when the player leaves the world, and a lapsed one (15 minutes after the last change) is announced once
 * in chat. Always on: it is not a module, it follows the draft the Stash window holds. Only the box and its size are drawn, never coordinates.
 */
public final class AreaOutline {
    /** The edit colour: amber, apart from the other overlays. */
    private static final Color SIDE = new Color(255, 170, 0, 28);
    private static final Color LINE = new Color(255, 170, 0, 230);
    private static final Color TEXT = new Color(255, 200, 80, 255);

    private final Vector3d screenPos = new Vector3d();

    /** The draft's box when it is to be drawn here (a draft in this dimension), else null. */
    private static AreaEdit.Draft visible() {
        if (mc.player == null || mc.level == null) return null;
        AreaDraft.State st = AreaDraft.shared().get(System.currentTimeMillis());
        if (st == null || st.draft() == null) return null;
        String here = StashIndex.dimension();
        if (here.isEmpty() || !CraftBounds.sameDimension(st.draft().dimension(), here)) return null;
        return st.draft();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        AreaEdit.Draft d = visible();
        if (d == null) return;
        int[] b = d.box();
        event.renderer.box(b[0], b[1], b[2], b[3] + 1.0, b[4] + 1.0, b[5] + 1.0, SIDE, LINE, ShapeMode.Both, 0);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        AreaEdit.Draft d = visible();
        if (d == null) return;
        int[] b = d.box();
        screenPos.set((b[0] + b[3] + 1) / 2.0, b[4] + 1.0, (b[2] + b[5] + 1) / 2.0);
        if (!NametagUtils.to2D(screenPos, 1.0, false)) return;
        String text = "Area draft " + d.size();
        NametagUtils.begin(screenPos, event.drawContext);
        event.drawContext.drawString(mc.font, text, -mc.font.width(text) / 2, -10, TEXT.getPacked(), true);
        NametagUtils.end(event.drawContext);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        AreaDraft.shared().clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (AreaDraft.shared().get(System.currentTimeMillis()) == null && AreaDraft.shared().takeExpired()) {
            ChatUtils.info("%s", "The stash area draft was dropped after 15 minutes without a change.");
        }
    }
}

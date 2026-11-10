package com.porter.qwerte.manager.gui.tabs;

import com.porter.qwerte.manager.model.AreaModel;
import com.porter.qwerte.manager.model.StashRef;
import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.manager.net.ApiError;
import com.porter.qwerte.stash.Stash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The Area card for a stash of the Stash Manager server: {@code GET} and {@code POST /api/v1/area}, with the window's login (the password prompt, the CSRF header). */
public final class ServerAreaSource implements AreaSource {
    private final ServerTabHost host;

    public ServerAreaSource(ServerTabHost host) {
        this.host = host;
    }

    @Override
    public boolean server() {
        return true;
    }

    @Override
    public boolean ready() {
        return host.selectedStash() != null;
    }

    @Override
    public String notReady() {
        return "No stash selected.";
    }

    @Override
    public String key() {
        StashRef s = host.selectedStash();
        return s == null ? "" : "server:" + s.key();
    }

    @Override
    public String label() {
        StashRef s = host.selectedStash();
        return s == null ? "" : Text.sanitize(s.stash(), 60);
    }

    @Override
    public boolean sameServer() {
        StashRef s = host.selectedStash();
        if (s == null) return false;
        // A server id the server made up (forced streamer) never matches; that account has no card anyway
        return s.server().equalsIgnoreCase(Stash.currentServer());
    }

    @Override
    public boolean canWrite() {
        return host.auth().can("settings") && !host.auth().forcedStreamer();
    }

    @Override
    public boolean hide() {
        return host.policy().hideCoords();
    }

    @Override
    public String botPlayer() {
        StashRef s = host.selectedStash();
        return s == null ? "" : s.player();
    }

    @Override
    public void load(Consumer<Result> done) {
        StashRef s = host.selectedStash();
        if (s == null) {
            done.accept(new Result(null, ""));
            return;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", s.server());
        query.put("stash", s.stash());
        // The poll goes straight to the client: it must never open the password prompt
        host.client().get("/api/v1/area", query, r -> {
            if (r.ok()) {
                AreaModel.Data d = AreaModel.parse(r.body());
                done.accept(d == null ? new Result(null, "The server's answer wasn't an area.") : new Result(d, ""));
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
                done.accept(new Result(null, ""));
            } else if (r.kind() == ApiError.Kind.CANCELLED || r.kind() == ApiError.Kind.NOT_MODIFIED) {
                done.accept(new Result(null, ""));
            } else {
                done.accept(new Result(null, r.kind() == ApiError.Kind.CONFIRM ? "The server asked for the password." : AreaModel.saveError(r.status(), Text.sanitize(r.error(), 120))));
            }
        });
    }

    @Override
    public void save(int[] box, String dimension, long rev, Consumer<Result> done) {
        StashRef s = host.selectedStash();
        if (s == null || !canWrite()) {
            done.accept(new Result(null, "Your account may not change the stash area."));
            return;
        }
        host.write("/api/v1/area", AreaModel.saveBody(s.server(), s.stash(), box, dimension, rev), r -> {
            if (r.ok()) {
                AreaModel.Data d = AreaModel.parse(r.body());
                done.accept(new Result(d, d == null ? "Saved, but the server's answer wasn't an area." : ""));
            } else if (r.kind() == ApiError.Kind.CANCELLED) {
                done.accept(new Result(null, "Not saved: the password was not confirmed."));
            } else if (r.kind() == ApiError.Kind.LOGIN || r.kind() == ApiError.Kind.MUST_CHANGE) {
                host.failed(r);
                done.accept(new Result(null, ""));
            } else if (r.kind() == ApiError.Kind.RATE_LIMIT) {
                done.accept(new Result(null, AreaModel.saveError(429, null) + " Wait " + r.wait429() + " s."));
            } else if (r.kind() == ApiError.Kind.NETWORK) {
                done.accept(new Result(null, "Can't reach the server."));
            } else {
                done.accept(new Result(null, AreaModel.saveError(r.status(), Text.sanitize(r.error(), 160))));
            }
        });
    }
}

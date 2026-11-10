package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.manager.net.Session;
import com.porter.qwerte.manager.net.SmClient;
import com.porter.qwerte.notify.Kind;
import com.porter.qwerte.notify.NoticeText;
import com.porter.qwerte.notify.OrderSnap;
import com.porter.qwerte.notify.OrderSnaps;
import com.porter.qwerte.notify.OrderWatch;
import com.porter.qwerte.notify.PollPlan;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.SoundEventListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Tells you in game when one of your Stash Manager orders ends: a sound, a toast and, if wanted, a chat line.
 *
 * <p>Orders are read the way the Printer's stash orders read them: through the Stash Window module's background client and its
 * saved login ({@code GET /api/v1/auth/state}, {@code GET /api/v1/stashes}, {@code GET /api/v1/orders}), so the window does not
 * have to be open. It needs the Stash Window's connect-to-server on, its address set and a logged-in session; without one it does
 * nothing. No new server API is used. The state-change logic is pure and lives in {@code com.porter.qwerte.notify}.
 *
 * <p>Threading: everything here runs on the client thread (the client calls back through {@code mc::execute}); one request is in
 * flight at a time, and a token drops the answers of an older session or a deactivation.
 */
public class OrderNotifications extends Module {
    private static final long CYCLE_MS = 10_000;
    private static final long RETRY_MS = 30_000;
    private static final long MIN_GAP_MS = 1_000;
    private static final long WATCHDOG_MS = 40_000;
    private static final int MAX_TOASTS = 3;
    private static final int MAX_CHAT = 5;

    private final SettingGroup sg = settings.createGroup("Order notifications");

    private final Setting<Boolean> enable = sg.add(new BoolSetting.Builder()
        .name("enable")
        .description("Tell you when one of your Stash Manager orders is delivered, delivered with shortfalls, fails or is cancelled. Needs the Stash Window's connect-to-server on and a login.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<SoundEvent>> completeSound = sg.add(new SoundEventListSetting.Builder()
        .name("complete-sound")
        .description("The sound for an order delivered in full (the first one in the list is played; none for silence).")
        .defaultValue(SoundEvents.PLAYER_LEVELUP)
        .build()
    );

    private final Setting<List<SoundEvent>> partialSound = sg.add(new SoundEventListSetting.Builder()
        .name("partial-sound")
        .description("The sound for an order delivered with shortfalls (lines, crafts or kits that came up short).")
        .defaultValue(SoundEvents.NOTE_BLOCK_PLING.value())
        .build()
    );

    private final Setting<List<SoundEvent>> failedSound = sg.add(new SoundEventListSetting.Builder()
        .name("failed-sound")
        .description("The sound for an order that failed or was cancelled.")
        .defaultValue(SoundEvents.VILLAGER_NO)
        .build()
    );

    private final Setting<List<SoundEvent>> arrivingSound = sg.add(new SoundEventListSetting.Builder()
        .name("arriving-sound")
        .description("The sound for the bot starting the delivery trip of your order. Off while the list is empty.")
        .build()
    );

    private final Setting<Integer> volume = sg.add(new IntSetting.Builder()
        .name("volume")
        .description("How loud the sounds are, in percent.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Double> pitch = sg.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The pitch of the sounds.")
        .defaultValue(1.0)
        .min(0.5)
        .max(2.0)
        .sliderRange(0.5, 2.0)
        .build()
    );

    private final Setting<Boolean> toast = sg.add(new BoolSetting.Builder()
        .name("toast")
        .description("Show a toast (like an advancement) for each notification.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatMessages = sg.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Write a line in the chat for each notification. With streamer display on in the Stash Window, nothing that could hold a position is shown.")
        .defaultValue(false)
        .build()
    );

    private final OrderWatch watch = new OrderWatch();
    private final Deque<String> queue = new ArrayDeque<>();
    private OrderSnaps.User user = OrderSnaps.User.NONE;
    private Session session;
    private long nextRequest;
    private boolean busy;
    private long busyAt;
    private int token;
    private long nextCycle;
    private int cycle;

    public OrderNotifications() {
        super(QwerteAddon.CATEGORY, "order-notifications", "Plays a sound and shows a toast when one of your Stash Manager orders is delivered, comes up short, fails or is cancelled.");
    }

    @Override
    public void onActivate() {
        stopPolling();
    }

    @Override
    public void onDeactivate() {
        stopPolling();
    }

    /** Forgets the requests in flight and the session; what was seen stays, so nothing is announced twice. */
    private void stopPolling() {
        token++;
        busy = false;
        queue.clear();
        session = null;
        user = OrderSnaps.User.NONE;
        nextCycle = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!enable.get() || mc.player == null) return;
        StashWindow w = StashWindow.get();
        Session s = w != null && w.connectToServer() && w.url() != null ? w.session() : null;
        if (s == null) {
            if (session != null) stopPolling();
            return;
        }
        long now = System.currentTimeMillis();
        if (!s.equals(session)) {
            stopPolling();
            session = s;
        }
        if (busy) {
            if (now - busyAt > WATCHDOG_MS) {
                token++;
                busy = false;
                queue.clear();
                nextCycle = now + RETRY_MS;
            }
            return;
        }
        SmClient client = w.backgroundClient();
        if (!queue.isEmpty()) {
            // At least a second between requests, so a full poll of many stashes is a trickle
            if (now >= nextRequest) {
                nextRequest = now + MIN_GAP_MS;
                fetchOrders(client, queue.poll());
            }
            return;
        }
        if (now < nextCycle) return;
        if (!user.loggedIn()) fetchAuth(client, s, now);
        else fetchStashes(client, now);
    }

    /** The wait after a 429: the server's, but never less than the normal retry gap. */
    private static long waitMs(com.porter.qwerte.manager.net.ApiResult r) {
        return Math.max(RETRY_MS, r.wait429() * 1000);
    }

    private void begin() {
        busy = true;
        busyAt = System.currentTimeMillis();
    }

    private void fetchAuth(SmClient client, Session s, long now) {
        int mine = token;
        String origin = s.origin();
        begin();
        client.get("/api/v1/auth/state", null, null, false, SmClient.DEFAULT_TIMEOUT, r -> r.body(), (r, body) -> {
            if (mine != token) return;
            busy = false;
            if (!r.ok()) {
                nextCycle = System.currentTimeMillis() + (r.status() == 429 ? waitMs(r) : RETRY_MS);
                return;
            }
            OrderSnaps.User u = OrderSnaps.User.parse(body);
            if (!u.loggedIn()) {
                nextCycle = System.currentTimeMillis() + RETRY_MS;
                return;
            }
            user = u;
            watch.setOwner(origin + "\u0000" + u.id());
            nextCycle = 0;
        });
    }

    private void fetchStashes(SmClient client, long now) {
        int mine = token;
        nextCycle = now + CYCLE_MS;
        begin();
        client.get("/api/v1/stashes", null, null, false, SmClient.DEFAULT_TIMEOUT, r -> OrderSnaps.parseStashes(r.body()), (r, stashes) -> {
            if (mine != token) return;
            busy = false;
            if (!r.ok()) {
                if (r.status() == 429) nextCycle = System.currentTimeMillis() + waitMs(r);
                return;
            }
            queue.clear();
            queue.addAll(PollPlan.scopes(stashes, watch, cycle++));
        });
    }

    private void fetchOrders(SmClient client, String scope) {
        int cut = scope.indexOf('\u0000');
        if (cut < 0) return;
        int mine = token;
        OrderSnaps.User u = user;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("server", scope.substring(0, cut));
        query.put("stash", scope.substring(cut + 1));
        begin();
        client.get("/api/v1/orders", query, null, false, SmClient.DEFAULT_TIMEOUT, r -> OrderSnaps.parseOrders(r.body(), u), (r, orders) -> {
            if (mine != token) return;
            busy = false;
            if (!r.ok()) {
                // Nothing is learned from a failed answer, so nothing is forgotten or replayed later
                if (r.status() == 429) {
                    queue.clear();
                    nextCycle = System.currentTimeMillis() + waitMs(r);
                }
                return;
            }
            // Not a valid list: learn nothing, forget nothing
            if (orders == null) return;
            // Out of a world there is nobody to tell: keep the old list, so the change is announced when you are back
            if (mc.player == null) return;
            announce(scope, watch.update(scope, orders));
        });
    }

    // ---------- telling the player ----------

    private void announce(String scope, List<OrderWatch.Notice> notices) {
        if (notices.isEmpty()) return;
        StashWindow w = StashWindow.get();
        boolean streamer = w != null && w.streamerDisplay();
        String stash = scope.substring(scope.indexOf('\u0000') + 1);

        // One sound per poll: the most serious thing that happened
        Kind loudest = null;
        for (OrderWatch.Notice n : notices) {
            if (loudest == null || n.kind().severity() > loudest.severity()) loudest = n.kind();
        }
        playFor(loudest);

        int toasts = 0;
        int chat = 0;
        for (OrderWatch.Notice n : notices) {
            NoticeText.Text text = NoticeText.of(n, stash, streamer);
            QwerteAddon.LOG.info("order-notifications: {} {}", n.kind(), n.order().id());
            if (toast.get() && toasts < MAX_TOASTS && mc.getToastManager() != null) {
                SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal(text.title()), Component.literal(text.body()));
                toasts++;
            }
            if (chatMessages.get() && chat < MAX_CHAT) {
                info("%s", text.line());
                chat++;
            }
        }
        int more = notices.size() - Math.max(toasts, chat);
        if (more > 0 && chatMessages.get() && chat >= MAX_CHAT) info("%s", "and " + more + " more order updates");
    }

    private void playFor(Kind kind) {
        List<SoundEvent> list = switch (kind) {
            case COMPLETE -> completeSound.get();
            case PARTIAL -> partialSound.get();
            case FAILED, CANCELLED -> failedSound.get();
            case ARRIVING -> arrivingSound.get();
        };
        if (list.isEmpty() || mc.player == null) return;
        float vol = volume.get() / 100f;
        if (vol <= 0) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(list.get(0), pitch.get().floatValue(), vol));
    }
}

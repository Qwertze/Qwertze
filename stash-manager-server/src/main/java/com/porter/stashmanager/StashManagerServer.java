package com.porter.stashmanager;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Entry point: java -jar stash-manager-server.jar [--data <dir>] */
public final class StashManagerServer {
    private StashManagerServer() {
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        // Drop connections that take too long to send a request or to read a response (slow clients).
        // Read once by the JDK's HttpServer, so they must be set before it is created. -D on the command line wins.
        setDefault("sun.net.httpserver.maxReqTime", "30");
        setDefault("sun.net.httpserver.maxRspTime", "60");

        Path dataDir = Path.of("stash-manager-data");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> {
                    if (i + 1 >= args.length) usage("--data needs a directory");
                    dataDir = Path.of(args[++i]);
                }
                case "--help", "-h" -> usage(null);
                default -> usage("unknown argument: " + args[i]);
            }
        }
        dataDir = dataDir.toAbsolutePath().normalize();

        int swept = AtomicFiles.sweepTemp(dataDir);
        if (swept > 0) System.out.println("Removed " + swept + " leftover temp file(s).");

        Config config;
        try {
            config = Config.load(dataDir);
            AtomicFiles.ownerOnlyDir(dataDir);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        IndexStore store = new IndexStore(dataDir);
        store.load();
        OrderStore orders = new OrderStore(dataDir, store);
        orders.load();
        Owners owners = new Owners(dataDir);
        owners.load();
        owners.adoptAll(store.players());
        boolean gameOn = !config.iconSource.equals("wiki");
        boolean wikiOn = !config.iconSource.equals("game");
        GameIcons game = new GameIcons(dataDir.resolve("icons"), config.minecraftJar, gameOn);
        // Tab icons the page shows are allowed even when no index has them.
        java.util.function.Function<String, IndexStore.IconInfo> known = id -> {
            IndexStore.IconInfo info = store.iconInfo(id);
            if (info == null && UiIcons.NAMES.containsKey(id)) info = new IndexStore.IconInfo(UiIcons.NAMES.get(id), store.anyMcVersion());
            return info;
        };
        RecipePacks recipePacks = new RecipePacks(dataDir);
        IconService icons = new IconService(dataDir, known, store::hasMcVersion, game, wikiOn);
        System.out.println("  Icon source: " + config.iconSource + (config.minecraftJar != null ? " (minecraftJar=" + config.minecraftJar + ")" : ""));
        LinkState link = new LinkState(config.keyState().generation());
        List<String> urls = urls(config);

        SessionStore sessions = new SessionStore(dataDir);
        sessions.load();
        if (config.dashboardPassword() == null && sessions.size() > 0) {
            // The password line was deleted (a reset): old logins must not survive it, sub-users' included.
            sessions.removeAllExcept(null);
        }
        Users users = new Users(dataDir);
        try {
            users.load();
        } catch (IOException e) {
            System.err.println("Can't read the website users: " + e.getMessage());
            System.exit(1);
            return;
        }
        AuditLog audit = new AuditLog(dataDir);
        audit.load();
        AuthRoutes.trustProxies(config.trustedProxies);
        if (!config.trustedProxies.isEmpty()) System.out.println("  Trusted proxies (client address from X-Forwarded-For): " + String.join(", ", config.trustedProxies));
        Auth.dummyHash(); // so the first login for an unknown name isn't slower than the rest
        BotHub hub = new BotHub();
        Routes built = routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, new LoginLimiter(), new PlanStore(),
            null, recipePacks);
        Router router = built.router();
        AuthRoutes authRoutes = built.auth();
        if (!users.list().isEmpty()) System.out.println("  Website users: the owner and " + users.list().size() + " sub-user(s).");

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(config.bind), config.port), 64);
        } catch (BindException e) {
            System.err.println("Can't listen on " + config.bind + ":" + config.port + " (" + e.getMessage() + ").");
            System.err.println("Another program (or another copy of this server) is probably using the port.");
            System.err.println("Stop it, or change port= in " + dataDir.resolve("config.properties") + ".");
            System.exit(1);
            return;
        }
        // Up to 8 bot long-polls and up to 8 live viewer streams (ViewerHub.MAX_STREAMS) hold a thread each.
        ExecutorService pool = Executors.newFixedThreadPool(32, daemonThreads());
        server.setExecutor(pool);
        // Only now that the port is ours: the constructor sweeps leftovers, which must not hit a live instance's build.
        // One pass per version builds the icon set (when the game is an icon source) and the recipe set from one jar.
        final IconPacks packs;
        {
            ClientJarFetcher fetcher = config.iconDownload || config.recipeDownload ? new ClientJarFetcher() : null;
            packs = new IconPacks(dataDir, config.minecraftJar, config.iconDownload ? fetcher : null, GameIcons.SIZE,
                30_000, 120_000);
            packs.iconsEnabled(gameOn);
            packs.attachRecipes(recipePacks, config.recipeDownload ? fetcher : null);
            if (gameOn) {
                game.attachPacks(packs);
                store.setPackItems(packs::knownItems);
                System.out.println("  Icon download: " + (config.iconDownload
                    ? "on (if no client jar is found, the official one is fetched once from piston-meta.mojang.com / piston-data.mojang.com)"
                    : "off (iconDownload=false)"));
            }
            System.out.println("  Recipe download: " + (config.recipeDownload
                ? "on (crafting recipes come from the same client jar as the icons; fetched once if none is found)"
                : "off (recipeDownload=false)"));
        }
        server.createContext("/", router);
        server.start();

        // Only once the port is ours: clean up the old icon cache, then queue every missing icon.
        int migrated = icons.migrateCache();
        if (migrated > 0) System.out.println("Icon cache: removed " + migrated + " stale file(s); they will be fetched again.");
        for (String v : store.mcVersions()) {
            recipePacks.request(v); // background: one pass per version for the icon set and the recipe set
            game.requestPack(v);
        }
        icons.prefetch(store.allIconIds());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping Stash Manager...");
            built.viewer().close();
            server.stop(1);
            icons.close();
            packs.close();
            pool.shutdown();
            try {
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }, "stash-manager-shutdown"));

        System.out.println("Stash Manager server running.");
        System.out.println("  Data directory: " + dataDir);
        System.out.println("  Loaded " + store.size() + " stash index(es).");
        for (String url : urls) System.out.println("  Open: " + url);
        printKeyNotice(System.out, config.generatedKey, config.apiKey());
        if (config.allowRemote) System.out.println("  WARNING: allowRemote=true, non-LAN addresses are not blocked.");
        if (config.allowBearer) {
            System.out.println("  WARNING: allowBearer=true: the API key is sent in clear text by old bots (Bearer). Update every bot, then set allowBearer=false.");
        } else {
            System.out.println("  Bots must sign their requests (allowBearer=false). Older addons/plugins get 401 until updated; allowBearer=true in config.properties lets them in meanwhile.");
        }
        if (authRoutes.setupCode() != null) {
            System.out.println("  No website password yet: open http://localhost:" + config.port + " on this PC to set one.");
            System.out.println("  Dashboard setup code: " + authRoutes.setupCode());
        }
    }

    /**
     * What the start-up output says about the bots' API key. The key itself is printed only on the run that generated it (the
     * output of a service ends up in a journal that more people can read than the data folder); every other start only says
     * that a key is set. The key stays in config.properties and in the website's Link addon panel.
     */
    static void printKeyNotice(java.io.PrintStream out, boolean generated, String apiKey) {
        if (generated) {
            out.println("  API key (first setup: copy it now; it won't be shown again): " + apiKey);
            out.println("  It is saved in config.properties; the website's \"Link addon\" button links a bot without typing it.");
        } else {
            out.println("  API key: set (use the website's \"Link addon\" button to link a bot)");
        }
    }

    record Routes(Router router, AuthRoutes auth, PlanStore plans, ViewerHub viewer, DeliveryContents delivery, CraftService craft) {
    }

    /** Every route, in order (the static files last). Shared with the tests, so they check the real registrations. */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter) {
        return routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, limiter, new PlanStore());
    }

    /** The same, with the build planner's plan store (tests give it their own clock). */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter,
                         PlanStore plans) {
        return routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, limiter, plans,
            new ViewerHub(hub, audit));
    }

    /** The same, with the recipe source of the crafting features (tests give it a fake; null viewer = a new one). */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter,
                         PlanStore plans, ViewerHub viewer, RecipeSource recipes) {
        return routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, limiter, plans,
            viewer == null ? new ViewerHub(hub, audit) : viewer, recipes, null);
    }

    /** The same, with the live viewer's hub (tests give it short rotate and heartbeat times). */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter,
                         PlanStore plans, ViewerHub viewer) {
        return routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, limiter, plans, viewer,
            RecipeSource.NONE, null);
    }

    /** The same with the crafting parts: the recipe source, and the craft settings folder (null: the data folder). */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter,
                         PlanStore plans, ViewerHub viewer, RecipeSource recipes, CraftBots bots) {
        return routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, limiter, plans, viewer, recipes, bots, null);
    }

    /** The same with the area reports (tests give it their own clock). */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter,
                         PlanStore plans, ViewerHub viewer, RecipeSource recipes, CraftBots bots, AreaBots areaBotsIn) {
        return routes(config, store, orders, owners, icons, link, urls, sessions, users, audit, hub, limiter, plans, viewer, recipes, bots,
            areaBotsIn, null);
    }

    /** The same with the kit reports (tests give them their own clock; null: a new one). */
    static Routes routes(Config config, IndexStore store, OrderStore orders, Owners owners, IconService icons, LinkState link,
                         List<String> urls, SessionStore sessions, Users users, AuditLog audit, BotHub hub, LoginLimiter limiter,
                         PlanStore plans, ViewerHub viewer, RecipeSource recipes, CraftBots bots, AreaBots areaBotsIn, KitReports kitReportsIn) {
        // A user's plans end with their last session, whatever ended it (logout, expiry, a reset).
        plans.sessionCheck(id -> sessions.countByUser().getOrDefault(id, 0) > 0);
        Router router = new Router(new AccessControl(config), sessions, config, users);
        router.ids(new ServerIds(ServerIds.secret(config.dataDir()), store::servers,
            () -> hub.all().stream().map(b -> b.botId).toList()));
        AuthRoutes authRoutes = new AuthRoutes(config, sessions, limiter, users, audit);
        authRoutes.ids(router.ids());
        authRoutes.onSessionsEnded(plans::pruneNow);
        authRoutes.register(router);
        DeliveryContents delivery = new DeliveryContents(); // in memory only
        KitStore kits = new KitStore(config.dataDir());
        kits.load();
        KitReports kitReports = kitReportsIn != null ? kitReportsIn : new KitReports(); // in memory only
        CraftStore craftStore = new CraftStore(config.dataDir());
        craftStore.load();
        CraftService craft = new CraftService(craftStore, bots == null ? new CraftBots() : bots, recipes, store);
        AreaStore areaStore = new AreaStore(config.dataDir());
        areaStore.load();
        AreaBots areaBots = areaBotsIn != null ? areaBotsIn : new AreaBots();
        craft.areas(areaStore);
        orders.craft(craft);
        craft.kits(kits);
        orders.kits(new KitOrders(kits, kitReports));
        Redact.kitNumbers = (srv, st, id) -> {
            try {
                return new java.util.ArrayList<>(kits.get(srv, st).types().templates().keySet()).indexOf(id) + 1;
            } catch (RuntimeException e) {
                return 0;
            }
        };
        RepairStore repairStore = new RepairStore(config.dataDir());
        repairStore.load();
        RepairBots repairBots = new RepairBots(); // in memory only
        store.onForget((srv, stashName) -> {
            try {
                repairStore.forget(srv, stashName);
            } catch (IOException e) {
                System.err.println("Could not drop the repair settings of a forgotten stash: " + e.getMessage());
            }
            repairBots.forget(srv, stashName);
            try {
                craftStore.forget(srv, stashName);
            } catch (IOException e) {
                System.err.println("Could not drop the craft settings of a forgotten stash: " + e.getMessage());
            }
            craft.bots().forget(srv, stashName);
            try {
                areaStore.forget(srv, stashName);
            } catch (IOException e) {
                System.err.println("Could not drop the area of a forgotten stash: " + e.getMessage());
            }
            areaBots.forget(srv, stashName);
        });
        AreaGuard areaGuard = new AreaGuard(areaStore, store, kitReports);
        RepairRoutes repairRoutes = new RepairRoutes(repairStore, repairBots, kitReports, kits, orders, store, owners, audit);
        AreaRoutes areaRoutes = new AreaRoutes(areaStore, areaBots, store, audit, areaGuard, kitReports, sessions);
        AddonRoutes addonRoutes = new AddonRoutes(store, link, icons, orders, owners, delivery, kits, kitReports, craft, areaStore, areaBots, audit, areaGuard);
        addonRoutes.repair(repairStore, repairBots);
        addonRoutes.register(router);
        BrowserRoutes browserRoutes = new BrowserRoutes(store, config, link, urls, orders, owners, sessions, audit, delivery, kits, kitReports, craft);
        browserRoutes.areas(areaRoutes);
        browserRoutes.repair(repairRoutes);
        browserRoutes.register(router);
        areaRoutes.register(router);
        new CraftRoutes(craft, orders, audit, store).register(router);
        KitRoutes kitRoutes = new KitRoutes(kits, kitReports, owners, audit, store);
        kitRoutes.repair(repairStore);
        kitRoutes.register(router);
        repairRoutes.register(router);
        new DeliveryRoutes(delivery, owners).register(router);
        new OrderRoutes(orders, owners, audit).register(router);
        new BotRoutes(hub).register(router);
        new DashboardRoutes(hub, store, owners, sessions, audit).register(router);
        new UserRoutes(users, sessions, audit, plans).register(router);
        new PlannerRoutes(store, orders, plans, audit, craft).register(router);
        new ViewerRoutes(viewer).register(router);
        new IconRoutes(icons).register(router);
        new StaticRoutes().register(router); // last: the catch-all
        return new Routes(router, authRoutes, plans, viewer, delivery, craft);
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    private static void usage(String error) {
        if (error != null) System.err.println(error);
        System.err.println("Usage: java -jar stash-manager-server.jar [--data <dir>]");
        System.exit(error == null ? 0 : 2);
    }

    /** URLs the server can be reached at: localhost plus each LAN IPv4 address (or just the bind address). */
    private static List<String> urls(Config config) {
        List<String> urls = new ArrayList<>();
        boolean any = config.bind.equals("0.0.0.0") || config.bind.equals("::") || config.bind.equals("[::]");
        if (!any) {
            String host = config.bind.contains(":") ? "[" + config.bind + "]" : config.bind;
            urls.add("http://" + host + ":" + config.port);
            return urls;
        }
        urls.add("http://localhost:" + config.port);
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        urls.add("http://" + addr.getHostAddress() + ":" + config.port);
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return urls;
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "stash-manager-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

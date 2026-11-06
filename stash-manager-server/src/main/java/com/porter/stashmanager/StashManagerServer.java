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
        IconService icons = new IconService(dataDir, known, store::hasMcVersion, game, wikiOn);
        System.out.println("  Icon source: " + config.iconSource + (config.minecraftJar != null ? " (minecraftJar=" + config.minecraftJar + ")" : ""));
        LinkState link = new LinkState(config.keyState().generation());
        List<String> urls = urls(config);

        SessionStore sessions = new SessionStore(dataDir);
        sessions.load();
        if (config.dashboardPassword() == null && sessions.size() > 0) {
            // The password line was deleted (a reset): old logins must not survive it.
            sessions.removeAllExcept(null);
        }
        Router router = new Router(new AccessControl(config), sessions, config);
        AuthRoutes authRoutes = new AuthRoutes(config, sessions, new LoginLimiter());
        authRoutes.register(router);
        new AddonRoutes(store, link, icons, orders, owners).register(router);
        new BrowserRoutes(store, config, link, urls, orders, owners, sessions).register(router);
        new OrderRoutes(orders, owners).register(router);
        BotHub hub = new BotHub();
        new BotRoutes(hub).register(router);
        new DashboardRoutes(hub, store, owners, sessions).register(router);
        new IconRoutes(icons).register(router);
        new StaticRoutes().register(router); // last: the catch-all

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
        ExecutorService pool = Executors.newFixedThreadPool(32, daemonThreads()) /* up to 8 bot long-polls wait at once */;
        server.setExecutor(pool);
        server.createContext("/", router);
        server.start();

        // Only once the port is ours: clean up the old icon cache, then queue every missing icon.
        int migrated = icons.migrateCache();
        if (migrated > 0) System.out.println("Icon cache: removed " + migrated + " stale file(s); they will be fetched again.");
        icons.prefetch(store.allIconIds());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping Stash Manager...");
            server.stop(1);
            icons.close();
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
        System.out.println("  Link the addon from the website's \"Link addon\" button, or paste this API key");
        System.out.println("  into the stash-manager module's api-key setting:");
        System.out.println("    " + config.apiKey());
        if (config.generatedKey) System.out.println("  (new key generated and saved in config.properties)");
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

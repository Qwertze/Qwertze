package com.porter.stashmanager;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Settings from config.properties in the data directory. The file is created with comments on first run. */
public final class Config {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*apiKey\\s*[=:].*$");
    private static final String PASSWORD_KEY = "dashboardPassword";

    /** The current API key. generation goes up by one each time the key is regenerated. */
    public record KeyState(String key, byte[] bytes, long generation, byte[] signingKey) {
        KeyState(String key, long generation) {
            this(key, key.getBytes(StandardCharsets.UTF_8), generation, Signer.deriveKey(key));
        }
    }

    public final int port;
    public final String bind;
    public final boolean allowRemote;
    public final List<String> allowedHosts;
    public final boolean generatedKey;
    /** minecraftJar=: a client jar or a launcher/.minecraft folder; null = find it automatically. */
    public final Path minecraftJar;
    /** iconSource=: auto (game jar, then the wiki), game, or wiki. */
    public final String iconSource;
    /** iconDownload=: download the official client jar once to render the full icon set when none is found locally. */
    public boolean iconDownload = true;
    /** allowBearer=: accept the old Authorization: Bearer header on index/heartbeat/order requests (never on bot/*). */
    public final boolean allowBearer;
    /**
     * trustedProxies=: addresses of reverse proxies / tunnels (e.g. cloudflared) in front of this server. Only for a
     * request whose socket address is one of these is the client address taken from the last
     * X-Forwarded-For entry, for the login rate limit and the audit log. Empty: headers are never trusted.
     */
    public final List<String> trustedProxies;
    /** The key before the last regenerate, accepted for 10 minutes on non-dashboard requests. */
    private volatile KeyState previousKey;
    private volatile long previousUntil;
    public static final long PREVIOUS_KEY_MS = 10 * 60_000;
    private final Path file;
    private volatile KeyState key;
    /** dashboardPassword=: the website password hash (see Auth), or null before the first-run setup. */
    private volatile String dashboardPassword;

    private Config(Path file, int port, String bind, String apiKey, boolean allowRemote, List<String> allowedHosts,
                   boolean generatedKey, Path minecraftJar, String iconSource, boolean allowBearer, List<String> trustedProxies) {
        this.allowBearer = allowBearer;
        this.trustedProxies = List.copyOf(trustedProxies);
        this.file = file;
        this.minecraftJar = minecraftJar;
        this.iconSource = iconSource;
        this.port = port;
        this.bind = bind;
        this.key = new KeyState(apiKey, 0);
        this.allowRemote = allowRemote;
        this.allowedHosts = List.copyOf(allowedHosts);
        this.generatedKey = generatedKey;
    }

    public String apiKey() {
        return key.key();
    }

    /** The data folder (where config.properties lives). */
    public Path dataDir() {
        return file.toAbsolutePath().getParent();
    }

    public KeyState keyState() {
        return key;
    }

    /** The previous key while it is still accepted (10 minutes after a regenerate), else null. */
    public KeyState previousKey() {
        return System.currentTimeMillis() < previousUntil ? previousKey : null;
    }

    /** The stored password hash, or null when none is set (the website then asks for the first-run setup). */
    public String dashboardPassword() {
        return dashboardPassword;
    }

    /** Saves a new password hash in its own line of config.properties (other lines and comments are kept). */
    public synchronized void setDashboardPassword(String encoded) throws IOException {
        writeLine(file, Pattern.compile("^\\s*" + PASSWORD_KEY + "\\s*[=:].*$"), PASSWORD_KEY, encoded);
        dashboardPassword = encoded;
    }

    public static Config load(Path dataDir) throws IOException {
        Path file = dataDir.resolve("config.properties");
        Properties props = new Properties();
        boolean existed = Files.exists(file);
        if (existed) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            AtomicFiles.ownerOnly(file);
        }

        int port = parsePort(props.getProperty("port", "8765").trim());
        String bind = props.getProperty("bind", "0.0.0.0").trim();
        if (bind.isEmpty()) bind = "0.0.0.0";
        String apiKey = props.getProperty("apiKey", "").trim();
        boolean allowRemote = Boolean.parseBoolean(props.getProperty("allowRemote", "false").trim());
        List<String> allowedHosts = new ArrayList<>();
        for (String host : props.getProperty("allowedHosts", "").split(",")) {
            String h = host.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty()) allowedHosts.add(h);
        }

        Path minecraftJar = null;
        String jarText = props.getProperty("minecraftJar", "").trim();
        if (!jarText.isEmpty()) {
            try {
                minecraftJar = Path.of(jarText);
            } catch (InvalidPathException e) {
                System.err.println("config.properties: ignoring minecraftJar, not a valid path: " + jarText);
            }
        }
        String iconSource = props.getProperty("iconSource", "auto").trim().toLowerCase(Locale.ROOT);
        if (iconSource.isEmpty()) iconSource = "auto";
        if (!iconSource.equals("auto") && !iconSource.equals("game") && !iconSource.equals("wiki")) {
            System.err.println("config.properties: iconSource must be auto, game or wiki, got '" + iconSource + "'. Using auto.");
            iconSource = "auto";
        }

        boolean generated = false;
        if (apiKey.isEmpty()) {
            apiKey = generateKey();
            generated = true;
        }

        if (!existed) {
            writeTemplate(file, port, bind, apiKey, allowRemote, String.join(",", allowedHosts));
        } else {
            if (generated) writeKeyLine(file, apiKey);
            appendMissing(file, props);
        }
        boolean allowBearer = Boolean.parseBoolean(props.getProperty("allowBearer", "false").trim());
        List<String> trustedProxies = new ArrayList<>();
        for (String a : props.getProperty("trustedProxies", "").split(",")) {
            String t = a.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (!AuthRoutes.isIpLiteral(t)) {
                System.err.println("config.properties: ignoring trustedProxies entry '" + t + "' (an IP address is needed)");
                continue;
            }
            trustedProxies.add(t);
        }
        Config config = new Config(file, port, bind, apiKey, allowRemote, allowedHosts, generated, minecraftJar, iconSource, allowBearer,
            trustedProxies);
        config.iconDownload = parseIconDownload(props.getProperty("iconDownload"));
        String password = props.getProperty(PASSWORD_KEY, "").trim();
        if (!password.isEmpty()) {
            if (!Auth.wellFormed(password)) {
                System.err.println("config.properties: dashboardPassword is not a valid password hash, so nobody can log in."
                    + " Delete that line and restart to set a new password.");
            }
            config.dashboardPassword = password;
        }
        return config;
    }

    /** Absent = true (the default); "true"/"false" exactly; anything else warns and means false (no download). */
    static boolean parseIconDownload(String raw) {
        if (raw == null) return true;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        System.err.println("config.properties: iconDownload must be true or false, got '" + clean(v) + "'. Treating it as false (no download).");
        return false;
    }

    /** Makes a new key, saves it, and only then makes it the live key, so the old key stops working at once. */
    public synchronized String regenerateKey() throws IOException {
        String next = generateKey();
        writeKeyLine(file, next);
        previousKey = key;
        previousUntil = System.currentTimeMillis() + PREVIOUS_KEY_MS;
        key = new KeyState(next, key.generation() + 1);
        return next;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("config.properties: port must be a number from 1 to 65535, got '" + value + "'");
    }

    private static String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Replaces the apiKey line of an existing file, keeping the user's other lines and comments. */
    private static void writeKeyLine(Path file, String apiKey) throws IOException {
        writeLine(file, KEY_LINE, "apiKey", apiKey);
    }

    /**
     * Replaces the first line matching {@code pattern} with name=value (appending it when there is none), drops any
     * later duplicates (the last one would win in Properties.load), keeps every other line, and writes atomically
     * with owner-only permissions.
     */
    private static void writeLine(Path file, Pattern pattern, String name, String value) throws IOException {
        String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (String line : new java.io.BufferedReader(new StringReader(text)).lines().toList()) {
            if (pattern.matcher(line).matches()) {
                if (!replaced) {
                    out.append(name).append('=').append(clean(value)).append('\n');
                    replaced = true;
                }
            } else {
                out.append(line).append('\n');
            }
        }
        if (!replaced) out.append(name).append('=').append(clean(value)).append('\n');
        AtomicFiles.write(file, out.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private static final String JAR_BLOCK = """
        # Minecraft client jar for rendering item icons from the game's own files. Leave empty to find it
        # automatically (Prism Launcher libraries and .minecraft/versions). Can also be a launcher or
        # .minecraft folder. Use forward slashes (C:/Users/...) or double backslashes on Windows.
        minecraftJar=
        """;
    private static final String BEARER_BLOCK = """
        # Bots sign every request with the API key (the key itself is never sent). true also accepts the old
        # "Authorization: Bearer <key>" header from older addons/plugins on index, heartbeat and order requests
        # (never on the dashboard). The key then crosses the network in clear text: update every bot and set false.
        allowBearer=false
        """;
    private static final String PROXY_BLOCK = """
        # IP addresses (comma separated) of a reverse proxy or tunnel in front of this server, such as the machine
        # running cloudflared. Requests from exactly these addresses have their real client address read from
        # the last X-Forwarded-For entry for the login rate limit and the audit log. Leave empty otherwise.
        trustedProxies=
        """;
    private static final String SOURCE_BLOCK = """
        # Where item icons come from: auto (the game jar, then the Minecraft Wiki for what it can't draw),
        # game (the game jar only), or wiki (the Minecraft Wiki only).
        iconSource=auto
        """;
    private static final String DOWNLOAD_BLOCK = """
        # true: when no Minecraft client jar is found on this machine, download the official one ONCE from Mojang
        # (piston-meta.mojang.com / piston-data.mojang.com, sha1 verified), render every item icon into
        # <data>/icons/game-<version>/, then delete the jar. false: never download (local jars only).
        iconDownload=true
        """;

    /** Adds settings introduced after the file was created, with their comments, leaving every existing line alone. */
    private static void appendMissing(Path file, Properties props) throws IOException {
        StringBuilder add = new StringBuilder();
        if (!props.containsKey("minecraftJar")) add.append('\n').append(JAR_BLOCK);
        if (!props.containsKey("iconSource")) add.append('\n').append(SOURCE_BLOCK);
        if (!props.containsKey("iconDownload")) add.append('\n').append(DOWNLOAD_BLOCK);
        if (!props.containsKey("allowBearer")) add.append('\n').append(BEARER_BLOCK);
        if (!props.containsKey("trustedProxies")) add.append('\n').append(PROXY_BLOCK);
        if (add.isEmpty()) return;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";
        AtomicFiles.write(file, (text + add).getBytes(StandardCharsets.UTF_8), true);
    }

    private static void writeTemplate(Path file, int port, String bind, String apiKey, boolean allowRemote, String allowedHosts) throws IOException {
        String text = ("""
            # Stash Manager server settings. Restart the server after editing.

            # TCP port the web server listens on.
            port=%d

            # Address to bind to. 0.0.0.0 listens on every network interface.
            bind=%s

            # Key the Qwerte addon's stash-manager module must send (Authorization: Bearer <key>).
            # The website's "Link addon" panel shows it and can make a new one.
            # You can also delete this line and restart to make a new key.
            apiKey=%s

            # By default only loopback and private/LAN addresses may connect.
            # true turns that check off. Do not do this unless you know why you need it.
            allowRemote=%s

            # Extra host names (comma separated) the browser may use to reach this server,
            # e.g. a DNS name on your LAN. IP addresses, localhost and this machine's name always work.
            allowedHosts=%s
            """ + "\n" + JAR_BLOCK + "\n" + SOURCE_BLOCK + "\n" + DOWNLOAD_BLOCK + "\n" + BEARER_BLOCK + "\n" + PROXY_BLOCK).formatted(port, clean(bind), clean(apiKey), allowRemote, clean(allowedHosts));
        AtomicFiles.write(file, text.getBytes(StandardCharsets.UTF_8), true);
    }

    /** Keeps values safe for the properties format (they are expected to be plain ASCII anyway). */
    private static String clean(String value) {
        return value.replace("\\", "\\\\").replace("\n", "").replace("\r", "");
    }
}

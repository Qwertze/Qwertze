package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Downloads the official Mojang client jar of one Minecraft version: version manifest v2, then the per-version
 * JSON, then {@code downloads.client.url}, checked against {@code downloads.client.sha1}. https only, to
 * piston-meta.mojang.com and piston-data.mojang.com; redirects are followed by hand (max 3), each hop checked
 * against the same allowlist. Size caps apply to every response. The jar is written to a temp file next to the
 * target and moved into place only after the hash matched.
 */
class ClientJarFetcher {
    /** A failure that retrying will not fix (version not listed, no client download, bad entry). */
    static class PermanentException extends IOException {
        PermanentException(String m) {
            super(m);
        }
    }

    static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final long MAX_JSON = 8L * 1024 * 1024;
    static final long MAX_JAR = 200L * 1024 * 1024;
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Duration CONNECT = Duration.ofSeconds(15);
    private static final Duration READ = Duration.ofMinutes(5);
    private static final int MAX_REDIRECTS = 3;

    private final String manifestUrl;
    private final Predicate<URI> allowed;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT).build();

    ClientJarFetcher() {
        this(MANIFEST_URL, ClientJarFetcher::allowedTarget);
    }

    /** For tests: another manifest URL and host check. */
    ClientJarFetcher(String manifestUrl, Predicate<URI> allowed) {
        this.manifestUrl = manifestUrl;
        this.allowed = allowed;
    }

    static boolean allowedTarget(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("piston-meta.mojang.com") || host.equals("piston-data.mojang.com");
    }

    /** Downloads the client jar for the version to {@code target}. Throws when anything is off; nothing partial is left. */
    void fetch(String mcVersion, Path target) throws IOException, InterruptedException {
        if (!IconService.MC_VERSION.matcher(mcVersion).matches()) throw new PermanentException("bad version " + mcVersion);
        String versionUrl = versionUrl(mcVersion);
        JsonObject meta = json(versionUrl);
        JsonObject client = meta.has("downloads") && meta.get("downloads").isJsonObject()
            && meta.getAsJsonObject("downloads").has("client") && meta.getAsJsonObject("downloads").get("client").isJsonObject()
            ? meta.getAsJsonObject("downloads").getAsJsonObject("client") : null;
        if (client == null) throw new PermanentException("version " + mcVersion + " has no client download");
        String url = str(client, "url");
        String sha1 = str(client, "sha1");
        if (url == null || sha1 == null || !SHA1.matcher(sha1.toLowerCase(Locale.ROOT)).matches()) throw new PermanentException("client download entry is incomplete");
        long declared = client.has("size") && client.get("size").isJsonPrimitive() ? client.get("size").getAsLong() : -1;
        if (declared > MAX_JAR) throw new IOException("client jar is larger than " + MAX_JAR + " bytes");

        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "client", ".part");
        try {
            String got = download(url, tmp, MAX_JAR);
            if (!got.equals(sha1.toLowerCase(Locale.ROOT))) throw new IOException("client jar sha1 mismatch");
            if (declared >= 0 && Files.size(tmp) != declared) throw new IOException("client jar size mismatch");
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static final long MANIFEST_TTL_MS = 5 * 60 * 1000L;
    private JsonObject cachedManifest;
    private long cachedAt;

    private synchronized JsonObject manifest() throws IOException, InterruptedException {
        if (cachedManifest != null && System.currentTimeMillis() - cachedAt < MANIFEST_TTL_MS) return cachedManifest;
        cachedManifest = json(manifestUrl);
        cachedAt = System.currentTimeMillis();
        return cachedManifest;
    }

    /** The per-version JSON URL from the (cached) manifest; PermanentException when Mojang does not list the version. */
    private String versionUrl(String mcVersion) throws IOException, InterruptedException {
        JsonObject manifest = manifest();
        if (manifest.get("versions") != null && manifest.get("versions").isJsonArray()) {
            for (JsonElement e : manifest.getAsJsonArray("versions")) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                if (mcVersion.equals(str(v, "id")) && str(v, "url") != null) return str(v, "url");
            }
        }
        throw new PermanentException("Mojang's version manifest has no version " + mcVersion);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private JsonObject json(String url) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        open(url, (in) -> {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_JSON) throw new IOException("response too large");
                out.write(buf, 0, n);
            }
            return null;
        });
        try {
            return JsonParser.parseString(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("not JSON from " + hostOf(url));
        }
    }

    /** Streams into file with a size cap and returns the lowercase hex SHA-1. */
    private String download(String url, Path file, long max) throws IOException, InterruptedException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        open(url, in -> {
            try (var os = Files.newOutputStream(file)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > max) throw new IOException("download larger than " + max + " bytes");
                    sha.update(buf, 0, n);
                    os.write(buf, 0, n);
                }
            }
            return null;
        });
        return HexFormat.of().formatHex(sha.digest());
    }

    private interface Reader {
        Object read(InputStream in) throws IOException;
    }

    private void open(String url, Reader reader) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("bad URL");
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowed.test(uri)) throw new IOException("refused " + uri.getScheme() + "://" + uri.getHost());
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(READ).header("User-Agent", "QwerteStashManager/1.0").GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                int status = resp.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String loc = resp.headers().firstValue("Location").orElse(null);
                    if (loc == null) throw new IOException("redirect without Location");
                    uri = uri.resolve(loc.trim());
                    continue;
                }
                if (status != 200) throw new IOException("HTTP " + status + " from " + uri.getHost());
                reader.read(in);
                return;
            }
        }
        throw new IOException("too many redirects");
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}

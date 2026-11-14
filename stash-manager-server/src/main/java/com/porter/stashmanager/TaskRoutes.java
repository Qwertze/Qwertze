package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bot side of tasks (TASK-CONTRACT v1.0 slice): POST /api/v1/tasks/sync, /api/v1/tasks/{id}/claim and
 * /api/v1/tasks/{id}/report. All three are strict bot routes ({@link Router.Guard#ADDON_KEY}: signed requests, current
 * key, 1 MiB at most) and take at most 64 KiB. Every body goes through the coordinate guard (G1, G2) before a field is read;
 * a refusal never repeats what was sent and nothing about a body is logged.
 */
final class TaskRoutes {
    static final Pattern CLAIM = Pattern.compile("^/api/v1/tasks/(t-[0-9a-f]{12})/claim$");
    static final Pattern REPORT = Pattern.compile("^/api/v1/tasks/(t-[0-9a-f]{12})/report$");

    private final TaskService svc;

    TaskRoutes(TaskService svc) {
        this.svc = svc;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/tasks/sync", Router.Guard.ADDON_KEY, this::sync)
            .pattern(Router.POST, CLAIM, Router.Guard.ADDON_KEY, this::claim)
            .pattern(Router.POST, REPORT, Router.Guard.ADDON_KEY, this::report);
    }

    /** The parsed body (at most 64 KiB, a JSON object, clean of coordinates), or null after answering. */
    static JsonObject body(HttpExchange ex, long max) throws IOException {
        byte[] bytes = Http.readBody(ex, max); // 413 above the limit
        JsonElement json;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            json = JsonParser.parseString(text);
        } catch (CharacterCodingException | JsonParseException | StackOverflowError e) {
            json = null;
        }
        if (json == null || !json.isJsonObject()) {
            answer(ex, new Fail(400, "bad_field", "body"));
            return null;
        }
        PositionGuard.Hit hit = PositionGuard.check(json);
        if (hit != null) {
            answer(ex, new Fail(400, hit.code(), hit.key()));
            return null;
        }
        return json.getAsJsonObject();
    }

    static void answer(HttpExchange ex, Fail f) throws IOException {
        if (f.retryAfter > 0) ex.getResponseHeaders().set("Retry-After", Long.toString(f.retryAfter));
        Http.json(ex, f.status, f.toJson());
    }

    private static String headerBot(HttpExchange ex) {
        Signer.Auth a = Signer.Auth.parse(ex.getRequestHeaders().getFirst(Signer.REQUEST_HEADER));
        return a == null ? null : a.botId();
    }

    private void respond(HttpExchange ex, Supplier<JsonObject> work) throws IOException {
        JsonObject out;
        try {
            out = work.get();
        } catch (Fail f) {
            answer(ex, f);
            return;
        } catch (java.io.UncheckedIOException e) {
            // The server could not write its state: the bot retries (a report repeats its seq, which is then a no-op).
            answer(ex, new Fail(503, "storage_error"));
            return;
        }
        Http.json(ex, 200, out);
    }

    private void sync(HttpExchange ex) throws IOException {
        JsonObject o = body(ex, TaskService.MAX_BODY);
        if (o == null) return;
        String address = AuthRoutes.address(ex);
        String hb = headerBot(ex);
        respond(ex, () -> svc.sync(o, hb, address));
    }

    private void claim(HttpExchange ex) throws IOException {
        Matcher m = CLAIM.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        JsonObject o = body(ex, TaskService.MAX_BODY);
        if (o == null) return;
        String hb = headerBot(ex);
        respond(ex, () -> svc.claim(m.group(1), o, hb));
    }

    private void report(HttpExchange ex) throws IOException {
        Matcher m = REPORT.matcher(ex.getRequestURI().getRawPath());
        if (!m.matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        JsonObject o = body(ex, TaskService.MAX_BODY);
        if (o == null) return;
        String hb = headerBot(ex);
        String address = AuthRoutes.address(ex);
        respond(ex, () -> svc.report(m.group(1), o, hb, address));
    }
}

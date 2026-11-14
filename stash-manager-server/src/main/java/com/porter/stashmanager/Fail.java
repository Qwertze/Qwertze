package com.porter.stashmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.regex.Pattern;

/**
 * A refusal of the task and farm routes: an HTTP status, a machine {@code code} and optionally the name of the field. The
 * text is fixed per code and never repeats what the caller sent (FARM-CONTRACT E5: no echo of rejected values).
 */
final class Fail extends RuntimeException {
    final int status;
    final String code;
    final String field;
    final long retryAfter;
    final JsonObject extra;

    Fail(int status, String code) {
        this(status, code, null, 0, null);
    }

    Fail(int status, String code, String field) {
        this(status, code, field, 0, null);
    }

    Fail(int status, String code, String field, long retryAfter, JsonObject extra) {
        super(code, null, false, false); // no stack trace: it is control flow
        this.status = status;
        this.code = code;
        this.field = field;
        this.retryAfter = retryAfter;
        this.extra = extra;
    }

    static Fail badField(String field) {
        return new Fail(400, "bad_field", field);
    }

    static Fail badVocab(String field) {
        return new Fail(400, "bad_vocab", field);
    }

    /** The fixed text of a code. */
    static String text(String code) {
        return switch (code) {
            case "position_field", "position_like" -> "a coordinate is not allowed here";
            case "bad_field" -> "a field is missing or has a wrong value";
            case "bad_vocab" -> "a field has a value outside its closed list";
            case "reserved_field" -> "that field is reserved";
            case "botid_mismatch" -> "the botId in the header and the body must match";
            case "no_task" -> "no such task";
            case "no_farm" -> "no such farm";
            case "unknown_bot" -> "no such bot";
            case "rev_conflict" -> "the farm changed since you read it";
            case "name_in_use" -> "another farm has that name";
            case "session_open" -> "a session is open for this farm";
            case "no_session" -> "no session is open for this farm";
            case "no_draft" -> "the farmer holds no draft program";
            case "no_program" -> "the farm has no program yet";
            case "no_farmer" -> "no bot has the farmer role";
            case "farmer_offline" -> "the farmer is not connected";
            case "stopped" -> "a STOP applies to that bot";
            case "busy" -> "a session is already open for this farm";
            case "farmer_busy" -> "the farmer has another task";
            case "disabled" -> "the farm is switched off";
            case "mode_not_ready" -> "this mode can't be run yet";
            case "program_mode_mismatch" -> "the program was recorded for another mode";
            case "taken" -> "another bot holds the task";
            case "not_target" -> "the task is not for that bot";
            case "lease_lost" -> "the lease is gone; drop the task";
            case "bad_transition" -> "that state change is not allowed";
            case "not_claimable" -> "the task can't be claimed";
            case "slow_down" -> "too many requests; slow down";
            case "farms_unavailable" -> "the farm records can't be read";
            case "storage_error" -> "the change could not be saved and was not applied; try again";
            case "farm_limit" -> "too many farms";
            case "farmer" -> "a farmer bot sends no live viewer frames";
            case "body_too_large" -> "body too large";
            default -> "refused";
        };
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", text(code));
        o.addProperty("code", code);
        if (field != null) o.addProperty("field", field);
        if (extra != null) extra.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return o;
    }

    // ---------- field readers (every refusal names the field, never the value) ----------

    /** A JSON integer literal (no decimal point or exponent, not a string) in [min, max], else bad_field(field). */
    static long integer(JsonElement e, String field, long min, long max) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) throw badField(field);
        String s = p.getAsString();
        if (!INTEGER.matcher(s).matches()) throw badField(field);
        long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException ex) {
            throw badField(field);
        }
        if (v < min || v > max) throw badField(field);
        return v;
    }

    private static final Pattern INTEGER = Pattern.compile("^-?\\d{1,18}$");

    /** The integer if e is one in range, else null (used where a bad value only drops a field). */
    static Long integerOrNull(JsonElement e, long min, long max) {
        try {
            return integer(e, "x", min, max);
        } catch (Fail f) {
            return null;
        }
    }

    static boolean present(JsonObject o, String f) {
        return o.has(f) && !o.get(f).isJsonNull();
    }

    /** A string field matching the pattern (and length), else bad_field(field). */
    static String string(JsonElement e, String field, Pattern p, int maxLen) {
        if (!(e instanceof JsonPrimitive s) || !s.isString()) throw badField(field);
        String v = s.getAsString();
        if (v.length() > maxLen || (p != null && !p.matcher(v).matches())) throw badField(field);
        return v;
    }

    static boolean bool(JsonElement e, String field) {
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) throw badField(field);
        return p.getAsBoolean();
    }

    static JsonObject object(JsonElement e, String field) {
        if (e == null || !e.isJsonObject()) throw badField(field);
        return e.getAsJsonObject();
    }
}

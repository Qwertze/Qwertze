// Stash Manager: the Farms page, the STOP bar and the bot role controls (server 1.5.0).
// Loaded after app.js, which offers the small window.StashHost it builds on. Everything the server sends is rendered
// with textContent only, never as HTML. Farms, farm sessions, roles and STOP are the owner's: a non-owner never
// gets a farm name or task from the server, and this file only asks for /api/v1/ops for them (a read-only indicator).
// No coordinate is asked for or shown anywhere on this page.
(function () {
  "use strict";

  var H = window.StashHost;
  if (!H) return;
  var str = H.str, num = H.num, line = H.line;

  var OPS_POLL_MS = 10000;
  var OPS_FAST_MS = 3000;
  var FARMS_POLL_MS = 5000;
  var M2_MODES = ["custom", "afk_still"]; // what this version runs; the other modes come in the next version

  // ---------- words ----------

  var MODE_NAMES = {
    custom: "Custom (recorded)", afk_still: "AFK, standing still", afk_swing: "AFK, swinging", afk_use: "AFK, using an item",
    hold_use: "Holding use", fishing: "Fishing", raid: "Raid", gold_iron: "Gold and iron", wither_shulker: "Wither and shulker",
    trading: "Trading", crop_replant: "Crop replant"
  };
  var MODE_ORDER = ["custom", "afk_still", "afk_swing", "afk_use", "hold_use", "fishing", "raid", "gold_iron", "wither_shulker", "trading", "crop_replant"];

  var STATE_WORDS = {
    idle: "Idle", pending: "Waiting for the farmer", running: "Running", paused: "Paused", completed: "Finished",
    failed: "Failed", cancelled: "Stopped"
  };
  var PHASE_WORDS = {
    setup: "getting ready", loop: "running the loop", recover: "getting back on the route", stop_steps: "finishing up",
    parked: "parked", waiting: "waiting"
  };
  var CODE_WORDS = {
    time_up: "Ran for the whole time limit", stopped: "Stopped by the owner", ops_stop: "Emergency STOP",
    pause_timeout: "Paused for too long, so it was ended", offer_timeout: "The farmer did not pick the session up in time",
    role_removed: "The farmer role was taken away", player_near: "A player came too close",
    low_health: "Health got too low", no_food: "Out of food", no_totem: "No totem in the off hand",
    drifted: "Drifted off the route and could not recover", farm_changed: "The farm was changed during the session",
    inventory_full: "The inventory got full", item_missing: "A needed item was missing", not_at_farm: "The bot was not at the farm",
    wrong_dim: "The bot was in the wrong dimension", chunk_not_loaded: "The farm's chunks were not loaded",
    module_conflict: "Another module was in the way", guard_off: "A safety module (AutoTotem, AutoEat or AutoDisconnect) was off",
    unsafe_logging: "Refused to start: debug logging is on", unsafe_discord: "Refused to start: the Discord setup is unsafe",
    unsafe_bridge: "Refused to start: the bridge setup is unsafe", unsafe_rotation: "Refused to start: rotation setting is unsafe",
    unsafe_viewer: "Refused to start: the live viewer is on", key_missing: "The farmer has no key for its programs",
    key_mismatch: "The farmer's program key does not match", key_invalid: "The farmer's program key is not valid",
    key_perms: "The farmer's key file has the wrong permissions", mode_not_ready: "This mode arrives in the next version",
    program_missing: "The farmer has no program for this farm", program_mismatch: "The farmer's program differs from the farm's",
    error: "The farmer hit an error", controller_connected: "Paused: a player is connected to the bot",
    disconnected: "Paused: the bot is disconnected", pathfinder_busy: "Paused: the pathfinder was busy",
    server_unreachable: "Paused: the manager server was unreachable", lease_lost: "Lost contact with the server"
  };

  function codeWords(code) {
    code = str(code);
    if (!code) return "";
    return CODE_WORDS[code] || "stopped (" + code + ")";
  }

  var DELIVERY_WORDS = {
    sent: "Sent, waiting for the bot to confirm",
    delivered: "Delivered: the bot has stopped",
    unconfirmed: "Not confirmed after 30 seconds: check the bot",
    unsupported: "This bot can't be stopped remotely (its plugin is too old, or it never reported)"
  };

  var FIELD_WORDS = {
    name: "Name (1 to 32 characters, at most 2 digits, no section sign)", server: "Server address", dim: "Dimension",
    mode: "Mode", botId: "Farmer bot", enabled: "Enabled", session: "Time limit", thresholds: "Safety limits",
    id: "Farm", rev: "Version", maxMs: "Time limit", roles: "Roles", scope: "Scope", reason: "Reason (100 characters, no control characters)"
  };

  /** What an error means, in words. {status, code, field} come from the server's {ok:false, code, field, error}. */
  function explain(err) {
    if (!err) return "Something went wrong.";
    if (err.network) return "Can't reach the server.";
    var b = err.body && typeof err.body === "object" ? err.body : {};
    var code = str(b.code);
    var limits = F.data && F.data.limits ? F.data.limits : {};
    switch (code) {
      case "rev_conflict": return "Someone changed this farm since you opened it. The list is reloaded.";
      case "name_in_use": return "Another farm already has that name.";
      case "farm_limit": return "You already have the most farms the server allows (" + num(limits.maxFarms, 64) + "). Delete one first.";
      case "storage_error": return "Not saved: the server could not write to its disk, so nothing was changed. Try again.";
      case "farms_unavailable": return "The farm records can't be read. The server is protecting its files; check the server's log.";
      case "position_field": case "position_like":
        return "Coordinates are not allowed. Use a plain name.";
      case "bad_field": {
        var f = str(b.field).split(".")[0];
        var what = FIELD_WORDS[f] || (f ? "'" + f + "'" : "A field");
        if (f === "name") return "Name refused: use 1 to 32 characters, with at most 2 digits, and nothing that looks like coordinates.";
        if (f === "botId") return "Pick a bot that has the farmer role.";
        return what + " has a wrong value.";
      }
      case "reserved_field": return "That field can't be set yet.";
      case "bad_vocab": return "The value is not one the server knows.";
      case "no_farm": return "That farm no longer exists. The list is reloaded.";
      case "unknown_bot": return "The server doesn't know that bot.";
      case "no_program": return "This farm has no recorded program yet. Record one in the Stash Window, then use Attach draft.";
      case "no_farmer": return "No bot has the farmer role. Give one in the Bots page.";
      case "farmer_offline": return "The farmer bot is offline.";
      case "stopped": return "A STOP applies to the farmer bot. Resume it first.";
      case "busy": return "A session is already open for this farm.";
      case "farmer_busy": return "The farmer bot has another task open.";
      case "disabled": return "This farm is switched off. Edit it and tick Enabled.";
      case "mode_not_ready": return "This mode arrives in the next version. Only Custom and AFK, standing still run now.";
      case "program_mode_mismatch": return "The recorded program is for a different mode than the farm.";
      case "session_open": return "A session is open for this farm. Stop it first.";
      case "no_session": return "No session is open for this farm.";
      case "no_draft": return "The farmer holds no draft recording to attach. Record and upload one from the Stash Window.";
      case "slow_down": return "Too many changes. Wait " + (err.retryAfter > 0 ? err.retryAfter + " seconds" : "a moment") + " and try again.";
      default:
    }
    if (err.status === 403) return "Only the owner can do this.";
    if (err.status === 401) return "You are logged out.";
    if (err.status === 413) return "The request was too large.";
    if (err.status === 429) return "Too many changes. Wait a moment and try again.";
    if (err.status === 503) return "The server could not save this. Try again.";
    return "The server refused this (" + (err.status || "no answer") + ").";
  }

  var NOT_SAVED = "Applied, not saved: the change is in effect but could not be written to disk. " +
    "It is retried, and it is lost if the server restarts first.";

  // ---------- small DOM helpers ----------

  function mk(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = text;
    return n;
  }

  /** key (farm id and action) lets a re-render give the focus back to the same button. */
  function button(label, cls, onClick, key) {
    var b = mk("button", "mc-button small" + (cls ? " " + cls : ""), label);
    b.type = "button";
    if (key) b.dataset.key = key;
    b.addEventListener("click", onClick);
    return b;
  }

  function chip(text, kind) {
    return mk("span", "status-chip" + (kind ? " " + kind : ""), text);
  }

  function inRoot(node) {
    return !!node && node.nodeType === 1 && /^(INPUT|SELECT|TEXTAREA)$/.test(node.tagName);
  }

  // ---------- STOP bar and bot controls (owner and activity users) ----------

  var O = { data: null, timer: 0, warn: "", busy: false, reason: "", fast: 0 };
  var barEl = document.getElementById("ops-bar");
  var reasonInput = mk("input", "mc-input ops-reason");
  reasonInput.type = "text";
  reasonInput.maxLength = 100;
  reasonInput.placeholder = "Reason (optional)";
  reasonInput.setAttribute("aria-label", "Reason for the emergency stop (optional)");
  reasonInput.autocomplete = "off";
  H.hide(reasonInput); // the text of a STOP can name a place
  reasonInput.spellcheck = false;
  reasonInput.addEventListener("input", function () { O.reason = reasonInput.value; });
  reasonInput.addEventListener("blur", function () { renderBar(); });
  reasonInput.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      reasonInput.blur();
      opsStop("all");
    }
  });

  function opsBots() {
    return O.data && Array.isArray(O.data.bots) ? O.data.bots.filter(function (b) { return b && typeof b === "object"; }) : [];
  }

  function opsBot(botId) {
    var l = opsBots();
    for (var i = 0; i < l.length; i++) if (l[i].botId === botId) return l[i];
    return null;
  }

  function stopOf(o) {
    var s = o && o.stop && typeof o.stop === "object" ? o.stop : {};
    return s.on === true;
  }

  function globalStop() {
    var s = O.data && O.data.stopAll && typeof O.data.stopAll === "object" ? O.data.stopAll : null;
    return s && s.on === true ? s : null;
  }

  function pollOps() {
    window.clearTimeout(O.timer);
    O.timer = 0;
    if (!active()) {
      if (barEl) barEl.hidden = true;
      return;
    }
    var wait = O.fast > Date.now() ? OPS_FAST_MS : OPS_POLL_MS;
    if (!document.hidden) H.api("/api/v1/ops").then(applyOps, function () {}); // a hidden tab asks nothing
    O.timer = window.setTimeout(pollOps, wait);
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && O.timer && active()) pollOps();
  });

  /** Poll quickly for a minute (after a STOP, resume or role change, to see the delivery). */
  function fastPoll() {
    O.fast = Date.now() + 60000;
    if (!O.timer) return;
    window.clearTimeout(O.timer);
    O.timer = window.setTimeout(pollOps, OPS_FAST_MS);
  }

  function applyOps(body) {
    if (!body || typeof body !== "object" || !body.stopAll) return;
    O.data = body;
    var pending = opsBots().some(function (b) { return b.delivered === "sent"; });
    if (pending && O.fast < Date.now()) fastPoll(); // watch the delivery for a minute
    renderBar();
    if (H.isOwner()) H.rerenderBots();
  }

  /** The delivery of the STOP to the bots it applies to, in one line. */
  function deliverySummary() {
    var all = globalStop();
    var counts = { sent: 0, delivered: 0, unconfirmed: 0, unsupported: 0 };
    var total = 0;
    opsBots().forEach(function (b) {
      if (!all && !stopOf(b)) return;
      total++;
      if (counts.hasOwnProperty(b.delivered)) counts[b.delivered]++;
    });
    if (!total) return all ? "No bot is registered for tasks yet." : "";
    var parts = [];
    if (counts.delivered) parts.push(counts.delivered + " of " + total + " delivered");
    if (counts.sent) parts.push(counts.sent + " sent, waiting");
    if (counts.unconfirmed) parts.push(counts.unconfirmed + " not confirmed");
    if (counts.unsupported) parts.push(counts.unsupported + " can't be stopped remotely");
    return parts.join(", ");
  }

  function renderBar() {
    if (!barEl) return;
    if (!O.data || !active()) {
      barEl.hidden = true;
      return;
    }
    var owner = H.isOwner();
    if (owner && document.activeElement === reasonInput) return; // don't pull the box away while typing
    barEl.hidden = false;
    var frag = document.createDocumentFragment();
    var all = globalStop();
    var stoppedBots = opsBots().filter(stopOf).length;
    var top = mk("div", "ops-row");
    if (all) {
      top.appendChild(chip("ALL BOTS STOPPED", "off"));
    } else if (stoppedBots) {
      top.appendChild(chip(stoppedBots + (stoppedBots === 1 ? " bot stopped" : " bots stopped"), "warn"));
    } else {
      top.appendChild(chip("Running", "ok"));
    }
    var sum = deliverySummary();
    if (sum) top.appendChild(mk("span", "note", sum));
    if (all && owner) {
      var by = str(all.by), why = str(all.reason);
      if (by || why) {
        var who = mk("span", "note");
        who.appendChild(document.createTextNode("by "));
        who.appendChild(H.hiddenSpan(by || "?"));
        if (why) {
          who.appendChild(document.createTextNode(": "));
          who.appendChild(H.hiddenSpan(why));
        }
        top.appendChild(who);
      }
    }
    if (owner) {
      if (all) {
        top.appendChild(button("Resume all", "ops-resume", function () { opsResume("all"); }));
      } else {
        top.appendChild(reasonInput);
        reasonInput.value = O.reason;
        var stop = button("STOP ALL", "danger ops-stop", function () { opsStop("all"); });
        stop.title = "Stop every bot's tasks now";
        top.appendChild(stop);
      }
      top.querySelectorAll("button").forEach(function (b) { b.disabled = O.busy; });
    } else if (all || stoppedBots) {
      top.appendChild(mk("span", "note", "Only the owner can resume."));
    }
    frag.appendChild(top);
    if (O.warn) frag.appendChild(mk("div", "note warn", O.warn));
    barEl.replaceChildren(frag);
  }

  function opsCall(path, body, doneText) {
    if (O.busy) return;
    O.busy = true;
    O.warn = "";
    renderBar();
    H.rerenderBots();
    H.api(path, { method: "POST", body: body }).then(function (r) {
      O.busy = false;
      applyOps(r);
      fastPoll();
      if (doneText) H.toast(doneText);
    }, function (err) {
      O.busy = false;
      var b = err && err.body && typeof err.body === "object" ? err.body : null;
      if (err && err.status === 503 && b && b.applied === true) {
        O.warn = NOT_SAVED;
        applyOps(b);
        fastPoll();
      } else {
        O.warn = explain(err);
        renderBar();
        H.rerenderBots();
      }
    });
  }

  function opsStop(scope) {
    if (document.activeElement === reasonInput) reasonInput.blur();
    var body = { scope: scope };
    if (scope === "all") {
      var reason = O.reason.trim();
      if (reason) body.reason = reason;
    }
    opsCall("/api/v1/ops/stop", body, scope === "all" ? "STOP sent to all bots" : "STOP sent");
    if (scope === "all") O.reason = "";
  }

  function opsResume(scope) {
    if (scope === "all" && !window.confirm("Resume all bots?\n\nThey can take tasks again, including farm sessions.")) return;
    opsCall("/api/v1/ops/resume", { scope: scope }, scope === "all" ? "Resumed" : "Bot resumed");
  }

  function setRole(bot, farmer) {
    var roles = (Array.isArray(bot.roles) ? bot.roles : []).filter(function (r) { return r !== "farmer"; });
    if (farmer) roles.push("farmer");
    if (O.busy) return;
    O.busy = true;
    H.rerenderBots();
    H.api("/api/v1/bots/roles", { method: "POST", body: { botId: bot.botId, roles: roles } }).then(function (r) {
      O.busy = false;
      O.warn = "";
      bot.roles = roles;
      applyOps(r);
      H.reloadBots();
      H.toast(farmer ? "Farmer role given" : "Farmer role removed");
    }, function (err) {
      O.busy = false;
      var b = err && err.body && typeof err.body === "object" ? err.body : null;
      if (err && err.status === 503 && b && b.applied === true) {
        O.warn = NOT_SAVED;
        bot.roles = roles;
        applyOps(b);
        H.reloadBots();
      } else {
        O.warn = explain(err);
        renderBar();
        H.rerenderBots();
      }
    });
  }

  /** The owner's controls on one bot card: the farmer role, STOP and Resume with the delivery state. */
  function botControls(bot) {
    if (!H.isOwner() || !bot || typeof bot.botId !== "string") return null;
    var box = mk("div", "farm-botctl");
    if (Array.isArray(bot.roles)) {
      var label = mk("label", "farm-check");
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = bot.roles.indexOf("farmer") >= 0;
      cb.disabled = O.busy;
      cb.addEventListener("change", function () { setRole(bot, cb.checked); });
      label.appendChild(cb);
      label.appendChild(document.createTextNode(" Farmer: may run farm sessions"));
      box.appendChild(label);
    }
    var o = opsBot(bot.botId);
    var row = mk("div", "ops-row");
    if (!o) {
      row.appendChild(mk("span", "note", "STOP is available once this bot has synced with the server."));
    } else {
      var own = stopOf(o), all = !!globalStop();
      if (own || all) row.appendChild(chip(own ? "Stopped" : "Stopped (all)", "off"));
      else row.appendChild(chip("Running", "ok"));
      if (str(o.delivered)) row.appendChild(mk("span", "note", DELIVERY_WORDS[o.delivered] || o.delivered));
      if (own) {
        var res = button("Resume", "", function () { opsResume(bot.botId); });
        res.disabled = O.busy;
        row.appendChild(res);
      } else if (!all) {
        var st = button("STOP", "danger", function () { opsStop(bot.botId); });
        st.disabled = O.busy;
        st.title = "Stop this bot's tasks now";
        row.appendChild(st);
      }
      if (own && all) row.appendChild(mk("span", "note", "Resume all too."));
    }
    box.appendChild(row);
    return box;
  }

  // ---------- Farms page ----------

  var F = {
    data: null, tasks: [], timer: 0, open: false, token: 0, loadError: "", busy: false,
    rowMsg: {}, msg: null, starting: null, startMinutes: "", editor: null
  };
  var rootEl = document.getElementById("farms-root");
  var introBox = mk("div", "farm-intro");
  var editorBox = mk("div", "farm-editorbox");
  var listBox = mk("div", "farm-list");
  var sessionsBox = mk("section", "inset farm-sessions");
  var built = false;

  function active() {
    return !H.blocked() && H.canActivity();
  }

  function farms() {
    return F.data && Array.isArray(F.data.farms) ? F.data.farms.filter(function (f) { return f && typeof f === "object"; }) : [];
  }

  function farmById(id) {
    var l = farms();
    for (var i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
    return null;
  }

  function limits() {
    return F.data && F.data.limits && typeof F.data.limits === "object" ? F.data.limits : {};
  }

  function farmerBots() {
    return H.bots().filter(function (b) { return b && Array.isArray(b.roles) && b.roles.indexOf("farmer") >= 0; });
  }

  function botLabel(botId) {
    var bs = H.bots();
    for (var i = 0; i < bs.length; i++) if (bs[i].botId === botId) return H.botName(bs[i]);
    return botId ? botId : "";
  }

  function setMsg(id, text, kind) {
    if (id) {
      if (text) F.rowMsg[id] = { text: text, kind: kind || "", at: Date.now() };
      else delete F.rowMsg[id];
    } else {
      F.msg = text ? { text: text, kind: kind || "", at: Date.now() } : null;
    }
  }

  function load() {
    var token = ++F.token;
    H.api("/api/v1/farms").then(function (d) {
      if (token !== F.token || !F.open) return;
      F.data = d && typeof d === "object" ? d : null;
      F.loadError = "";
      return H.api("/api/v1/tasks?kind=farm.session").then(function (t) {
        if (token === F.token) F.tasks = t && Array.isArray(t.tasks) ? t.tasks : [];
      }, function () {});
    }, function (err) {
      if (token !== F.token || !F.open) return;
      F.loadError = explain(err);
      if (err && err.status === 503) F.data = null;
      if (err && (err.status === 401 || err.status === 403)) close(); // no access: no more polling
    }).then(function () {
      if (token === F.token && F.open) render(false);
    });
  }

  function schedule() {
    window.clearTimeout(F.timer);
    F.timer = 0;
    if (!F.open) return;
    F.timer = window.setTimeout(function () {
      if (!active() || !H.isOwner()) {
        close(); // logged out or no longer the owner: stop asking
        return;
      }
      if (!document.hidden) load();
      schedule();
    }, FARMS_POLL_MS);
  }

  function open() {
    if (!built) build();
    F.open = true;
    if (!H.isOwner()) {
      rootEl.replaceChildren(mk("div", "note", "The Farms page is for the owner only."));
      built = false;
      return;
    }
    render(true);
    load();
    schedule();
    H.reloadBots(); // the farmer choices need the roles
  }

  function close() {
    F.open = false;
    F.token++;
    window.clearTimeout(F.timer);
    F.timer = 0;
  }

  function build() {
    built = true;
    var sec = mk("section", "inset");
    sec.appendChild(introBox);
    rootEl.replaceChildren(sec, editorBox, listBox, sessionsBox);
  }

  function renderIntro() {
    var frag = document.createDocumentFragment();
    var head = mk("div", "farm-head");
    head.appendChild(mk("div", "section-title", "Farms"));
    var newBtn = button("New farm", "", function () { openEditor(null); });
    var lim = limits();
    if (farms().length >= num(lim.maxFarms, 64)) {
      newBtn.disabled = true;
      newBtn.title = "The farm limit is reached";
    }
    head.appendChild(newBtn);
    head.appendChild(button("Reload", "", function () { setMsg(null, ""); load(); }));
    frag.appendChild(head);
    frag.appendChild(mk("div", "note", "A farm is a recorded loop the farmer bot walks for you. Record from the Stash Window while connected to the farmer's proxy."));
    frag.appendChild(mk("div", "note", "Don't name a farm after its coordinates. Names with 3 or more digits are refused, and no position is ever shown or stored here."));
    frag.appendChild(mk("div", "note", "This version runs the Custom and AFK, standing still modes. The other modes arrive in the next version."));
    var fb = F.data && F.data.farmer && typeof F.data.farmer === "object" ? F.data.farmer : null;
    if (fb) {
      var fr = mk("div", "farm-line");
      fr.appendChild(document.createTextNode("Farmer bot: "));
      fr.appendChild(H.hiddenSpan(botLabel(str(fb.botId))));
      fr.appendChild(chip(fb.online ? "Online" : "Offline", fb.online ? "ok" : "off"));
      if (str(fb.version)) fr.appendChild(mk("span", "note", "plugin " + str(fb.version)));
      if (fb.acceptUploads === false) fr.appendChild(chip("Not accepting recordings", "warn"));
      frag.appendChild(fr);
    } else if (F.data) {
      frag.appendChild(mk("div", "note warn", "No bot has the farmer role yet. Open the Bots page and tick \"Farmer\" on the bot that will run farms."));
    }
    if (F.loadError) frag.appendChild(mk("div", "note warn", F.loadError));
    if (F.msg && F.msg.kind === "ok" && Date.now() - F.msg.at > 12000) F.msg = null; // a confirmation, not a record
    if (F.msg) frag.appendChild(mk("div", "note" + (F.msg.kind === "warn" ? " warn" : F.msg.kind === "ok" ? " farm-ok" : ""), F.msg.text));
    introBox.replaceChildren(frag);
  }

  function fmtDuration(ms) {
    return H.durationText(Math.max(0, num(ms, 0)));
  }

  function programLine(farm) {
    var p = farm.program && typeof farm.program === "object" ? farm.program : null;
    var d = mk("div", "farm-line");
    if (!p) {
      d.appendChild(chip("No program", "warn"));
      d.appendChild(mk("span", "note", "Not recorded yet."));
      return d;
    }
    d.appendChild(chip("Program recorded", "ok"));
    var bits = ["id " + str(p.hash).slice(0, 8)];
    if (num(p.loop, 0) > 0) bits.push("loop " + fmtDuration(num(p.loop, 0) * 50));
    if (num(p.anchors, -1) >= 0) bits.push(num(p.anchors, 0) + (num(p.anchors, 0) === 1 ? " anchor" : " anchors"));
    if (num(p.at, 0) > 0) bits.push("received " + H.ago(p.at));
    d.appendChild(mk("span", "note", bits.join(" · ")));
    if (num(p.unsupported, 0) > 0) d.appendChild(chip(num(p.unsupported, 0) + " unsupported", "warn"));
    if (str(p.mode) && str(p.mode) !== str(farm.mode)) d.appendChild(chip("Recorded for another mode", "warn"));
    return d;
  }

  function taskById(id) {
    for (var i = 0; i < F.tasks.length; i++) if (F.tasks[i] && F.tasks[i].id === id) return F.tasks[i];
    return null;
  }

  function statusLine(farm) {
    var s = farm.status && typeof farm.status === "object" ? farm.status : {};
    var state = str(s.state) || "idle";
    var d = mk("div", "farm-line");
    d.appendChild(chip(STATE_WORDS[state] || state, state === "running" ? "ok" : state === "paused" ? "warn" : ""));
    var bits = [];
    if (state === "paused") bits.push(codeWords(s.pauseReason) || "paused");
    else if (str(s.phase)) bits.push(PHASE_WORDS[s.phase] || s.phase);
    if (state === "running" || state === "paused") bits.push(num(s.loops, 0) + (num(s.loops, 0) === 1 ? " loop" : " loops"));
    var t = str(s.taskId) ? taskById(s.taskId) : null;
    if (t) {
      var pr = t.progress && typeof t.progress === "object" ? t.progress : null;
      if (pr && num(pr.total, 0) > 0) bits.push(num(pr.done, 0) + " of " + num(pr.total, 0));
      var lim = t.params && typeof t.params === "object" ? num(t.params.maxMs, 0) : 0;
      if (lim > 0) bits.push("time limit " + fmtDuration(lim));
    }
    if (bits.length) d.appendChild(mk("span", "note", bits.join(" · ")));
    if ((state === "running" || state === "paused" || state === "pending") && s.farmerOnline === false) {
      d.appendChild(chip("Farmer offline", "off"));
    }
    return d;
  }

  function lastLine(farm) {
    var l = farm.lastSession && typeof farm.lastSession === "object" ? farm.lastSession : null;
    if (!l) return mk("div", "note", "No session yet.");
    var bits = [];
    var why = codeWords(l.endedBy);
    if (!why || str(l.state) !== "cancelled") bits.push(STATE_WORDS[str(l.state)] || str(l.state) || "Ended");
    if (why) bits.push(why);
    bits.push(num(l.loops, 0) + " loops");
    if (num(l.recoveries, 0) > 0) bits.push(num(l.recoveries, 0) + " recoveries");
    if (num(l.runtimeMs, 0) > 0) bits.push("ran " + fmtDuration(l.runtimeMs));
    if (num(l.at, 0) > 0) bits.push(H.ago(l.at));
    return mk("div", "note", "Last session: " + bits.join(" · "));
  }

  function draftOf(farm) {
    var d = F.data && F.data.draft && typeof F.data.draft === "object" ? F.data.draft : null;
    var fb = F.data && F.data.farmer && typeof F.data.farmer === "object" ? F.data.farmer : null;
    if (!d || !fb || fb.botId !== farm.botId) return null;
    return d;
  }

  function renderFarm(farm) {
    var card = mk("div", "dash-bot farm-card");
    var s = farm.status && typeof farm.status === "object" ? farm.status : {};
    var open = str(s.state) === "pending" || str(s.state) === "running" || str(s.state) === "paused";
    var head = mk("div", "dash-bot-head");
    var name = mk("span", "dash-bot-name", str(farm.name) || str(farm.id));
    H.hide(name);
    head.appendChild(name);
    head.appendChild(chip(MODE_NAMES[farm.mode] || str(farm.mode) || "?", M2_MODES.indexOf(farm.mode) >= 0 ? "" : "warn"));
    head.appendChild(chip(farm.enabled === false ? "Switched off" : "Enabled", farm.enabled === false ? "off" : "ok"));
    card.appendChild(head);
    if (M2_MODES.indexOf(farm.mode) < 0) card.appendChild(mk("div", "note", "This mode arrives in the next version; it can't be started yet."));
    card.appendChild(statusLine(farm));
    card.appendChild(programLine(farm));
    var meta = mk("div", "note");
    meta.appendChild(document.createTextNode("Farmer: "));
    meta.appendChild(H.hiddenSpan(botLabel(str(farm.botId)) || "none"));
    if (str(farm.server)) {
      meta.appendChild(document.createTextNode(" · server "));
      meta.appendChild(H.hiddenSpan(str(farm.server)));
    }
    var sess = farm.session && typeof farm.session === "object" ? farm.session : {};
    if (num(sess.maxMs, 0) > 0) meta.appendChild(document.createTextNode(" · max run " + fmtDuration(sess.maxMs)));
    card.appendChild(meta);
    card.appendChild(lastLine(farm));
    var m = F.rowMsg[farm.id];
    if (m && m.kind === "ok" && Date.now() - m.at > 12000) m = null; // a confirmation, not a record
    if (m) card.appendChild(mk("div", "note" + (m.kind === "warn" ? " warn" : m.kind === "ok" ? " farm-ok" : ""), m.text));

    if (F.starting === farm.id) {
      var sp = mk("div", "farm-start");
      var lab = mk("label", "kits-sc-field", "Time limit (minutes, 1 to 720)");
      var inp = mk("input", "mc-input");
      inp.type = "number";
      inp.min = "1";
      inp.max = "720";
      inp.step = "1";
      inp.inputMode = "numeric";
      inp.value = F.startMinutes || String(Math.max(1, Math.round(num(sess.maxMs, 7200000) / 60000)));
      inp.addEventListener("input", function () { F.startMinutes = inp.value; });
      lab.appendChild(inp);
      sp.appendChild(lab);
      sp.appendChild(button("Confirm start", "", function () { doStart(farm, inp.value); }, farm.id + ":confirm"));
      sp.appendChild(button("Cancel", "", function () { F.starting = null; render(true); }, farm.id + ":cancel"));
      card.appendChild(sp);
    }

    var actions = mk("div", "dash-bot-actions");
    var busy = F.busy;
    if (open) {
      var stop = button("Stop", "danger", function () { doStop(farm); }, farm.id + ":stop");
      stop.disabled = busy;
      actions.appendChild(stop);
    } else {
      var start = button("Start", "", function () { askStart(farm); }, farm.id + ":start");
      start.disabled = busy;
      actions.appendChild(start);
    }
    var edit = button("Edit", "", function () { openEditor(farm); }, farm.id + ":edit");
    edit.disabled = busy;
    actions.appendChild(edit);
    var dr = draftOf(farm);
    if (dr) {
      var same = farm.program && farm.program.hash === dr.hash;
      var att = button("Attach draft", "", function () { doAttach(farm, dr); }, farm.id + ":attach");
      att.disabled = busy || open || same;
      att.title = same ? "This draft is already attached" : "Bind the farmer's draft recording (id " + str(dr.hash).slice(0, 8) + ") to this farm";
      actions.appendChild(att);
    }
    var del = button("Delete", "danger", function () { doDelete(farm); }, farm.id + ":delete");
    del.disabled = busy || open;
    if (open) del.title = "Stop the session first";
    actions.appendChild(del);
    card.appendChild(actions);
    if (dr && farm.botId && farm.program && farm.program.hash === dr.hash) {
      card.appendChild(mk("div", "note", "The farmer's draft recording is the one attached."));
    } else if (dr && farm.botId) {
      card.appendChild(mk("div", "note", "Draft on the farmer: " + (MODE_NAMES[dr.mode] || str(dr.mode)) + ", id " + str(dr.hash).slice(0, 8) +
        (num(dr.anchors, -1) >= 0 ? ", " + num(dr.anchors, 0) + " anchors" : "") +
        (str(dr.mode) && str(dr.mode) !== str(farm.mode) ? ". It was recorded for another mode, so Start would be refused." : ".")));
    }
    return card;
  }

  function renderList() {
    var frag = document.createDocumentFragment();
    var l = farms();
    if (!F.data) {
      frag.appendChild(mk("div", "note", F.loadError ? "" : "Loading..."));
    } else if (!l.length) {
      frag.appendChild(mk("div", "note", "No farms yet. Press New farm to add one."));
    } else {
      var grid = mk("div", "dash-bots");
      l.forEach(function (f) { grid.appendChild(renderFarm(f)); });
      frag.appendChild(grid);
    }
    listBox.replaceChildren(frag);
  }

  function renderSessions() {
    var frag = document.createDocumentFragment();
    frag.appendChild(mk("div", "section-title", "Recent sessions"));
    var list = F.tasks.filter(function (t) { return t && typeof t === "object"; }).slice(0, 12);
    if (!list.length) {
      frag.appendChild(mk("div", "note", "No farm session has run yet."));
    } else {
      list.forEach(function (t) {
        var row = mk("div", "farm-session");
        var f = farmById(t.ref && t.ref.farmId);
        var nm = mk("span", "farm-session-name", f ? str(f.name) : "(deleted farm)");
        H.hide(nm);
        row.appendChild(nm);
        var state = str(t.state);
        row.appendChild(chip(STATE_WORDS[state] || state, state === "running" || state === "completed" ? "ok" : state === "failed" ? "off" : state === "paused" ? "warn" : ""));
        var bits = [];
        var code = (t.error && typeof t.error === "object" && str(t.error.code)) || (t.result && typeof t.result === "object" && str(t.result.endedBy)) ||
          (state === "paused" ? str(t.pauseReason) : "");
        if (code) bits.push(codeWords(code));
        if ((state === "running" || state === "paused" || state === "pending") && str(t.phase)) bits.push(PHASE_WORDS[t.phase] || t.phase);
        var pr = t.progress && typeof t.progress === "object" ? t.progress : null;
        if (pr && num(pr.total, 0) > 0) bits.push(num(pr.done, 0) + " of " + num(pr.total, 0));
        var res = t.result && typeof t.result === "object" ? t.result : null;
        if (res) {
          if (num(res.loops, 0) > 0) bits.push(num(res.loops, 0) + " loops");
          if (num(res.recoveries, 0) > 0) bits.push(num(res.recoveries, 0) + " recoveries");
          if (num(res.runtimeMs, 0) > 0) bits.push("ran " + fmtDuration(res.runtimeMs));
        }
        var at = num(t.finishedAt, 0) || num(t.createdAt, 0);
        if (at > 0) bits.push(H.ago(at));
        row.appendChild(mk("span", "note", bits.join(" · ")));
        frag.appendChild(row);
      });
    }
    sessionsBox.replaceChildren(frag);
  }

  function render(force) {
    if (!built) build();
    var focus = document.activeElement;
    if (!force && focus && focus !== document.body && listBox.contains(focus)) {
      // A button or the start box has the keyboard focus: a poll leaves the cards alone so it is not lost.
      renderIntro();
      renderSessions();
      return;
    }
    var key = focus && focus.dataset && focus.dataset.key && listBox.contains(focus) ? focus.dataset.key : "";
    renderIntro();
    renderList();
    renderSessions();
    if (key) {
      var again = listBox.querySelector('[data-key="' + window.CSS.escape(key) + '"]');
      if (again && !again.disabled) again.focus();
    }
  }

  // ---------- actions ----------

  /** Every farm write answers with the full GET body: take it as the new state. */
  function farmCall(path, body, id, okText) {
    if (F.busy) return Promise.resolve(false);
    F.busy = true;
    setMsg(id, "");
    render(true);
    return H.api(path, { method: "POST", body: body }).then(function (r) {
      F.busy = false;
      if (r && typeof r === "object" && Array.isArray(r.farms)) F.data = r;
      if (okText) setMsg(id, okText, "ok");
      render(true);
      load();
      return r || true;
    }, function (err) {
      F.busy = false;
      handleErr(err, id);
      return false;
    });
  }

  function handleErr(err, id) {
    var b = err && err.body && typeof err.body === "object" ? err.body : {};
    var code = str(b.code);
    setMsg(id, explain(err), "warn");
    render(true);
    if (code === "rev_conflict" || code === "no_farm" || code === "session_open" || code === "busy" || code === "no_session") load();
  }

  function askStart(farm) {
    setMsg(farm.id, "");
    if (M2_MODES.indexOf(farm.mode) < 0) {
      setMsg(farm.id, "This mode arrives in the next version. Only Custom and AFK, standing still run now.", "warn");
      render(true);
      return;
    }
    F.starting = F.starting === farm.id ? null : farm.id;
    F.startMinutes = "";
    render(true);
  }

  function doStart(farm, minutesText) {
    var minutes = Number(minutesText);
    if (!/^\d{1,3}$/.test(String(minutesText).trim()) || minutes < 1 || minutes > 720) {
      setMsg(farm.id, "Time limit: a whole number of minutes from 1 to 720.", "warn");
      render(true);
      return;
    }
    if (!window.confirm("Start " + (H.streamerOn() ? "this farm" : "\"" + str(farm.name) + "\"") + " for up to " + minutes +
      " minutes?\n\nThe farmer bot will leave what it is doing and walk the recorded loop.")) return;
    F.starting = null;
    farmCall("/api/v1/farms/start", { id: farm.id, maxMs: minutes * 60000 }, farm.id, "Start requested. The farmer picks it up at its next sync.");
  }

  function doStop(farm) {
    farmCall("/api/v1/farms/stop", { id: farm.id }, farm.id, "Stop requested.");
  }

  function doAttach(farm, draft) {
    if (farm.program && !window.confirm("This farm already has a recorded program. Replace it with the farmer's draft (id " + str(draft.hash).slice(0, 8) + ")?")) return;
    farmCall("/api/v1/farms/attach", { id: farm.id }, farm.id,
      "Attach requested. The farmer binds the draft at its next sync; the program shows here once it reports.");
  }

  function doDelete(farm) {
    if (!window.confirm("Delete " + (H.streamerOn() ? "this farm" : "\"" + str(farm.name) + "\"") +
      "?\n\nThe farmer will also delete its recorded program for it.")) return;
    farmCall("/api/v1/farms/delete", { id: farm.id }, farm.id, "").then(function (r) {
      if (r) setMsg(null, "Farm deleted.", "ok");
      render(true);
    });
  }

  // ---------- create and edit ----------

  function labelled(text, node, cls) {
    var l = mk("label", cls || "kits-sc-field", text);
    l.appendChild(node);
    return l;
  }

  function numInput(value, min, max) {
    var i = mk("input", "mc-input");
    i.type = "number";
    i.min = String(min);
    i.max = String(max);
    i.step = "1";
    i.inputMode = "numeric";
    i.value = String(value);
    return i;
  }

  var THRESHOLDS = [
    ["healthStop", "Stop at health (1-19)", 1, 19, 10],
    ["food", "Stop at food (0-19)", 0, 19, 6],
    ["totems", "Totems needed (0-64)", 0, 64, 1],
    ["playerRadius", "Player radius, blocks (8-64)", 8, 64, 16],
    ["playerSeconds", "Player near for, seconds (1-120)", 1, 120, 10],
    ["invFullPct", "Inventory full at, percent (50-100)", 50, 100, 90]
  ];

  function openEditor(farm) {
    var ed = { id: farm ? farm.id : null, rev: farm ? num(farm.rev, 1) : 0, fields: {} };
    F.editor = ed;
    var lim = limits();
    var nameMax = num(lim.nameMax, 32);
    var box = mk("section", "inset farm-editor");
    box.appendChild(mk("div", "section-title", farm ? "Edit farm" : "New farm"));
    var grid = mk("div", "farm-form");

    var name = mk("input", "mc-input");
    name.type = "text";
    name.maxLength = nameMax;
    name.autocomplete = "off";
    name.spellcheck = false;
    name.value = farm ? str(farm.name) : "";
    H.hide(name);
    ed.fields.name = name;
    grid.appendChild(labelled("Name", name));
    var hint = mk("div", "note farm-name-hint", "Don't name a farm after its coordinates. Names with 3 or more digits are refused.");
    grid.appendChild(hint);
    var checkName = function () {
      var digits = (name.value.normalize("NFKC").match(/\p{Nd}/gu) || []).length;
      hint.className = "note farm-name-hint" + (digits >= 3 ? " warn" : "");
      hint.textContent = digits >= 3 ? "That name has " + digits + " digits. The server refuses names with 3 or more." :
        "Don't name a farm after its coordinates. Names with 3 or more digits are refused.";
    };
    name.addEventListener("input", checkName);
    checkName();

    var mode = mk("select", "mc-select");
    MODE_ORDER.forEach(function (m) {
      var o = mk("option", "", (MODE_NAMES[m] || m) + (M2_MODES.indexOf(m) < 0 ? " (next version)" : ""));
      o.value = m;
      mode.appendChild(o);
    });
    mode.value = farm && MODE_ORDER.indexOf(farm.mode) >= 0 ? farm.mode : "custom";
    ed.fields.mode = mode;
    grid.appendChild(labelled("Mode", mode));

    var bot = mk("select", "mc-select");
    H.hide(bot);
    ed.fields.bot = bot;
    ed.cur = farm ? str(farm.botId) : "";
    grid.appendChild(labelled("Farmer bot", bot));

    var dim = mk("select", "mc-select");
    [["minecraft:overworld", "Overworld"], ["minecraft:the_nether", "Nether"], ["minecraft:the_end", "End"]].forEach(function (d) {
      var o = mk("option", "", d[1]);
      o.value = d[0];
      dim.appendChild(o);
    });
    var curDim = farm ? str(farm.dim) : "minecraft:overworld";
    if (curDim && !Array.prototype.some.call(dim.options, function (o) { return o.value === curDim; })) {
      var od = mk("option", "", curDim);
      od.value = curDim;
      dim.appendChild(od);
    }
    dim.value = curDim || "minecraft:overworld";
    ed.fields.dim = dim;
    grid.appendChild(labelled("Dimension", dim));

    var srv = mk("input", "mc-input");
    srv.type = "text";
    srv.maxLength = 64;
    srv.autocomplete = "off";
    srv.spellcheck = false;
    var known = farms().length ? str(farms()[0].server) : "";
    srv.value = farm ? str(farm.server) : known || "2b2t.org";
    srv.readOnly = !!farm; // set at creation, immutable
    H.hide(srv);
    ed.fields.server = srv;
    grid.appendChild(labelled(farm ? "Server address (fixed)" : "Server address", srv));

    var sess = farm && farm.session && typeof farm.session === "object" ? farm.session : {};
    var maxRun = numInput(Math.round(num(sess.maxMs, 7200000) / 60000), 1, 720);
    ed.fields.maxRun = maxRun;
    grid.appendChild(labelled("Max run time (minutes, 1-720)", maxRun));

    var en = mk("label", "farm-check");
    var enCb = document.createElement("input");
    enCb.type = "checkbox";
    enCb.checked = farm ? farm.enabled !== false : true;
    ed.fields.enabled = enCb;
    en.appendChild(enCb);
    en.appendChild(document.createTextNode(" Enabled (a switched-off farm can't be started)"));
    grid.appendChild(en);
    box.appendChild(grid);

    box.appendChild(mk("div", "settings-group-title", "Safety limits"));
    var th = farm && farm.thresholds && typeof farm.thresholds === "object" ? farm.thresholds : {};
    var tgrid = mk("div", "farm-form");
    ed.th = {};
    THRESHOLDS.forEach(function (t) {
      var inp = numInput(num(th[t[0]], t[4]), t[2], t[3]);
      ed.th[t[0]] = { input: inp, min: t[2], max: t[3], label: t[1] };
      tgrid.appendChild(labelled(t[1], inp));
    });
    box.appendChild(tgrid);

    if (farm) {
      var later = [];
      var tr = farm.travel && typeof farm.travel === "object" ? farm.travel : null;
      if (tr && str(tr.preset)) later.push("travel preset " + str(tr.preset));
      if (Array.isArray(farm.produces) && farm.produces.length) later.push("produces " + farm.produces.length + (farm.produces.length === 1 ? " item" : " items"));
      if (Array.isArray(farm.needs) && farm.needs.length) later.push("needs " + farm.needs.length + (farm.needs.length === 1 ? " item" : " items"));
      if (farm.offlineOk === true) later.push("works while offline");
      if (later.length) box.appendChild(mk("div", "note", "Coming later, read-only and not changed here: " + later.join(", ") + "."));
    }

    ed.msg = mk("div", "note");
    box.appendChild(ed.msg);
    var row = mk("div", "farm-actions");
    ed.saveBtn = button(farm ? "Save" : "Create farm", "", function () { saveEditor(); });
    row.appendChild(ed.saveBtn);
    row.appendChild(button("Cancel", "", function () { closeEditor(); }));
    box.appendChild(row);
    ed.box = box;
    fillBots(ed);
    editorBox.replaceChildren(box);
    name.focus();
    box.scrollIntoView({ block: "nearest" });
  }

  /** The farmer bot choices of the open editor (the bot list may arrive after the editor opens). */
  function fillBots(ed) {
    var bot = ed.fields.bot;
    var keep = bot.value || ed.cur;
    var bots = farmerBots();
    var opts = document.createDocumentFragment();
    if (ed.cur && !bots.some(function (b) { return b.botId === ed.cur; })) {
      var oc = mk("option", "", (H.streamerOn() ? "Bot" : botLabel(ed.cur) || ed.cur) + " (no longer a farmer)");
      oc.value = ed.cur;
      opts.appendChild(oc);
    }
    bots.forEach(function (b, i) {
      var o = mk("option", "", H.streamerOn() ? "Bot " + (i + 1) : H.botName(b)); // opaque labels while streaming
      o.value = b.botId;
      opts.appendChild(o);
    });
    bot.replaceChildren(opts);
    if (keep) bot.value = keep;
    ed.noBot = !bot.options.length;
    if (!ed.id) {
      ed.saveBtn.disabled = ed.noBot;
      if (ed.noBot) {
        ed.msg.className = "note warn";
        ed.msg.textContent = "No bot has the farmer role. Open the Bots page and tick \"Farmer\" on one first.";
      } else if (ed.msg.textContent.indexOf("No bot has the farmer role") === 0) {
        ed.msg.className = "note";
        ed.msg.textContent = "";
      }
    }
  }

  function closeEditor() {
    F.editor = null;
    editorBox.replaceChildren();
  }

  function editorError(text) {
    var ed = F.editor;
    if (!ed) return;
    ed.msg.className = "note warn";
    ed.msg.textContent = text;
  }

  function readInt(input, min, max, label) {
    var t = input.value.trim();
    if (!/^\d{1,6}$/.test(t)) throw label + ": a whole number from " + min + " to " + max + ".";
    var n = Number(t);
    if (n < min || n > max) throw label + ": a whole number from " + min + " to " + max + ".";
    return n;
  }

  function saveEditor() {
    var ed = F.editor;
    if (!ed || F.busy) return;
    var body;
    try {
      var name = ed.fields.name.value.trim();
      if (!name) throw "Give the farm a name.";
      body = { name: name, mode: ed.fields.mode.value, dim: ed.fields.dim.value, enabled: ed.fields.enabled.checked, thresholds: {} };
      if (ed.fields.bot.value) body.botId = ed.fields.bot.value;
      else if (!ed.id) throw "Pick the farmer bot.";
      body.session = { maxMs: readInt(ed.fields.maxRun, 1, 720, "Max run time") * 60000 };
      THRESHOLDS.forEach(function (t) {
        body.thresholds[t[0]] = readInt(ed.th[t[0]].input, t[2], t[3], t[1].replace(/ \(.*\)$/, ""));
      });
      if (ed.id) {
        body.id = ed.id;
        body.rev = ed.rev;
      } else {
        var server = ed.fields.server.value.trim();
        if (!/^[A-Za-z0-9.-]{1,253}(:\d{1,5})?$/.test(server)) throw "Server address: a host name such as 2b2t.org, with an optional :port.";
        body.server = server;
      }
    } catch (e) {
      editorError(typeof e === "string" ? e : "Check the values.");
      return;
    }
    F.busy = true;
    ed.saveBtn.disabled = true;
    ed.msg.className = "note";
    ed.msg.textContent = "Saving...";
    H.api("/api/v1/farms", { method: "POST", body: body }).then(function (r) {
      F.busy = false;
      if (r && typeof r === "object" && Array.isArray(r.farms)) F.data = r;
      closeEditor();
      setMsg(null, ed.id ? "Farm saved." : "Farm created.", "ok");
      render(true);
      load();
    }, function (err) {
      F.busy = false;
      if (F.editor !== ed) return;
      ed.saveBtn.disabled = false;
      var b = err && err.body && typeof err.body === "object" ? err.body : {};
      if (str(b.code) === "rev_conflict" && b.farm && typeof b.farm === "object") {
        // Reload and retry: the typed values stay, the version moves to the farm's current one.
        ed.rev = num(b.farm.rev, ed.rev);
        editorError("The farm was changed by someone else meanwhile, so nothing was saved. Your values are kept: check them and press Save again.");
        load();
        return;
      }
      editorError(explain(err));
      if (str(b.code) === "no_farm") load();
    });
  }

  // ---------- keeping the page in step ----------

  /** Called by app.js whenever the login, the permissions or the bots change. */
  function sync() {
    var ok = active();
    if (!ok || (F.open && !H.isOwner())) close(); // logged out, or no longer the owner: the Farms page stops asking
    if (!ok) {
      window.clearTimeout(O.timer);
      O.timer = 0;
      O.data = null;
      if (barEl) barEl.hidden = true;
      return;
    }
    if (!O.timer) pollOps();
    else renderBar();
    if (F.editor && !F.busy) fillBots(F.editor);
  }

  // Streamer mode switches on or off with a class on <html>: the bot labels of an open editor follow it.
  new MutationObserver(function () {
    if (F.editor && !F.busy) fillBots(F.editor);
  }).observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });

  window.StashFarms = { open: open, close: close, sync: sync, botControls: botControls };
  sync();
})();

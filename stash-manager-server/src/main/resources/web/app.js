// Stash Manager page. Item names come from players, so the DOM is only built with
// createElement/textContent, never innerHTML.
(function () {
  "use strict";

  var POLL_MS = 10000;
  var LINK_POLL_MS = 3000;
  var FETCH_TIMEOUT_MS = 8000;
  var SEARCH_DEBOUNCE_MS = 100;
  var PLACEHOLDER = "/placeholder.svg";
  var ICON_RETRIES = 20;
  var ICON_SOURCE_TOKEN = "g2"; // bump when the server changes how icons are made
  var DEFAULT_MC = "1.21.11";
  var ICON_RETRY_DELAYS = [1000, 2000, 3000, 5000];
  var STORAGE_KEY = "stashManager.selected";

  function $(id) {
    return document.getElementById(id);
  }

  // ---------- streamer mode (display only) ----------
  // Sensitive values are always marked with data-sensitive where they are created; the CSS blurs them while
  // <html> has the "streamer" class (set before the first paint by streamer-init.js).
  var STREAMER_KEY = "stashManager.streamer";

  function streamerOn() {
    return document.documentElement.classList.contains("streamer");
  }

  /**
   * The owner forced streamer mode on for this user (perms.streamer): always on, and the server already leaves out
   * positions, names and addresses. The page only shows it as locked.
   */
  function forcedStreamer() {
    return typeof me === "object" && !me.owner && !!me.perms && me.perms.streamer === true;
  }

  /** A server's error text for display: in streamer mode it may carry names or positions, so a generic line is shown instead. */
  function safeServerText(text, generic) {
    return streamerOn() ? generic : text;
  }

  function hide(node) {
    node.setAttribute("data-sensitive", "");
    return node;
  }

  function hiddenSpan(text) {
    var span = document.createElement("span");
    span.textContent = text;
    return hide(span);
  }

  var el = {
    select: $("stash-select"),
    streamerBtn: $("streamer-btn"),
    streamerState: $("streamer-state"),
    dot: $("bot-dot"),
    botText: $("bot-text"),
    infoIndex: $("info-index"),
    infoContainers: $("info-containers"),
    infoMeta: $("info-meta"),
    indexMeta: $("index-meta"),
    tileContainers: $("tile-containers"),
    tileStale: $("tile-stale"),
    tileIndexed: $("tile-indexed"),
    gridWrap: $("grid-wrap"),
    grid: $("item-grid"),
    empty: $("empty"),
    emptyText: $("empty-text"),
    search: $("search"),
    sort: $("sort"),
    summary: $("summary"),
    tooltip: $("tooltip"),
    tabs: $("tabs"),
    tabsRight: $("tabs-right"),
    panelLabel: $("panel-label"),
    viewItems: $("view-items"),
    viewOrders: $("view-orders"),
    viewKits: $("view-kits"),
    viewKb: $("view-kb"),
    crumbs: $("crumbs"),
    pickerBody: $("picker-body"),
    cartLines: $("cart-lines"),
    cartTotals: $("cart-totals"),
    namesSeg: $("names-seg"),
    namesAll: $("names-all"),
    namesEach: $("names-each"),
    namesRows: $("names-rows"),
    namesWarn: $("names-warn"),
    craftInfo: $("craft-info"),
    repairAll: $("repair-all"),
    repairInfo: $("repair-info"),
    repairWarn: $("repair-warn"),
    deliveryFit: $("delivery-fit"),
    deliveryWarn: $("delivery-warn"),
    cartPlace: $("cart-place"),
    cartClear: $("cart-clear"),
    cartStatus: $("cart-status"),
    cartOffline: $("cart-offline"),
    orderList: $("order-list"),
    toast: $("toast"),
    linkOpen: $("link-open"),
    linkModal: $("link-modal"),
    linkClose: $("link-close"),
    linkUrls: $("link-urls"),
    linkKey: $("link-key"),
    linkKeyToggle: $("link-key-toggle"),
    linkKeyCopy: $("link-key-copy"),
    linkKeyNote: $("link-key-note"),
    linkDot: $("link-dot"),
    linkStatusText: $("link-status-text"),
    linkRegen: $("link-regen"),
    linkCopyNote: $("link-copy-note"),
    deliveryStatus: $("delivery-status"),
    orderBlocker: $("order-blocker"),
    cartDrop: $("cart-drop"),
    cartCount: $("cart-count"),
    chestGrid: $("chest-grid"),
    binGrid: $("bin-grid"),
    binTitle: $("bin-title"),
    dragGhost: $("drag-ghost"),
    ordersClear: $("orders-clear"),
    pageActivity: $("page-activity"),
    navActivity: $("nav-activity"),
    activityTitle: $("activity-title"),
    actEmpty: $("act-empty"),
    actBody: $("act-body"),
    actHead: $("act-head"),
    actStats: $("act-stats"),
    actNow: $("act-now"),
    actEvents: $("act-events"),
    actSource: $("act-source"),
    actLevel: $("act-level"),
    actModules: $("act-modules"),
    actModCount: $("act-mod-count"),
    actModFilter: $("act-mod-filter"),
    actEnabledOnly: $("act-enabled-only"),
    actPlugins: $("act-plugins"),
    sudoBox: $("sudo-box"),
    sudoText: $("sudo-text"),
    sudoLock: $("sudo-lock"),
    inventory: $("inventory"),
    tabsWrap: $("tabs-wrap"),
    pageBots: $("page-bots"),
    pageSettings: $("page-settings"),
    pageConsole: $("page-console"),
    dashNav: $("dash-nav"),
    botSelect: $("bot-select"),
    dashBots: $("dash-bots"),
    stashBots: $("stash-bots"),
    settingsTitle: $("settings-title"),
    settingsStatus: $("settings-status"),
    settingsRevert: $("settings-revert"),
    settingsSave: $("settings-save"),
    settingsOffline: $("settings-offline"),
    settingsBody: $("settings-body"),
    consoleTitle: $("console-title"),
    consoleState: $("console-state"),
    consoleOffline: $("console-offline"),
    consoleLog: $("console-log"),
    consoleForm: $("console-form"),
    consoleCmd: $("console-cmd"),
    consoleSend: $("console-send"),
    pageProxy: $("page-proxy"),
    navProxy: $("nav-proxy"),
    proxyTitle: $("proxy-title"),
    proxyScope: $("proxy-scope"),
    proxyStatus: $("proxy-status"),
    proxyRevert: $("proxy-revert"),
    proxySave: $("proxy-save"),
    proxyOffline: $("proxy-offline"),
    proxySearch: $("proxy-search"),
    proxyNav: $("proxy-nav"),
    proxyBody: $("proxy-body"),
    authScreen: $("auth-screen"),
    authForm: $("auth-form"),
    authText: $("auth-text"),
    authPassword: $("auth-password"),
    authPasswordRow: $("auth-password-row"),
    authConfirm: $("auth-confirm"),
    authConfirmRow: $("auth-confirm-row"),
    authCode: $("auth-code"),
    authCodeRow: $("auth-code-row"),
    authSubmit: $("auth-submit"),
    authError: $("auth-error"),
    logoutBtn: $("logout-btn"),
    passwordOpen: $("password-open"),
    logoutAll: $("logout-all"),
    botsOpen: $("bots-open"),
    conflictWarn: $("conflict-warn"),
    linkBots: $("link-bots"),
    linkStatus: $("link-status"),
    rescanBox: $("rescan-box"),
    rescanBtn: $("rescan-btn"),
    rescanStatus: $("rescan-status"),
    rescanProgress: $("rescan-progress"),
    rescanFill: $("rescan-fill"),
    authUsername: $("auth-username"),
    authUsernameRow: $("auth-username-row"),
    authUserHint: $("auth-user-hint"),
    authPasswordLabel: $("auth-password-label"),
    authNext: $("auth-next"),
    authNextRow: $("auth-next-row"),
    authConfirmLabel: $("auth-confirm-label"),
    authLogout: $("auth-logout"),
    userName: $("user-name"),
    accountName: $("account-name"),
    accountSection: $("account-section"),
    accountWho: $("account-who"),
    navConsole: $("nav-console"),
    navPlanner: $("nav-planner"),
    pagePlanner: $("page-planner"),
    consoleAllow: $("console-allow"),
    orderPicker: $("order-picker"),
    usersSection: $("users-section"),
    usersBody: $("users-body"),
    audit: $("audit"),
    auditStatus: $("audit-status"),
    auditList: $("audit-list"),
    auditRefresh: $("audit-refresh")
  };

  var slotEntry = new WeakMap(); // slot element -> entry

  var state = {
    stashes: [],          // last /api/v1/stashes reply
    selected: null,       // stash key string
    clockSkew: 0,         // server time minus local time, from the Date header
    unreachable: false,
    polling: false,
    index: null,          // loaded index
    loadedWant: null,     // "<stash key>|<indexedAt>" of the loaded index
    pendingWant: null,    // same, for the request in flight
    loadToken: 0,         // bumps on every index request; stale replies are dropped
    items: [],            // entries in index order: { item, slot, img, countEl, glint, hay, ns, ... }
    byKey: new Map(),     // item key -> entry, kept across index refreshes
    shown: [],            // entries currently in the grid, in display order
    shulkers: [],         // shulker group entries (the Shulkers tab), same shape as item entries
    tab: "all",           // selected tab id
    sortAll: "count",     // sort used in the Search/All and Shulkers tabs
    sortTab: "creative",  // sort used inside creative tabs
    mc: DEFAULT_MC,       // the loaded index's Minecraft version (for icon URLs)
    hovered: null,        // entry under the cursor
    mouse: { x: 0, y: 0, over: false },
    searchTimer: 0
  };

  // ---------- formatting ----------

  function trimZero(s) {
    return s.replace(/\.0$/, "");
  }

  function compact(n) {
    if (n < 1000) return String(n);
    if (n < 9950) return trimZero((n / 1e3).toFixed(1)) + "k";
    if (n < 999500) return Math.round(n / 1e3) + "k";
    if (n < 9950000) return trimZero((n / 1e6).toFixed(1)) + "M";
    if (n < 999500000) return Math.round(n / 1e6) + "M";
    return trimZero((n / 1e9).toFixed(1)) + "B";
  }

  function approx(v) {
    if (v > 0 && v < 0.05) return "<0.1";
    return v >= 10 ? String(Math.round(v)) : trimZero(v.toFixed(1));
  }

  function grouped(n) {
    return Number(n).toLocaleString("en-US");
  }

  function serverNow() {
    return Date.now() + state.clockSkew;
  }

  function ago(ms) {
    if (ms == null) return "never";
    var s = Math.max(0, Math.round((serverNow() - ms) / 1000));
    if (s < 60) return s + "s ago";
    var m = Math.floor(s / 60);
    if (m < 60) return m + "m ago";
    var h = Math.floor(m / 60);
    if (h < 48) return h + "h ago";
    return Math.floor(h / 24) + "d ago";
  }

  function stashKey(s) {
    return JSON.stringify([s.server, s.stash]);
  }

  function str(v) {
    return typeof v === "string" ? v : "";
  }

  function num(v, d) {
    return typeof v === "number" && isFinite(v) ? v : d;
  }

  // ---------- website login ----------
  // The page's files are public, everything else needs the sm_session cookie (HttpOnly, set by the server).
  // Any 401 from the API shows the login screen over the page; after logging in the page carries on where it was.
  // A sub-user whose password the owner set (mustChange) gets the "Choose a new password" screen instead, and so
  // does any API answer 403 {mustChange: true}.

  var MIN_PASSWORD = 12;
  var USER_KEY = "stashManager.user";
  var auth = { blocked: true, mode: "login", busy: false, userId: null };

  // ---------- the logged-in user and what it may do ----------
  // auth/state names the user and its effective permissions. The server enforces every one of them; the page only
  // hides what the user can't use. A server without sub-users sends no user: that is the owner, with everything.

  var OWNER_PERMS = {
    view: true, order: true, ordersAll: true, activity: true, settings: true, proxy: "change", console: "full",
    consoleAllow: [], rescan: true, link: true, planner: true, plannerOrder: true, viewer: true, streamer: false, stashes: null, bots: null
  };
  var me = { id: "owner", name: "", owner: true, mustChange: false, perms: OWNER_PERMS };
  var permsTimer = 0;

  /** Takes auth/state's user (or none: the owner). Returns true when anything changed. */
  function setUser(u) {
    var before = JSON.stringify(me);
    if (u && typeof u === "object" && u.owner !== true) {
      me = {
        id: str(u.id),
        name: str(u.name),
        owner: false,
        mustChange: u.mustChange === true,
        perms: u.perms && typeof u.perms === "object" ? u.perms : {}
      };
    } else {
      me = {
        id: u && typeof u === "object" ? str(u.id) || "owner" : "owner",
        name: u && typeof u === "object" ? str(u.name) : "",
        owner: true,
        mustChange: !!(u && typeof u === "object" && u.mustChange === true),
        perms: OWNER_PERMS
      };
    }
    return JSON.stringify(me) !== before;
  }

  /** A yes/no permission of the logged-in user. */
  function can(key) {
    return me.owner || me.perms[key] === true;
  }

  /** "proxy" ("none" | "read" | "change") or "console" ("none" | "list" | "full"). */
  function permMode(key) {
    if (me.owner) return key === "console" ? "full" : "change";
    var v = me.perms[key];
    return typeof v === "string" && v ? v : "none";
  }

  /** The dashboard pages this user may open. */
  function pageAllowed(page) {
    if (page === "bots" || page === "activity") return can("activity");
    if (page === "proxy") return permMode("proxy") !== "none" && !forcedStreamer();
    if (page === "console") return permMode("console") !== "none" && !forcedStreamer();
    if (page === "planner") return can("planner");
    if (page === "viewer") return can("viewer");
    return true;
  }

  /** An order is the user's own when they placed it; an order without placedBy is from before users: the owner's. */
  function ownOrder(o) {
    var by = o && o.placedBy && typeof o.placedBy === "object" ? o.placedBy : null;
    return by ? str(by.id) === me.id : me.owner;
  }

  function canManageOrder(o) {
    // plannerOrder counts for the user's own orders too (build planner parts).
    return can("ordersAll") || ((can("order") || can("plannerOrder")) && ownOrder(o));
  }

  /** A 403 naming a permission: the owner may have changed this user's permissions, so ask again (once). */
  function permsStale() {
    if (permsTimer) return;
    permsTimer = window.setTimeout(function () {
      permsTimer = 0;
      refreshSudo();
    }, 500);
  }

  /** "wrong username or password" -> "Wrong username or password." */
  function sentence(text) {
    var t = String(text || "").trim();
    if (!t) return "";
    return t.charAt(0).toUpperCase() + t.slice(1) + (/[.!?]$/.test(t) ? "" : ".");
  }

  function savedUsername() {
    try {
      return window.localStorage.getItem(USER_KEY) || "";
    } catch (e) {
      return "";
    }
  }

  function saveUsername(name) {
    try {
      if (name) window.localStorage.setItem(USER_KEY, name);
      else window.localStorage.removeItem(USER_KEY);
    } catch (e) {
      // Storage disabled: just don't remember.
    }
  }

  function checkAuth() {
    api("/api/v1/auth/state").then(function (st) {
      if (st && typeof st.minLength === "number") MIN_PASSWORD = st.minLength;
      if (st && st.loggedIn) {
        setUser(st.user);
        // Another account on the same page (an expired session, then someone else logged in): start clean.
        if (auth.userId !== null && auth.userId !== me.id) {
          reloadPage();
          return;
        }
        auth.userId = me.id;
        if (me.mustChange) showAuth("change");
        else loggedIn();
      } else if (st && st.setupNeeded) {
        showAuth(st.setupAllowed ? "setup" : "elsewhere", str(st.message));
      } else {
        showAuth("login");
      }
    }, function () {
      showAuth("login");
      authError("Server unreachable. Retrying...");
      window.setTimeout(checkAuth, 3000);
    });
  }

  function loginRequired() {
    if (auth.blocked) return;
    showAuth("login");
  }

  /** Any API answer 403 {mustChange: true}: the blocking "Choose a new password" screen. */
  function mustChangeRequired() {
    if (auth.blocked && auth.mode === "change") return;
    me.mustChange = true;
    showAuth("change");
  }

  function authError(text) {
    el.authError.textContent = text || "";
  }

  function showAuth(mode, message) {
    auth.blocked = true;
    auth.mode = mode;
    hideTooltip();
    if (typeof closeWindow === "function") closeWindow();
    if (link.open) closeLink();
    el.authScreen.hidden = false;
    var setup = mode === "setup";
    var elsewhere = mode === "elsewhere";
    var change = mode === "change";
    el.authText.textContent = setup
      ? "First run: type the setup code shown in the server window, then choose a password for this website (at least " + MIN_PASSWORD +
        " characters). Anyone on your network will need it to see the stash, place orders or control the bots. It is remembered for 30 days on each browser."
      : elsewhere
        ? (message || "Set the dashboard password on the server PC (open http://localhost:8765).")
        : change
          ? "Choose a new password" + (me.name ? " for " + me.name : "") + " (at least " + MIN_PASSWORD + " characters). " +
            "The password you were given is temporary: the site opens once you have changed it."
          : "Enter the website password. Sub-users also type their username; the owner can leave it blank.";
    el.authUsernameRow.hidden = mode !== "login";
    // The hidden username field is a password-manager hint for the new password screens only.
    el.authUserHint.disabled = !(setup || change);
    el.authUserHint.value = change && me.name ? me.name : "stash-manager";
    el.authPasswordRow.hidden = elsewhere;
    el.authPasswordLabel.textContent = change ? "Current (temporary) password" : "Password";
    el.authNextRow.hidden = !change;
    el.authConfirmRow.hidden = !(setup || change);
    el.authConfirmLabel.textContent = change ? "New password again" : "Confirm password";
    el.authCodeRow.hidden = !setup;
    el.authCode.value = "";
    el.authSubmit.hidden = elsewhere;
    el.authSubmit.textContent = setup ? "Set password" : change ? "Change password" : "Log in";
    el.authLogout.hidden = !change;
    el.authPassword.setAttribute("autocomplete", setup ? "new-password" : "current-password");
    el.authPassword.value = "";
    el.authNext.value = "";
    el.authConfirm.value = "";
    if (mode === "login" && !el.authUsername.value) el.authUsername.value = savedUsername();
    authError("");
    if (setup) el.authCode.focus();
    else if (!elsewhere) el.authPassword.focus();
  }

  function loggedIn() {
    var first = !auth.started;
    auth.blocked = false;
    auth.started = true;
    el.authScreen.hidden = true;
    el.authPassword.value = "";
    el.authNext.value = "";
    el.authConfirm.value = "";
    refreshSudo();
    applyPerms(false);
    if (first) {
      renderTabs();
      showPage(dash.page);
    } else {
      retryIcons();
    }
    poll();
    pollBots();
    scheduleOrdersPoll(0);
  }

  /** Icons that failed while logged out are asked for again. */
  function retryIcons() {
    tabsShown = ""; // rebuild the tab buttons, whose icons may have failed too
    renderTabs();
    var all = [];
    state.byKey.forEach(function (e) { all.push(e); });
    if (state.shulkerByKey) state.shulkerByKey.forEach(function (e) { all.push(e); });
    all.forEach(function (e) {
      if (e.iconLoaded) return;
      e.tries = 0;
      e.img.src = e.url + "&retry=login";
    });
  }

  function tooMany(body) {
    return "Too many attempts. Try again in " + (body && body.retryAfter ? body.retryAfter : 60) + " s.";
  }

  /** The must-change screen: the caller's own password, then auth/state again. */
  function submitChange() {
    var current = el.authPassword.value;
    var next = el.authNext.value;
    if (!current) return authError("Enter the password you were given.");
    if (next.length < MIN_PASSWORD) return authError("The new password needs at least " + MIN_PASSWORD + " characters.");
    if (next !== el.authConfirm.value) return authError("The two new passwords are different.");
    if (next === current) return authError("Choose a new password, not the one you were given.");
    auth.busy = true;
    el.authSubmit.disabled = true;
    authError("Saving...");
    api("/api/v1/auth/password", { method: "POST", body: { current: current, next: next } }).then(function () {
      authError("");
      me.mustChange = false;
      toast("Password changed");
      checkAuth();
    }, function (err) {
      var body = err && err.body;
      if (err && err.status === 401) {
        authError("The current password is wrong.");
        el.authPassword.select();
      } else if (err && err.status === 429) {
        authError(tooMany(body));
      } else {
        authError(body && body.error ? sentence(body.error) : err && err.network ? "Server unreachable." : "Could not change the password.");
      }
    }).then(function () {
      auth.busy = false;
      el.authSubmit.disabled = false;
    });
  }

  el.authForm.addEventListener("submit", function (e) {
    e.preventDefault();
    if (auth.busy || auth.mode === "elsewhere") return;
    if (auth.mode === "change") return void submitChange();
    var password = el.authPassword.value;
    var setup = auth.mode === "setup";
    var username = setup ? "" : el.authUsername.value.trim();
    if (setup) {
      if (!el.authCode.value.trim()) return authError("Type the setup code from the server window.");
      if (password.length < MIN_PASSWORD) return authError("The password needs at least " + MIN_PASSWORD + " characters.");
      if (password !== el.authConfirm.value) return authError("The two passwords are different.");
    } else if (!password) {
      return authError("Enter the password.");
    }
    auth.busy = true;
    el.authSubmit.disabled = true;
    authError(setup ? "Saving..." : "Checking...");
    // A blank username is the owner, exactly as before sub-users existed.
    var payload = setup ? { password: password, code: el.authCode.value } : username ? { username: username, password: password } : { password: password };
    api(setup ? "/api/v1/auth/setup" : "/api/v1/auth/login", { method: "POST", body: payload }).then(function () {
      authError("");
      if (!setup) saveUsername(username);
      checkAuth(); // names the user (and its permissions, or the must-change screen)
    }, function (err) {
      var body = err && err.body;
      if (err && err.status === 429) {
        authError(tooMany(body));
      } else if (err && err.status === 401) {
        authError(body && body.error ? sentence(body.error) : "Wrong password.");
        el.authPassword.select();
      } else if (err && err.status === 409) {
        checkAuth(); // set up meanwhile, or reset
      } else if (err && err.status === 403 && setup) {
        authError(body && body.error ? body.error : "Refused.");
        el.authCode.select();
      } else if (err && err.status === 403) {
        authError(body && body.error ? sentence(body.error) : "Refused."); // "this account is disabled"
      } else {
        authError(body && body.error ? body.error : err && err.network ? "Server unreachable." : "Could not log in.");
      }
    }).then(function () {
      auth.busy = false;
      el.authSubmit.disabled = false;
    });
  });

  function logout() {
    api("/api/v1/auth/logout", { method: "POST", body: {} }).then(reloadPage, reloadPage);
  }

  el.logoutBtn.addEventListener("click", logout);
  el.authLogout.addEventListener("click", logout);

  function reloadPage() {
    window.location.reload();
  }

  /** The owner ends every session of every user; a sub-user ends their own. */
  function logoutEverywhere() {
    var text = me.owner ? "Log out every browser, including this one?" : "Log out every browser where you are logged in, including this one?";
    if (!window.confirm(text)) return;
    api("/api/v1/auth/logout-all", { method: "POST", body: {} }).then(reloadPage, function (e) {
      toast("Could not log out everywhere" + (e && e.body && e.body.error ? ": " + e.body.error : ""));
    });
  }

  el.logoutAll.addEventListener("click", logoutEverywhere);
  $("account-logout-all").addEventListener("click", logoutEverywhere);

  /** The caller's own password (owner or sub-user). */
  function openPasswordWindow() {
    if (link.open) closeLink();
    var body = document.createElement("form");
    body.className = "password-form";
    body.noValidate = true;
    var fields = {};
    [["current", "Current password", "current-password"], ["next", "New password (at least " + MIN_PASSWORD + " characters)", "new-password"],
      ["confirm", "New password again", "new-password"]].forEach(function (f) {
      var label = document.createElement("label");
      label.className = "auth-field";
      var span = document.createElement("span");
      span.className = "field-label";
      span.textContent = f[1];
      var input = document.createElement("input");
      input.type = "password";
      input.className = "mc-input";
      input.maxLength = 1024;
      input.autocomplete = f[2];
      label.appendChild(span);
      label.appendChild(input);
      body.appendChild(label);
      fields[f[0]] = input;
    });
    var info = line("note", "Every other browser is logged out. This one stays logged in.");
    body.appendChild(info);
    var msg = line("note warn", "");
    var save = document.createElement("button");
    save.type = "submit";
    save.className = "mc-button";
    save.textContent = "Change password";
    body.appendChild(save);
    body.appendChild(msg);
    body.addEventListener("submit", function (e) {
      e.preventDefault();
      if (fields.next.value.length < MIN_PASSWORD) return void (msg.textContent = "The new password needs at least " + MIN_PASSWORD + " characters.");
      if (fields.next.value !== fields.confirm.value) return void (msg.textContent = "The new passwords are different.");
      save.disabled = true;
      msg.textContent = "Saving...";
      api("/api/v1/auth/password", { method: "POST", body: { current: fields.current.value, next: fields.next.value } }).then(function (r) {
        closeWindow();
        var n = r && typeof r.ended === "number" ? r.ended : 0;
        toast("Password changed" + (n ? "; " + n + " other session" + (n === 1 ? "" : "s") + " logged out" : ""));
      }, function (err) {
        save.disabled = false;
        var b = err && err.body;
        msg.textContent = err && err.status === 401 ? "The current password is wrong." :
          err && err.status === 429 ? tooMany(b) :
          b && b.error ? b.error : "Could not change the password.";
      });
    });
    openWindow("Change password", body, "password-window");
    fields.current.focus();
  }

  el.passwordOpen.addEventListener("click", openPasswordWindow);
  $("account-password").addEventListener("click", openPasswordWindow);

  // ---------- networking ----------

  /**
   * fetch + JSON with an 8 s timeout. Non-GET requests carry the headers the server's browser-write
   * guard requires (X-Stash-Manager: 1 and a JSON content type). Rejects with err.status for HTTP
   * errors and err.network for connection problems.
   */
  function api(path, opts) {
    opts = opts || {};
    var method = opts.method || "GET";
    var ctrl = typeof AbortController === "function" ? new AbortController() : null;
    var timer = ctrl ? window.setTimeout(function () { ctrl.abort(); }, opts.timeout || FETCH_TIMEOUT_MS) : 0;
    var init = {
      method: method,
      cache: opts.cache || "no-store",
      credentials: "same-origin",
      headers: {},
      signal: ctrl ? ctrl.signal : undefined
    };
    if (method !== "GET") {
      init.headers["X-Stash-Manager"] = "1";
      if (opts.raw) {
        // A file upload (the build planner): the bytes as they are.
        init.headers["Content-Type"] = "application/octet-stream";
        init.body = opts.raw;
      } else {
        init.headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(opts.body || {});
      }
    }
    return fetch(path, init).then(function (res) {
      window.clearTimeout(timer);
      var date = res.headers.get("Date");
      if (date) {
        var t = Date.parse(date);
        if (!isNaN(t)) state.clockSkew = t - Date.now();
      }
      if (res.status === 401 && path.indexOf("/api/v1/auth/") !== 0) loginRequired();
      if (!res.ok) {
        var err = new Error("HTTP " + res.status);
        err.status = res.status;
        return res.json().then(function (body) {
          err.body = body;
          if (res.status === 403 && body && typeof body === "object" && path.indexOf("/api/v1/auth/") !== 0) {
            if (body.mustChange === true) mustChangeRequired();
            else if (typeof body.permission === "string") permsStale();
          }
          throw err;
        }, function () {
          throw err;
        });
      }
      if (res.status === 204) return null;
      return res.json();
    }, function (e) {
      window.clearTimeout(timer);
      var err = new Error("network: " + (e && e.message));
      err.network = true;
      throw err;
    });
  }

  function poll() {
    if (state.polling || auth.blocked) return;
    state.polling = true;
    // Without "view" the server refuses the stash list: don't ask, and don't call that "unreachable".
    var request = can("view") ? api("/api/v1/stashes") : Promise.resolve([]);
    request.then(function (list) {
      state.unreachable = false;
      state.stashes = Array.isArray(list) ? list : [];
      state.polledOnce = true;
    }, function () {
      state.unreachable = true;
    }).then(function () {
      state.polling = false;
      // Rendering runs outside the fetch's error handler, so a render bug can't look like "unreachable".
      updateSelect();
      if (planner.open) plannerFillStashes();
      updateHeader();
      renderBotStatus();
      if (!state.unreachable) {
        maybeLoadIndex();
        maybeAutoOpenLink();
        deliveryMaybeLoad();
      }
    });
  }

  function currentStash() {
    for (var i = 0; i < state.stashes.length; i++) {
      if (stashKey(state.stashes[i]) === state.selected) return state.stashes[i];
    }
    return null;
  }

  function maybeLoadIndex() {
    var s = currentStash();
    if (!s || s.indexedAt == null) {
      if (state.loadedWant !== null || state.items.length) clearIndex();
      showEmpty();
      return;
    }
    var want = state.selected + "|" + s.indexedAt;
    if (want === state.loadedWant || want === state.pendingWant) return;

    var token = ++state.loadToken;
    var key = state.selected;
    state.pendingWant = want;
    // "no-cache" lets the browser revalidate with the ETag (304) instead of downloading again.
    api("/api/v1/index?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash), { cache: "no-cache" })
      .then(function (index) {
        if (token !== state.loadToken) return; // a newer selection or index superseded this one
        state.pendingWant = null;
        var sameStash = state.loadedWant !== null && state.loadedWant.indexOf(key + "|") === 0;
        var scroll = el.gridWrap.scrollTop;
        state.index = index;
        state.loadedWant = want;
        updateItems(index);
        if (!sameStash) onStashChanged();
        renderTabs();
        renderNow();
        updateHeader();
        refreshOrdersView();
        el.gridWrap.scrollTop = sameStash ? scroll : 0;
      }, function (e) {
        if (token !== state.loadToken) return;
        state.pendingWant = null;
        if (e && e.status === 404) {
          clearIndex();
          showEmpty();
        }
      });
  }

  // ---------- stash selection ----------

  function savedSelection() {
    try {
      return window.localStorage.getItem(STORAGE_KEY);
    } catch (e) {
      return null;
    }
  }

  function saveSelection(key) {
    try {
      if (key) window.localStorage.setItem(STORAGE_KEY, key);
    } catch (e) {
      // Storage disabled: just don't remember.
    }
  }

  function pickDefault(keys) {
    var saved = savedSelection();
    if (saved && keys.indexOf(saved) >= 0) return saved;
    for (var i = 0; i < state.stashes.length; i++) {
      if (state.stashes[i].indexedAt != null) return keys[i];
    }
    return keys.length ? keys[0] : null;
  }

  /** "● stash · player @ server" (● online, ○ offline). */
  function stashLabel(s) {
    var who = str(s.owner) || str(s.player);
    return (s.online ? "\u25cf " : "\u25cb ") + str(s.stash) + (who ? " \u00b7 " + who : "") + (streamerOn() ? (str(s.serverLabel) ? " @ " + str(s.serverLabel) : "") : " @ " + str(s.server));
  }

  /** Switches the page to another stash (the stash picker and the Bots overview). */
  function selectStash(key) {
    if (key === state.selected) return;
    state.selected = key || null;
    el.select.value = key || "";
    saveSelection(state.selected);
    clearIndex();
    onStashChanged();
    updateHeader();
    maybeLoadIndex();
  }

  function updateSelect() {
    var keys = state.stashes.map(stashKey);
    if (state.selected === null || keys.indexOf(state.selected) < 0) {
      state.selected = pickDefault(keys);
    }
    var labels = state.stashes.map(stashLabel);
    var opts = el.select.options;
    var same = opts.length === Math.max(1, keys.length) && keys.every(function (k, i) {
      return opts[i].value === k && opts[i].textContent === labels[i];
    });
    if (!same) {
      var frag = document.createDocumentFragment();
      if (!keys.length) {
        var none = document.createElement("option");
        none.value = "";
        none.textContent = "(none)";
        frag.appendChild(none);
      }
      keys.forEach(function (k, i) {
        var o = document.createElement("option");
        o.value = k;
        o.textContent = labels[i];
        frag.appendChild(o);
      });
      el.select.replaceChildren(frag);
    }
    el.select.value = state.selected === null ? "" : state.selected;
    el.select.disabled = keys.length <= 1;
  }

  // ---------- header ----------

  function setDot(dot, online) {
    dot.className = "dot " + (online ? "dot-on" : "dot-off");
  }

  var CONFLICT_SHOW_MS = 24 * 60 * 60 * 1000;

  function conflictText(s) {
    var c = s && s.conflict;
    if (!c || typeof c !== "object" || serverNow() - num(c.at, 0) > CONFLICT_SHOW_MS) return "";
    return "Another bot (" + str(c.player) + ") tried to use stash '" + str(s.stash) + "' at " +
      new Date(num(c.at, 0)).toLocaleString() + ". It was refused: give that bot its own stash name.";
  }

  function updateHeader() {
    updateRescan();
    var warn = conflictText(currentStash());
    el.conflictWarn.hidden = !warn;
    if (el.conflictWarn.textContent !== warn) el.conflictWarn.textContent = warn;
    if (bots.body && bots.body.isConnected) renderBots();
    var s = currentStash();
    if (state.unreachable) {
      setDot(el.dot, false);
      el.botText.textContent = "Server unreachable";
    } else if (!s) {
      setDot(el.dot, false);
      el.botText.textContent = "No stash";
    } else {
      var who = s.player ? str(s.player) + " " : "Bot ";
      setDot(el.dot, !!s.online);
      el.botText.textContent = who + (s.online ? "online" : "offline") + " \u00b7 last seen " + ago(s.lastSeen);
    }

    if (!s) {
      el.infoIndex.textContent = "";
      el.infoContainers.textContent = "";
      el.infoMeta.textContent = "";
      el.indexMeta.textContent = "";
      el.tileContainers.textContent = "\u2013";
      el.tileStale.textContent = "\u2013";
      el.tileIndexed.textContent = "\u2013";
      return;
    }
    if (s.indexedAt != null) {
      el.infoIndex.textContent = "Indexed " + ago(s.indexedAt);
      el.infoIndex.title = new Date(s.indexedAt).toLocaleString();
    } else {
      el.infoIndex.textContent = "No index yet";
      el.infoIndex.title = "";
    }

    var loaded = state.loadedWant !== null && state.loadedWant.indexOf(state.selected + "|") === 0;
    var idx = loaded ? state.index : null;
    var c = idx && idx.containers && typeof idx.containers === "object" ? idx.containers : null;
    el.infoContainers.textContent = c
      ? "Containers: " + grouped(num(c.known, 0)) + " / " + grouped(num(c.total, 0)) + " known \u00b7 " + grouped(num(c.stale, 0)) + " stale"
      : "";
    var meta = [];
    if (idx) {
      if (str(idx.dimension)) meta.push(str(idx.dimension));
      if (str(idx.mcVersion)) meta.push("MC " + str(idx.mcVersion));
    }
    el.infoMeta.textContent = meta.join(" \u00b7 ");
    // The same values for the stash index card (mobile layout).
    el.tileIndexed.textContent = s.indexedAt != null ? ago(s.indexedAt) : "never";
    el.tileContainers.textContent = c ? grouped(num(c.known, 0)) + " / " + grouped(num(c.total, 0)) : "\u2013";
    el.tileStale.textContent = c ? grouped(num(c.stale, 0)) : "\u2013";
    var shortMeta = [];
    if (idx && str(idx.dimension)) shortMeta.push(str(idx.dimension).replace(/^minecraft:/, ""));
    if (idx && str(idx.mcVersion)) shortMeta.push("MC " + str(idx.mcVersion));
    el.indexMeta.textContent = shortMeta.join(" \u00b7 ");
  }

  // ---------- build planner ----------
  // Upload a schematic (the raw file, with the CSRF header), see its materials against a stash, and order what the
  // stash has, in parts of at most 54 shulkers (one delivery chest each). File names and ids come from users and
  // files: textContent only. The server keeps only the computed list, in memory, for 30 minutes after the last use.

  var PL_MAX_BYTES = 8 * 1024 * 1024;
  var PL_UPLOAD_TIMEOUT_MS = 30000;
  var PL_FINISHED = { delivered: true, failed: true, cancelled: true };
  var PL_FORMATS = { litematic: "Litematica (.litematic)", sponge: "Sponge schematic (.schem)", structure: "Structure (.nbt)" };
  var pl = {
    drop: $("pl-drop"), choose: $("pl-choose"), file: $("pl-file"), status: $("pl-status"), body: $("pl-body"),
    facts: $("pl-facts"), expires: $("pl-expires"), newer: $("pl-newer"), stash: $("pl-stash"), mult: $("pl-mult"),
    round: $("pl-round"), fluids: $("pl-fluids"), containers: $("pl-containers"), tiles: $("pl-tiles"),
    order: $("pl-order"), next: $("pl-next"), again: $("pl-again"), locked: $("pl-locked"), csv: $("pl-csv"), txt: $("pl-txt"), copy: $("pl-copy"),
    orderStatus: $("pl-order-status"), filter: $("pl-filter"), onlyMissing: $("pl-only-missing"), rows: $("pl-rows"),
    table: $("pl-table"), unknownBox: $("pl-unknown-box"), unknown: $("pl-unknown"), chestBox: $("pl-chest-box"),
    chest: $("pl-chest"), dismiss: $("pl-dismiss"), craft: $("pl-craft"), craftRow: $("pl-craft-row"), craftText: $("pl-craft-text"),
    craftNote: $("pl-craft-note"), craftBanner: $("pl-craft-banner"), thCraft: $("pl-th-craft")
  };
  var planner = {
    plan: null,           // the last plan JSON from the server (null: nothing uploaded)
    stash: null,          // stash key the plan is shown against
    exclude: new Set(),   // item ids left out
    filter: "",
    onlyMissing: false,
    sort: { key: "", dir: 1 }, // "" = creative order, as the server sends it
    seq: 0,               // bumps on every view request; stale replies are dropped
    busy: false,
    open: false,
    resumed: false,
    names: true,          // the confirmation's "name the shulkers" choice, kept for the next parts
    ordered: null,        // { partsTotal, placed: { "1": "o-..." }, remaining: [2, 3] } for the current options
    orderStatus: {},      // order id -> status, from GET /api/v1/orders
    craftPref: null,      // the user's choice for "Craft what the stash can make": null = not chosen yet (on when the stash can take craft orders)
    names2: null,         // id -> display name of the current plan's rows (built on every render)
    ordersTimer: 0,
    loadTimer: 0
  };

  /** "2 sh + 5 st + 12": shulkers of 27 stacks, stacks, and the rest. */
  function plStacks(n, maxStack) {
    n = Math.max(0, Math.floor(num(n, 0)));
    var ms = Math.max(1, Math.floor(num(maxStack, 64)));
    if (n === 0) return "0";
    var sh = 27 * ms;
    var a = Math.floor(n / sh);
    var r = n - a * sh;
    var parts = [];
    if (a) parts.push(grouped(a) + " sh");
    if (ms > 1) {
      var b = Math.floor(r / ms);
      r -= b * ms;
      if (b) parts.push(b + " st");
    }
    if (r) parts.push(String(r));
    return parts.join(" + ");
  }

  /** "minecraft:oak_planks" -> "Oak Planks" (for items no index has a name for). */
  function plPretty(id) {
    var path = splitId(id)[1];
    return path.split(/[_/]/).filter(function (w) { return w; }).map(function (w) {
      return w.charAt(0).toUpperCase() + w.slice(1);
    }).join(" ") || id;
  }

  function plName(row) {
    return str(row.name) || plPretty(str(row.id));
  }

  function plStatus(text, warn) {
    pl.status.textContent = text || "";
    pl.status.classList.toggle("warn", !!warn);
  }

  function plOrderStatus(text, warn) {
    pl.orderStatus.textContent = text || "";
    pl.orderStatus.classList.toggle("warn", !!warn);
  }

  function plannerStash() {
    for (var i = 0; i < state.stashes.length; i++) {
      if (stashKey(state.stashes[i]) === planner.stash) return state.stashes[i];
    }
    return null;
  }

  /** The stash picker: the stash list the header uses (already limited to the user's scope by the server). */
  function plannerFillStashes() {
    var keys = state.stashes.map(stashKey);
    if (planner.stash === null || keys.indexOf(planner.stash) < 0) {
      planner.stash = keys.indexOf(state.selected) >= 0 ? state.selected : (keys.length ? keys[0] : null);
    }
    var labels = state.stashes.map(function (s) { return stashLabel(s) + (s.indexedAt == null ? " (no index)" : ""); });
    var opts = pl.stash.options;
    var same = opts.length === Math.max(1, keys.length) && keys.every(function (k, i) {
      return opts[i].value === k && opts[i].textContent === labels[i];
    });
    if (!same) {
      var frag = document.createDocumentFragment();
      if (!keys.length) {
        var none = document.createElement("option");
        none.value = "";
        none.textContent = "(no stashes)";
        frag.appendChild(none);
      }
      keys.forEach(function (k, i) {
        var o = document.createElement("option");
        o.value = k;
        o.textContent = labels[i];
        frag.appendChild(o);
      });
      pl.stash.replaceChildren(frag);
    }
    pl.stash.value = planner.stash === null ? "" : planner.stash;
    pl.stash.disabled = keys.length <= 1;
  }

  function plannerOptions() {
    return {
      multiplier: clampInt(pl.mult.value, 1, 64),
      round: pl.round.value === "stack" || pl.round.value === "shulker" ? pl.round.value : "none",
      fluids: pl.fluids.checked,
      containerItems: pl.containers.checked,
      craft: pl.craft.checked && !pl.craftRow.hidden,
      exclude: Array.from(planner.exclude).sort()
    };
  }

  function plannerLocked() {
    return !!(planner.ordered && planner.ordered.locked === true);
  }

  /**
   * Parts are ordered with other options than the page shows (after a reload, or from another tab): put the locked
   * options and stash back and ask again. True when it did (once per locked split, so a stash that is gone can't loop).
   */
  function plannerTakeLockedOptions() {
    var o = planner.ordered;
    if (!plannerLocked() || o.sameOptions !== false || !o.options || typeof o.options !== "object") return false;
    var mark = JSON.stringify([planner.plan && planner.plan.id, o.placed]);
    if (planner.lockTaken === mark) return false;
    planner.lockTaken = mark;
    var opt = o.options;
    pl.mult.value = String(clampInt(opt.multiplier, 1, 64));
    pl.round.value = opt.round === "stack" || opt.round === "shulker" ? opt.round : "none";
    pl.fluids.checked = opt.fluids === true;
    pl.containers.checked = opt.containerItems === true;
    pl.craft.checked = opt.craft === true;
    planner.exclude = new Set(Array.isArray(opt.exclude) ? opt.exclude.filter(function (x) { return typeof x === "string"; }) : []);
    if (typeof o.server === "string" && typeof o.stash === "string") planner.stash = stashKey({ server: o.server, stash: o.stash });
    plannerLoad();
    return true;
  }

  /** While parts are ordered (and not failed or cancelled) the options and the stash can't change. */
  function plannerApplyLock() {
    var locked = plannerLocked();
    [pl.mult, pl.round, pl.fluids, pl.containers].forEach(function (c) { c.disabled = locked; });
    if (locked) {
      pl.stash.disabled = true;
      pl.craft.disabled = true;
    }
    pl.locked.hidden = !locked;
  }

  // The options of the current plan, per browser tab, so a reload (while waiting for a part to be delivered) keeps
  // them, and with them the order split the server remembers for them ("Order next part").
  var PL_SAVE_KEY = "stashManager.planner";

  function plannerSave() {
    try {
      var p = planner.plan;
      if (!p || !p.id) {
        window.sessionStorage.removeItem(PL_SAVE_KEY);
        return;
      }
      var o = plannerOptions();
      o.id = p.id;
      o.stash = planner.stash;
      o.names = planner.names;
      o.craftPref = planner.craftPref;
      window.sessionStorage.setItem(PL_SAVE_KEY, JSON.stringify(o));
    } catch (e) {
      // storage blocked: a reload starts from the defaults
    }
  }

  function plannerRestore(id) {
    var o = null;
    try {
      o = JSON.parse(window.sessionStorage.getItem(PL_SAVE_KEY) || "null");
    } catch (e) {
      o = null;
    }
    if (!o || typeof o !== "object" || o.id !== id) return;
    pl.mult.value = String(clampInt(o.multiplier, 1, 64));
    pl.round.value = o.round === "stack" || o.round === "shulker" ? o.round : "none";
    pl.fluids.checked = o.fluids === true;
    pl.containers.checked = o.containerItems === true;
    planner.exclude = new Set(Array.isArray(o.exclude) ? o.exclude.filter(function (x) { return typeof x === "string"; }) : []);
    if (typeof o.stash === "string") planner.stash = o.stash;
    planner.names = o.names !== false;
    planner.craftPref = typeof o.craftPref === "boolean" ? o.craftPref : null;
  }

  function plannerResetOptions() {
    planner.exclude = new Set();
    planner.ordered = null;
    planner.lockTaken = null;
    planner.orderStatus = {};
    pl.mult.value = "1";
    pl.round.value = "none";
    pl.fluids.checked = false;
    pl.containers.checked = false;
    pl.craft.checked = false;
    planner.craftPref = null;
  }

  /** The page was opened: pick up a plan made earlier in this session (after a reload), or show the current one. */
  function plannerOpen() {
    planner.open = true;
    plannerFillStashes();
    if (planner.plan) {
      plannerLoad();
    } else if (!planner.resumed) {
      planner.resumed = true;
      api("/api/v1/planner").then(function (r) {
        var list = r && Array.isArray(r.plans) ? r.plans : [];
        if (!list.length || planner.plan) return;
        planner.plan = { id: str(list[list.length - 1].id) };
        plannerRestore(planner.plan.id);
        plannerLoad();
      }, function () {});
    }
    plannerRender();
    plannerPollOrders();
  }

  function plannerClose() {
    if (!planner || !planner.open) return;
    planner.open = false;
    window.clearTimeout(planner.ordersTimer);
    window.clearTimeout(planner.loadTimer);
  }

  /** The permissions changed: show or hide the order buttons. */
  function plannerPerms() {
    if (!planner || !pl.order) return;
    plannerRenderActions();
  }

  function plannerFail(e, what) {
    var b = e && e.body;
    var craftText = craftConflictText(e);
    if (craftText) return craftText;
    if (e && e.status === 413) return "That file is larger than 8 MiB.";
    if (e && e.status === 429) return b && b.error ? sentence(str(b.error)) : "Too many uploads; try again later.";
    if (b && b.error) return sentence(str(b.error));
    if (e && e.network) return what + ": server unreachable or too slow.";
    return what + (e && e.status ? " (HTTP " + e.status + ")." : ".");
  }

  function plannerUpload(file) {
    if (!file || planner.busy) return;
    if (file.size > PL_MAX_BYTES) {
      plStatus("That file is larger than 8 MiB.", true);
      return;
    }
    if (!file.size) {
      plStatus("That file is empty.", true);
      return;
    }
    var s = plannerStash();
    var q = "?name=" + encodeURIComponent(String(file.name || "schematic").slice(0, 200));
    if (s) q += "&server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash);
    planner.busy = true;
    pl.choose.disabled = true;
    pl.drop.classList.add("busy");
    plStatus("Reading the schematic...", false);
    api("/api/v1/planner/upload" + q, { method: "POST", raw: file, timeout: PL_UPLOAD_TIMEOUT_MS }).then(function (r) {
      plannerResetOptions();
      // A new plan wins over any view request still in flight (for the old plan): those replies are dropped.
      planner.seq++;
      if (r && r.plan) planner.plan = r.plan;
      plannerSave();
      plStatus("", false);
      plOrderStatus("", false);
      plannerRender();
      toast("Material list ready");
    }, function (e) {
      plStatus(plannerFail(e, "Could not read the file"), true);
    }).then(function () {
      planner.busy = false;
      pl.choose.disabled = false;
      pl.drop.classList.remove("busy");
      pl.file.value = "";
    });
  }

  /** Asks for the plan against the chosen stash and options. */
  function plannerLoad(retried) {
    retried = retried === true;
    window.clearTimeout(planner.loadTimer);
    var p = planner.plan;
    var s = plannerStash();
    if (!p || !p.id || !s) {
      plannerRender();
      return;
    }
    var seq = ++planner.seq;
    // POST with the options as JSON: a long exclude list doesn't fit a URL.
    var body = plannerOptions();
    body.server = s.server;
    body.stash = s.stash;
    api("/api/v1/planner/" + encodeURIComponent(p.id) + "/view", { method: "POST", body: body }).then(function (r) {
      if (seq !== planner.seq || !r || !r.plan || !planner.plan || planner.plan.id !== p.id) return;
      planner.plan = r.plan;
      planner.ordered = r.plan.ordered && typeof r.plan.ordered === "object" ? r.plan.ordered : null;
      if (plannerTakeLockedOptions()) return;
      plannerSave();
      plannerRender();
      plannerPollOrders();
      // The first answer says whether this stash can take craft orders (the box is then on by default): ask again with
      // that option. Recipes still loading: look again in a few seconds.
      if (plannerOptions().craft !== body.craft) plannerScheduleLoad();
      else if (plannerCraftInfo(r.plan).mode === "building" && planner.open) {
        window.clearTimeout(planner.loadTimer);
        planner.loadTimer = window.setTimeout(plannerLoad, 4000);
      }
    }, function (e) {
      if (seq !== planner.seq) return;
      if (e && e.status === 404 && e.body && e.body.error === "no such stash") {
        plOrderStatus("That stash isn't available to your account (or no longer exists). Pick another stash.", true);
      } else if (e && e.status === 404 && planner.plan === p) {
        planner.plan = null;
        planner.ordered = null;
        plannerRender();
        plannerSave();
        plStatus("This material list has ended (30 minutes unused, 2 hours at most, or you logged out). Upload the file again.", true);
      } else if (e && (e.status === 503 || e.status === 429) && e.body && typeof e.body.retryAfter === "number") {
        var wait = Math.max(1, Math.min(60, Math.ceil(e.body.retryAfter)));
        plOrderStatus("Busy, retrying in " + wait + " s.", true);
        if (!retried) {
          window.clearTimeout(planner.loadTimer);
          planner.loadTimer = window.setTimeout(function () {
            if (planner.plan && planner.plan.id === p.id) plannerLoad(true);
          }, wait * 1000);
        }
      } else {
        plOrderStatus(plannerFail(e, "Could not update the list"), true);
      }
    });
  }

  function plannerScheduleLoad() {
    window.clearTimeout(planner.loadTimer);
    planner.loadTimer = window.setTimeout(plannerLoad, 250);
  }

  // ---------- build planner: crafting from the stash ----------
  // The server decides what is craftable (CRAFT-CONTRACT.md); this only shows it. With an older server there is no
  // "craft" in the answer and nothing here shows.

  /** mode: "none" (old server, or no crafting info at all), "off" (no recipes for this version), "building", "on". */
  function plannerCraftInfo(p) {
    var c = p && p.craft && typeof p.craft === "object" ? p.craft : null;
    if (!c) return { mode: "none", c: null };
    if (c.available === true) return { mode: "on", c: c };
    var rec = c.recipes && typeof c.recipes === "object" ? c.recipes : {};
    return { mode: rec.state === "building" ? "building" : "off", c: c };
  }

  function plItemName(id) {
    var m = planner.names2;
    if (m && m.has(id)) return m.get(id);
    var it = itemByKey(id);
    return it ? it.name : plPretty(id);
  }

  /** "6 Spruce Log, 12 Birch Log, and 2 more" for a list of { id, count }. */
  function plCountsText(list, max) {
    var arr = Array.isArray(list) ? list.filter(function (e) {
      return e && typeof e === "object" && typeof e.id === "string" && num(e.count, 0) > 0;
    }) : [];
    var shown = arr.slice(0, max || 6).map(function (e) { return grouped(e.count) + " " + plItemName(e.id); });
    if (arr.length > shown.length) shown.push("and " + (arr.length - shown.length) + " more");
    return shown.join(", ");
  }

  /** The planner's own wording for why something can't be made, in plainer words where it is terse. */
  function plReasonText(reason) {
    var t = str(reason);
    if (t === "too deep") return "the recipe chain is too long";
    if (t === "a recipe loop") return "its recipes only lead back to each other";
    if (t === "plan too large") return "the crafting plan got too large to finish";
    return t;
  }

  /** Why the bot can't use a table (craftOrders.tableWhy), as a sentence. */
  var CRAFT_WHY_WORDS = {
    no_area: "No stash area recorded yet, so the bot can't check the table's distance.",
    no_dimension: "The stash's dimension isn't recorded yet, so the bot can't check the table.",
    wrong_dimension: "The table is in a different dimension than the stash.",
    too_far: "The table is more than 64 blocks from the stash.",
    not_table: "There is no crafting table at that spot.",
    no_stand: "The bot can't find a spot to stand next to the table.",
    path_failed: "The bot can't find a path to the table.",
    not_loaded: "The table's chunk isn't loaded, so the bot can't check it yet.",
    no_material: "The bot found no crafting table nearby and the stash has no crafting table, planks or logs to make one."
  };

  function craftWhyText(why) {
    return typeof why === "string" && Object.prototype.hasOwnProperty.call(CRAFT_WHY_WORDS, why) ? CRAFT_WHY_WORDS[why] : "";
  }

  var PL_TABLE_WORDS = {
    auto: "the bot will find, place or craft one",
    unset: "no crafting table is set",
    unloaded: "the bot hasn't checked it yet",
    not_table: "there is no crafting table at that spot",
    unreachable: "the bot can't reach it",
    other_dimension: "it is in a different dimension than the stash",
    unknown: "the bot hasn't reported on it yet"
  };

  /** Why this stash can't take craft orders, as a sentence (from the code the server gives). */
  function plCraftReasonText(c) {
    var rec = c.recipes && typeof c.recipes === "object" ? c.recipes : {};
    var bot = c.bot && typeof c.bot === "object" ? c.bot : {};
    switch (str(c.reason)) {
      case "no_recipes": return "Crafting info isn't available for " + (str(rec.mc) || "this Minecraft version") + ".";
      case "no_bot":
        return bot.supports === true
          ? "The bot has crafting turned off. Turn on crafting and craft orders in its settings."
          : "This stash's bot can't craft orders (needs StashSorter 1.15.0+).";
      case "recipes_differ": return "The bot's recipe table differs from the server's. Update or restart the bot so they match.";
      case "no_table":
        if (/^Waiting for the bot/.test(str(c.message))) return "Waiting for the bot to pick up the latest settings.";
        if (craftWhyText(c.tableWhy)) return craftWhyText(c.tableWhy);
        return "Set a crafting table for this stash to order crafted items, or use a bot that can find, place or craft one.";
      case "table_bad":
        if (craftWhyText(c.tableWhy)) return "The crafting table isn't usable. " + craftWhyText(c.tableWhy);
        if (str(c.tableStatus) === "unloaded") return "The bot hasn't checked the crafting table yet.";
        return "The crafting table isn't usable: " + (PL_TABLE_WORDS[str(c.tableStatus)] || "the bot can't use it") + ".";
      default: return str(c.message) ? sentence(str(c.message)) : "Crafting orders aren't available for this stash.";
    }
  }

  /** Goes to the Kits tab of the planner's stash, where the crafting table is set. */
  function plannerOpenCraftSettings() {
    var s = plannerStash();
    if (s) selectStash(stashKey(s));
    showPage("stash");
    selectTab("kits");
  }

  function plNote(box, text, kind, linkText) {
    var d = kitNote(box, text, kind);
    if (linkText) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "mc-button small";
      b.textContent = linkText;
      b.addEventListener("click", plannerOpenCraftSettings);
      d.appendChild(b);
    }
  }

  /** What the rows take from the stash in all, biggest first. */
  function plCraftTake(p) {
    var m = new Map();
    p.items.forEach(function (r) {
      if (r.excluded || !Array.isArray(r.craftUses)) return;
      r.craftUses.forEach(function (u) {
        if (u && typeof u.id === "string") m.set(u.id, (m.get(u.id) || 0) + num(u.count, 0));
      });
    });
    return Array.from(m.entries()).sort(function (a, b) { return b[1] - a[1]; }).map(function (e) { return { id: e[0], count: e[1] }; });
  }

  /** The banners, the craft box and the Craftable header for the plan's craft state (the table cells are in the rows). */
  function plannerRenderCraft(p) {
    var info = plannerCraftInfo(p);
    var c = info.c;
    var names = new Map();
    p.items.forEach(function (r) { names.set(str(r.id), plName(r)); });
    planner.names2 = names;
    pl.thCraft.hidden = info.mode !== "on" && info.mode !== "building";
    var box = pl.craftBanner;
    box.replaceChildren();
    var canLink = can("view") && !forcedStreamer();
    var canOrder = info.mode === "on" && c.canOrder === true;
    if (info.mode === "off" && c) {
      plNote(box, plCraftReasonText(c), "");
    } else if (info.mode === "building") {
      var rec = c.recipes && typeof c.recipes === "object" ? c.recipes : {};
      plNote(box, "Loading recipes for " + (str(rec.mc) || "this version") + "…", "");
    } else if (info.mode === "on") {
      if (!canOrder) {
        var why = str(c.reason);
        plNote(box, plCraftReasonText(c), why === "no_table" || why === "table_bad" ? "warn" : "",
          (why === "no_table" || why === "table_bad") && canLink ? "Open the crafting table setting" : "");
      }
      if (c.truncated === true) plNote(box, "The crafting plan was cut short; some items show as missing.", "warn");
    }
    box.hidden = !box.firstChild;
    // The craft box: only with recipes. On by default when the stash can take craft orders.
    var show = info.mode === "on";
    pl.craftRow.hidden = !show;
    pl.craftNote.hidden = true;
    if (!show) {
      pl.craft.checked = false;
      return;
    }
    var locked = plannerLocked();
    var on;
    if (locked && planner.ordered.options && typeof planner.ordered.options === "object") on = planner.ordered.options.craft === true;
    else on = canOrder && planner.craftPref !== false;
    pl.craft.checked = on;
    pl.craft.disabled = locked || !canOrder;
    var t = p.totals && typeof p.totals === "object" ? p.totals : {};
    var n = num(t.craftable, 0);
    if (!canOrder) {
      pl.craftText.textContent = "Craft what the stash can make";
      pl.craftNote.textContent = "Not available: " + plCraftReasonText(c).replace(/^./, function (ch) { return ch.toLowerCase(); });
      pl.craftNote.hidden = false;
    } else {
      var take = plCraftTake(p);
      pl.craftText.textContent = "Craft what the stash can make (" + (n > 0
        ? grouped(n) + " " + (n === 1 ? "item" : "items") + " from " + plCountsText(take, 3)
        : "nothing to craft here") + ")";
      pl.craftNote.textContent = "The bot takes the ingredients from the stash, crafts them at the crafting table and delivers the result with the order.";
      pl.craftNote.hidden = false;
    }
  }

  /** The missing amount the page shows: after crafting when the server says (older servers: as before). */
  function plMissing(r) {
    return typeof r.missingAfterCraft === "number" ? r.missingAfterCraft : num(r.missing, 0);
  }

  /** The hover text of a Craft cell: what it uses, what it makes, what goes back. */
  function plCraftTip(r) {
    var st = str(r.craftStatus);
    if (r.excluded) return "";
    if (st === "none") return "Nothing to make: the stash already has enough.";
    if (st === "no_recipe") return "No crafting-table recipe makes this.";
    if (st === "station") return "Can't be made at a crafting table: " + plReasonText(r.craftReason) + ".";
    var lines = [];
    if (st === "short") lines.push("Can't make any: " + (plReasonText(r.craftReason) || "not enough ingredients") + ".");
    var uses = plCountsText(r.craftUses, 8);
    if (uses) lines.push("Uses from the stash: " + uses);
    var steps = Array.isArray(r.craftSteps) ? r.craftSteps.filter(function (s) { return s && typeof s === "object" && typeof s.result === "string"; }) : [];
    if (steps.length) {
      lines.push("Makes: " + steps.map(function (s) {
        var fills = num(s.fills, 0);
        var from = plCountsText(s.inputs, 4);
        return grouped(num(s.made, 0)) + " " + plItemName(s.result) +
          (from ? " from " + from : fills > 1 ? " (" + fills + " crafts)" : "");
      }).join("; "));
    }
    var left = plCountsText(r.craftLeftovers, 6);
    if (left) lines.push("Back to the stash: " + left);
    if (st === "partial" && str(r.craftReason)) lines.push("Can't make the rest: " + plReasonText(r.craftReason) + ".");
    return lines.join("\n");
  }

  /** The Craftable cell (only when the Craftable column is shown). */
  function plCraftCell(r, mode) {
    var td = document.createElement("td");
    td.className = "pl-c-num";
    if (mode === "building") {
      td.textContent = "…";
      td.title = "Loading recipes";
      return td;
    }
    var st = str(r.craftStatus);
    var n = num(r.craftable, 0);
    if (r.excluded || !st || st === "none" || st === "no_recipe" || st === "station" || st === "excluded") {
      td.textContent = "–";
    } else if (n > 0) {
      td.textContent = plStacks(n, r.maxStack);
      td.classList.add("pl-craftable");
    } else {
      td.textContent = "0";
    }
    td.title = plCraftTip(r);
    return td;
  }

  /** The Missing cell: "craftable" when the stash can make all of it, the rest in red with "+N craftable" when part. */
  function plMissingCell(r, ms) {
    var missing = num(r.missing, 0);
    if (typeof r.missingAfterCraft !== "number" || r.excluded || missing <= 0) {
      return plNumCell(typeof r.missingAfterCraft === "number" && !r.excluded ? r.missingAfterCraft : r.missing, ms,
        num(r.missing, 0) > 0 && !r.excluded ? "pl-missing" : "");
    }
    var after = r.missingAfterCraft;
    var made = Math.max(0, missing - after);
    var td = document.createElement("td");
    td.className = "pl-c-num";
    if (after === 0) {
      td.textContent = "craftable";
      td.classList.add("pl-craft-covered");
      td.title = grouped(missing) + " missing from the stash; all of it can be crafted";
      return td;
    }
    td.classList.add("pl-missing");
    td.appendChild(document.createTextNode(plStacks(after, ms)));
    if (made > 0) {
      td.appendChild(line("pl-craft-plus", "+" + grouped(made) + " craftable"));
      td.title = grouped(after) + " still missing after crafting (" + grouped(missing) + " missing, " + grouped(made) + " can be crafted)";
    } else {
      td.title = grouped(after);
    }
    return td;
  }

  /** The sentence for a 409 { craft: code } from placing an order, or "". */
  function craftConflictText(e) {
    var kitCode = e && e.body && typeof e.body.kit === "string" ? e.body.kit : "";
    if (kitCode === "no_bot") return "This stash's bot can't build kits yet (it needs a bot with kit orders). Remove the kit lines or wait for the bot to be updated.";
    if (kitCode === "disabled") return "Kit orders are turned off on the bot. Turn them on, or remove the kit lines.";
    if (kitCode === "deleted") return "That kit was deleted, so the order can't be placed. Remove the kit line.";
    var code = e && e.status === 409 && e.body && typeof e.body.craft === "string" ? e.body.craft : "";
    var tail = " Untick “Craft what the stash can make” to order only what the stash holds.";
    switch (code) {
      case "no_recipes": return "Crafting info isn't available for this stash's Minecraft version." + tail;
      case "no_bot": return "This stash's bot can't craft orders (it needs StashSorter 1.15.0+ with crafting turned on)." + tail;
      case "recipes_differ": return "The bot's recipe table differs from the server's, so it can't craft orders yet." + tail;
      case "no_table":
        if (/Waiting for the bot/.test(str(e.body.error))) return "Waiting for the bot to pick up the latest settings. Try again in a moment." + tail;
        return "No crafting table is set for this stash. Set one in the Kits tab, under Crafting table." + tail;
      case "table_bad": return "The crafting table isn't usable right now: the bot can't find or reach it." + tail;
      default: return "";
    }
  }

  // ---------- build planner: rendering ----------

  function plannerRender() {
    var p = planner.plan;
    var full = !!(p && p.items);
    pl.body.hidden = !full;
    pl.dismiss.hidden = !p;
    if (!full) return;
    plannerFillStashes();
    plannerRenderFacts(p);
    plannerRenderCraft(p);
    plannerRenderTiles(p);
    plannerRenderRows(p);
    plannerRenderSimple(pl.unknownBox, pl.unknown, Array.isArray(p.unknown) ? p.unknown : [], num(p.unknownOmitted, 0), false);
    plannerRenderSimple(pl.chestBox, pl.chest, Array.isArray(p.containerItems) ? p.containerItems : [], 0, true);
    plannerRenderActions();
    plannerApplyLock();
  }

  function plFact(dl, label, value) {
    var dt = document.createElement("dt");
    dt.textContent = label;
    var dd = document.createElement("dd");
    if (typeof value === "string") dd.textContent = value;
    else dd.appendChild(value);
    dl.appendChild(dt);
    dl.appendChild(dd);
  }

  function plannerRenderFacts(p) {
    var frag = document.createDocumentFragment();
    var dl = document.createElement("div");
    dl.className = "pl-facts-inner";
    plFact(dl, "File", hiddenSpan(str(p.fileName)));
    plFact(dl, "Format", (PL_FORMATS[p.format] || str(p.format)) + (num(p.formatVersion, 0) > 0 ? ", version " + p.formatVersion : ""));
    var size = num(p.size, 0);
    plFact(dl, "Size", size >= 1048576 ? (size / 1048576).toFixed(1) + " MiB" : Math.max(1, Math.round(size / 1024)) + " KiB");
    var d = p.dimensions && typeof p.dimensions === "object" ? p.dimensions : {};
    plFact(dl, "Dimensions", num(d.x, 0) + " × " + num(d.y, 0) + " × " + num(d.z, 0) + (num(p.regions, 1) > 1 ? " (" + p.regions + " regions)" : ""));
    plFact(dl, "Blocks", grouped(num(p.blocks, 0)));
    plFact(dl, "Block table", "Minecraft " + str(p.tableVersion) + (num(p.dataVersion, 0) > 0 ? " (file: data version " + p.dataVersion + ")" : " (the file has no data version)"));
    while (dl.firstChild) frag.appendChild(dl.firstChild);
    pl.facts.replaceChildren(frag);
    var newer = p.newerThanTable === true;
    pl.newer.hidden = !newer;
    pl.newer.textContent = newer ? "This schematic was saved with a newer Minecraft (data version " + p.dataVersion + ") than the newest block table (" +
      str(p.tableVersion) + ", data version " + p.tableDataVersion + "). Blocks added since then show as unknown." : "";
    var exp = num(p.expiresAt, 0);
    pl.expires.textContent = exp ? "Kept until " + new Date(exp - state.clockSkew).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) +
      " unless used" : "";
  }

  function plTile(label, value, sub, cls) {
    var t = document.createElement("div");
    t.className = "pl-tile" + (cls ? " " + cls : "");
    t.appendChild(line("pl-tile-label", label));
    t.appendChild(line("pl-tile-value", value));
    if (sub) t.appendChild(line("pl-tile-sub", sub));
    return t;
  }

  function plannerRenderTiles(p) {
    var t = p.totals && typeof p.totals === "object" ? p.totals : {};
    var st = p.stash && typeof p.stash === "object" ? p.stash : null;
    var frag = document.createDocumentFragment();
    frag.appendChild(plTile("Needed", grouped(num(t.need, 0)), plural(String(p.items.filter(function (r) { return !r.excluded; }).length), "kind")));
    frag.appendChild(plTile("Available", grouped(num(t.available, 0)), "in the stash, after open orders", "ok"));
    var cinfo = plannerCraftInfo(p);
    var withCraft = cinfo.mode === "on" && typeof t.craftable === "number";
    var missing = withCraft && typeof t.missingAfterCraft === "number" ? t.missingAfterCraft : num(t.missing, 0);
    var missingSub = "nothing missing";
    if (missing > 0) missingSub = withCraft && num(t.craftable, 0) > 0 ? "still missing after crafting" : "not in the stash";
    else if (withCraft && num(t.missing, 0) > 0) missingSub = "nothing missing after crafting";
    frag.appendChild(plTile("Missing", grouped(missing), missingSub, missing > 0 ? "bad" : "ok"));
    if (withCraft) {
      var kinds = num(t.craftKinds, 0);
      var sub = cinfo.c.canOrder === true ? plural(String(kinds), "kind") + ", from the stash" : "can't be ordered yet (see above)";
      frag.appendChild(plTile("Craftable", grouped(num(t.craftable, 0)), sub, num(t.craftable, 0) > 0 ? "craft" : ""));
    } else if (cinfo.mode === "building") {
      frag.appendChild(plTile("Craftable", "…", "loading recipes"));
    }
    if (st && st.indexed && typeof t.shulkers === "number") {
      // With the craft box ticked the crafted items are ordered too.
      var withCraftOn = pl.craft.checked && !pl.craftRow.hidden && !plannerLocked();
      var toOrder = withCraftOn ? (typeof t.wantWithCraft === "number" ? t.wantWithCraft : num(t.want, 0) + num(t.craftable, 0)) : num(t.want, 0);
      frag.appendChild(plTile("To order", grouped(toOrder), plural(String(num(t.lines, 0)), "line")));
      frag.appendChild(plTile("Shulkers", grouped(t.shulkers), plural(String(num(t.parts, 0)), "order") + " of up to 54"));
    } else {
      frag.appendChild(plTile("To order", "–", "this stash has no index yet"));
    }
    pl.tiles.replaceChildren(frag);
  }

  function plSortValue(r, key) {
    if (key === "name") return plName(r).toLowerCase();
    if (key === "missing") return plMissing(r);
    return num(r[key], 0);
  }

  function plannerVisibleRows(p) {
    var f = planner.filter.trim().toLowerCase();
    var rows = p.items.filter(function (r) {
      if (planner.onlyMissing && !(plMissing(r) > 0 && !r.excluded)) return false;
      if (!f) return true;
      return plName(r).toLowerCase().indexOf(f) >= 0 || str(r.id).toLowerCase().indexOf(f) >= 0;
    });
    var key = planner.sort.key;
    if (key) {
      var dir = planner.sort.dir;
      rows = rows.map(function (r, i) { return { r: r, i: i }; });
      rows.sort(function (a, b) {
        var x = plSortValue(a.r, key);
        var y = plSortValue(b.r, key);
        return (x < y ? -dir : x > y ? dir : 0) || a.i - b.i;
      });
      rows = rows.map(function (w) { return w.r; });
    }
    return rows;
  }

  function plIcon(r) {
    var img = document.createElement("img");
    img.className = "pl-icon";
    img.width = 24;
    img.height = 24;
    img.alt = "";
    img.loading = "lazy";
    img.decoding = "async";
    var tries = 0;
    var url = r.icon === true ? iconUrl(str(r.id), str(r.mc) || DEFAULT_MC) : PLACEHOLDER;
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      tries++;
      if (url === PLACEHOLDER || tries > 5) return;
      window.setTimeout(function () {
        if (img.isConnected) img.src = url + "&retry=" + tries;
      }, retryDelay(tries));
    });
    img.src = url;
    return img;
  }

  function plNumCell(n, maxStack, cls, dash) {
    var td = document.createElement("td");
    td.className = "pl-c-num" + (cls ? " " + cls : "");
    if (dash) {
      td.textContent = "–";
      td.title = "Not in this stash's index";
    } else {
      td.textContent = plStacks(n, maxStack);
      td.title = grouped(num(n, 0));
    }
    return td;
  }

  function plannerRenderRows(p) {
    var rows = plannerVisibleRows(p);
    var craftMode = plannerCraftInfo(p).mode;
    var frag = document.createDocumentFragment();
    rows.forEach(function (r) {
      var tr = document.createElement("tr");
      if (r.excluded) tr.className = "pl-excluded";
      var name = plName(r);
      var tdEx = document.createElement("td");
      tdEx.className = "pl-c-ex";
      var box = document.createElement("input");
      box.type = "checkbox";
      box.checked = !r.excluded;
      box.disabled = plannerLocked();
      box.setAttribute("aria-label", "Include " + name);
      box.addEventListener("change", function () {
        if (box.checked) planner.exclude.delete(str(r.id));
        else planner.exclude.add(str(r.id));
        r.excluded = !box.checked;
        tr.classList.toggle("pl-excluded", r.excluded);
        plannerScheduleLoad();
      });
      tdEx.appendChild(box);
      tr.appendChild(tdEx);
      var tdIcon = document.createElement("td");
      tdIcon.className = "pl-c-icon";
      tdIcon.appendChild(plIcon(r));
      tr.appendChild(tdIcon);
      var tdName = document.createElement("td");
      tdName.className = "pl-c-name";
      tdName.appendChild(line("pl-name", name));
      var from = r.from && typeof r.from === "object" ? r.from : {};
      var extra = [];
      if (num(from.containers, 0) > 0) extra.push(grouped(from.containers) + " from chests");
      if (num(from.fluids, 0) > 0) extra.push("source blocks");
      if (num(r.variants, 0) > 0) extra.push("+" + grouped(r.variants) + " with extra data, not counted");
      var idLine = line("pl-id", str(r.id) + (extra.length ? " · " + extra.join(" · ") : ""));
      tdName.appendChild(idLine);
      tr.appendChild(tdName);
      var ms = num(r.maxStack, 64);
      tr.appendChild(plNumCell(r.need, ms));
      tr.appendChild(plNumCell(r.inStash, ms, "", r.inIndex !== true));
      tr.appendChild(plNumCell(r.available, ms, "", r.inIndex !== true));
      if (craftMode === "on" || craftMode === "building") tr.appendChild(plCraftCell(r, craftMode));
      tr.appendChild(plMissingCell(r, ms));
      frag.appendChild(tr);
    });
    if (!rows.length) {
      var tr = document.createElement("tr");
      var td = document.createElement("td");
      td.colSpan = craftMode === "on" || craftMode === "building" ? 8 : 7;
      td.className = "pl-empty";
      td.textContent = p.items.length ? "Nothing matches." : "The schematic has no blocks that are items.";
      tr.appendChild(td);
      frag.appendChild(tr);
    }
    pl.rows.replaceChildren(frag);
    Array.prototype.forEach.call(pl.table.querySelectorAll("th[aria-sort]"), function (th) {
      var b = th.querySelector(".pl-sort");
      var on = b && b.dataset.sort === planner.sort.key;
      th.setAttribute("aria-sort", on ? (planner.sort.dir > 0 ? "ascending" : "descending") : "none");
    });
  }

  function plannerRenderSimple(box, host, list, omitted, icons) {
    box.hidden = !list.length && !omitted;
    var frag = document.createDocumentFragment();
    list.forEach(function (e) {
      var row = document.createElement("div");
      row.className = "pl-simple-row";
      if (icons) {
        var info = null;
        if (planner.plan) {
          for (var i = 0; i < planner.plan.items.length; i++) if (planner.plan.items[i].id === e.id) info = planner.plan.items[i];
        }
        row.appendChild(plIcon(info || { id: e.id, icon: false }));
        row.appendChild(themeSpan("pl-name", info ? plName(info) : plPretty(str(e.id))));
      } else {
        row.appendChild(themeSpan("pl-name", str(e.id)));
      }
      row.appendChild(themeSpan("pl-count", "× " + grouped(num(e.count, 0))));
      frag.appendChild(row);
    });
    if (omitted > 0) frag.appendChild(line("note", "and " + grouped(omitted) + " more blocks of other unknown kinds"));
    host.replaceChildren(frag);
  }

  /** The order buttons: "Order build materials now", then "Order next part (i/n)" once parts are placed. */
  function plannerRenderActions() {
    var p = planner.plan;
    var st = p && p.stash && typeof p.stash === "object" ? p.stash : null;
    var mayOrder = can("plannerOrder");
    var t = p && p.totals ? p.totals : {};
    var o = planner.ordered;
    // Placed parts of the split for the options shown (other options can only be previewed while it is locked).
    var started = !!(o && o.placed && Object.keys(o.placed).length && (o.sameOptions !== false || o.locked === true));
    pl.order.hidden = !mayOrder || started;
    var craftLines = pl.craft.checked && !pl.craftRow.hidden && num(t.lines, 0) > 0;
    pl.order.disabled = !st || !st.indexed || !(num(t.want, 0) > 0 || craftLines);
    pl.next.hidden = true;
    pl.again.hidden = true;
    if (!mayOrder || !started) return;
    var remaining = Array.isArray(o.remaining) ? o.remaining : [];
    var again = Array.isArray(o.again) ? o.again : [];
    var total = num(o.partsTotal, 0);
    if (again.length) {
      // A part whose order failed or was cancelled: never delivered, so it may be ordered again.
      pl.again.hidden = false;
      pl.again.disabled = false;
      pl.again.textContent = "Order part " + again[0] + " again (" + again[0] + "/" + total + ")";
    }
    if (!remaining.length) {
      plOrderStatus(again.length
        ? "Part " + again.join(", ") + " failed or was cancelled; order it again when the bot is ready."
        : "All " + plural(String(total), "part") + " ordered. Dismiss and upload the file again for a new order.", again.length > 0);
      return;
    }
    var next = remaining[0];
    var placedNums = Object.keys(o.placed).map(Number).sort(function (a, b) { return a - b; });
    var lastId = o.placed[String(placedNums[placedNums.length - 1])];
    var last = planner.orderStatus[lastId];
    pl.next.hidden = false;
    pl.next.textContent = "Order next part (" + next + "/" + total + ")";
    pl.next.disabled = !PL_FINISHED[last];
    if (!last) {
      plOrderStatus("Part " + placedNums[placedNums.length - 1] + " ordered (" + lastId + "). Checking its status...", false);
    } else if (!PL_FINISHED[last]) {
      plOrderStatus("Part " + placedNums[placedNums.length - 1] + " is " + (STATUS_LABEL[last] || last).toLowerCase() +
        ". One order fills the delivery chest, so order the next part once this one is delivered.", false);
    } else if (last !== "delivered") {
      plOrderStatus("Part " + placedNums[placedNums.length - 1] + " " + last + ". You can still order the next part.", true);
    } else {
      plOrderStatus("Part " + placedNums[placedNums.length - 1] + " delivered. Empty the delivery chest, then order the next part.", false);
    }
  }

  /** While parts are placed and not all delivered, follow their status (the stash's order list). */
  function plannerPollOrders() {
    window.clearTimeout(planner.ordersTimer);
    var o = planner.ordered;
    var s = plannerStash();
    if (!planner.open || !o || !o.placed || !Object.keys(o.placed).length || !s) return;
    api("/api/v1/orders?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (r) {
      var list = r && Array.isArray(r.orders) ? r.orders : [];
      list.forEach(function (ord) { if (ord && typeof ord.id === "string") planner.orderStatus[ord.id] = str(ord.status); });
      // A placed part that failed or was cancelled since: ask the server again (it may be ordered again, and the
      // options may unlock).
      var again = Array.isArray(o.again) ? o.again.map(String) : [];
      var changed = Object.keys(o.placed).some(function (part) {
        var st = planner.orderStatus[o.placed[part]];
        return (st === "failed" || st === "cancelled") && again.indexOf(part) < 0;
      });
      if (changed) plannerScheduleLoad();
      plannerRenderActions();
    }, function () {}).then(function () {
      var cur = planner.ordered;
      var open = cur && cur.placed && Object.keys(cur.placed).some(function (part) { return !PL_FINISHED[planner.orderStatus[cur.placed[part]]]; });
      if (planner.open && cur && ((Array.isArray(cur.remaining) && cur.remaining.length) || open)) {
        planner.ordersTimer = window.setTimeout(plannerPollOrders, POLL_MS);
      }
    });
  }

  // ---------- build planner: export ----------

  function plannerMissingRows() {
    var p = planner.plan;
    return p && p.items ? p.items.filter(function (r) { return !r.excluded && plMissing(r) > 0; }) : [];
  }

  function plCsvField(v) {
    var s = String(v);
    // A leading = + - @ would be read as a formula by spreadsheets.
    if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
    return /[",\n\r]/.test(s) ? "\"" + s.replace(/"/g, "\"\"") + "\"" : s;
  }

  function plannerMissingText(kind) {
    var rows = plannerMissingRows();
    if (kind === "csv") {
      var out = ["item,name,missing,stacks"];
      rows.forEach(function (r) {
        out.push([plCsvField(str(r.id)), plCsvField(plName(r)), plMissing(r), plCsvField(plStacks(plMissing(r), r.maxStack))].join(","));
      });
      return out.join("\r\n") + "\r\n";
    }
    var lines = ["Missing materials" + (plannerCraftInfo(planner.plan).mode === "on" ? " after crafting" : "") + " (" + plural(String(rows.length), "kind") + ")"];
    rows.forEach(function (r) {
      lines.push("- " + plName(r) + ": " + grouped(plMissing(r)) + " (" + plStacks(plMissing(r), r.maxStack) + ")");
    });
    return lines.join("\n") + "\n";
  }

  function plannerDownload(kind) {
    if (!plannerMissingRows().length) {
      toast("Nothing is missing");
      return;
    }
    var blob = new Blob([plannerMissingText(kind)], { type: kind === "csv" ? "text/csv;charset=utf-8" : "text/plain;charset=utf-8" });
    var url = URL.createObjectURL(blob);
    var a = document.createElement("a");
    a.href = url;
    a.download = "missing-materials." + (kind === "csv" ? "csv" : "txt");
    a.hidden = true;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
  }

  pl.csv.addEventListener("click", function () { plannerDownload("csv"); });
  pl.txt.addEventListener("click", function () { plannerDownload("txt"); });
  pl.copy.addEventListener("click", function () {
    if (!plannerMissingRows().length) {
      toast("Nothing is missing");
      return;
    }
    var text = plannerMissingText("txt");
    var done = function () { toast("Copied the missing materials"); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { toast("Could not copy: use Export instead"); });
    } else {
      toast("Copying isn't available here: use Export instead");
    }
  });

  // ---------- build planner: ordering ----------

  function plannerOrderBody(extra) {
    var s = plannerStash();
    var o = plannerOptions();
    o.server = s.server;
    o.stash = s.stash;
    o.names = planner.names;
    Object.keys(extra || {}).forEach(function (k) { o[k] = extra[k]; });
    return o;
  }

  /** The bot's state for the stash: the warnings the cart shows too (offline, can't take orders, no delivery box). */
  function plannerBotWarnings(s) {
    var out = [];
    if (!s) return out;
    if (!s.online) out.push("The bot is offline. The order starts when it reconnects.");
    if (str(s.orderBlocker)) out.push("The bot can't take orders right now: " + str(s.orderBlocker) + ".");
    var d = s.delivery;
    if (!(d && typeof d === "object" && typeof d.x === "number")) out.push("No delivery container is set on the bot.");
    return out;
  }

  function plannerPlace(part, onDone) {
    var s = plannerStash();
    var p = planner.plan;
    if (!s || !p) return;
    plOrderStatus("Placing part " + part + "...", false);
    return api("/api/v1/planner/" + encodeURIComponent(p.id) + "/order", { method: "POST", body: plannerOrderBody({ part: part }) }).then(function (r) {
      planner.ordered = { partsTotal: num(r.partsTotal, 1), placed: r.placed || {}, remaining: Array.isArray(r.remaining) ? r.remaining : [],
        again: Array.isArray(r.again) ? r.again : [], locked: true, sameOptions: true };
      plannerApplyLock();
      if (r.order && typeof r.order.id === "string") planner.orderStatus[r.order.id] = str(r.order.status);
      toast("Part " + part + " of " + num(r.partsTotal, 1) + " ordered");
      plannerRenderActions();
      plannerPollOrders();
      plannerScheduleLoad();
      if (onDone) onDone(true);
    }, function (e) {
      plOrderStatus(plannerFail(e, "Could not place the order"), true);
      if (onDone) onDone(false, e);
    });
  }

  function plannerConfirm(dry) {
    var s = plannerStash();
    var p = planner.plan;
    var parts = Array.isArray(dry.parts) ? dry.parts : [];
    var body = document.createElement("div");
    body.className = "pl-confirm";
    var total = num(dry.partsTotal, parts.length);
    body.appendChild(line("window-info", total > 1
      ? "This needs " + total + " orders. One order fills at most 54 shulkers (one double chest), so the bot delivers it part by part: order the next part once the previous one is delivered."
      : "This fits in one order."));
    var table = document.createElement("table");
    table.className = "pl-parts";
    var head = document.createElement("tr");
    ["Part", "Lines", "Shulkers", "Items"].forEach(function (h) {
      var th = document.createElement("th");
      th.scope = "col";
      th.textContent = h;
      head.appendChild(th);
    });
    var thead = document.createElement("thead");
    thead.appendChild(head);
    table.appendChild(thead);
    var tbody = document.createElement("tbody");
    parts.forEach(function (pt) {
      var tr = document.createElement("tr");
      [String(pt.part) + (pt.order ? " (ordered)" : ""), grouped(num(pt.lineCount, 0)), grouped(num(pt.shulkers, 0)), grouped(num(pt.items, 0))]
        .forEach(function (v) {
          var td = document.createElement("td");
          td.textContent = v;
          tr.appendChild(td);
        });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    body.appendChild(table);
    var nameOf = function (id) {
      for (var i = 0; i < p.items.length; i++) if (p.items[i].id === id) return plName(p.items[i]);
      return plPretty(id);
    };
    var craftLinesOf = function (pt) { return Array.isArray(pt.lines) ? pt.lines.filter(function (l) { return l && l.type === "craft"; }) : []; };
    var craftOn = parts.some(function (pt) { return craftLinesOf(pt).length > 0; });
    var rowOf = function (id) {
      for (var i = 0; i < p.items.length; i++) if (p.items[i].id === id) return p.items[i];
      return null;
    };
    var na = Array.isArray(dry.notAvailable) ? dry.notAvailable : [];
    if (craftOn) {
      // What the stash can make is ordered as craft lines, so it is not left out.
      na = na.filter(function (e) { var row = rowOf(str(e.id)); return !(row && num(row.craftable, 0) > 0); });
      var made = new Map();
      var takes = new Map();
      var fillsTotal = 0;
      parts.forEach(function (pt) {
        craftLinesOf(pt).forEach(function (l) { made.set(str(l.key), (made.get(str(l.key)) || 0) + num(l.count, 0)); });
        var cr = pt.craft && typeof pt.craft === "object" ? pt.craft : null;
        if (!cr) return;
        fillsTotal += num(cr.fills, 0);
        (Array.isArray(cr.take) ? cr.take : []).forEach(function (u) {
          if (u && typeof u.id === "string") takes.set(u.id, (takes.get(u.id) || 0) + num(u.count, 0));
        });
      });
      var madeList = Array.from(made.entries()).map(function (e) { return { id: e[0], count: e[1] }; });
      var takeList = Array.from(takes.entries()).map(function (e) { return { id: e[0], count: e[1] }; });
      body.appendChild(line("note", "Crafted from the stash by the bot: " + plCountsText(madeList, 8) +
        (takeList.length ? ". It uses " + plCountsText(takeList, 8) : "") +
        (fillsTotal > 0 ? " (about " + plural(grouped(fillsTotal), "craft") + " at the table)" : "") + "."));
    }
    if (na.length) {
      var names = na.slice(0, 12).map(function (e) { return nameOf(str(e.id)) + " × " + grouped(num(e.need, 0)); });
      body.appendChild(line("note warn", "Not available in this stash, left out: " + names.join(", ") + (na.length > 12 ? " and " + (na.length - 12) + " more." : ".")));
    }
    var miss = (Array.isArray(dry.missing) ? dry.missing : []).filter(function (e) {
      return typeof e.missingAfterCraft !== "number" || e.missingAfterCraft > 0;
    });
    if (miss.length) body.appendChild(line("note", plural(String(miss.length), "kind") + " of material will still be missing (see the list; Export missing)."));
    var check = checkField("", planner.names);
    check.node.replaceChildren(check.input, document.createTextNode(" Name the shulkers “"), hiddenSpan(PLANNER_NAME_PREFIX + str(p.fileName)),
      document.createTextNode((total > 1 ? " 1/" + total : "") + "”"));
    body.appendChild(check.node);
    var warn = line("note warn", "");
    warn.setAttribute("role", "status");
    body.appendChild(warn);
    var first = Array.isArray(dry.remaining) && dry.remaining.length ? dry.remaining[0] : 1;
    var firstPart = parts[first - 1];
    var syncWarn = function () {
      var list = plannerBotWarnings(s);
      if (check.input.checked) {
        var w = namesWarnText(s, firstPart ? num(firstPart.shulkers, 0) : 0);
        if (w) list.push(w);
      }
      warn.textContent = list.join(" ");
      warn.hidden = !list.length;
    };
    check.input.addEventListener("change", function () {
      planner.names = check.input.checked;
      plannerSave();
      syncWarn();
    });
    syncWarn();
    var actions = document.createElement("div");
    actions.className = "window-actions";
    var go = document.createElement("button");
    go.type = "button";
    go.className = "mc-button primary";
    go.textContent = total > 1 ? "Order part " + first + " of " + total : "Place order";
    var cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "mc-button";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", closeWindow);
    var msg = line("note warn", "");
    go.addEventListener("click", function () {
      go.disabled = true;
      msg.textContent = "";
      plannerPlace(first, function (ok, e) {
        if (ok) closeWindow();
        else {
          go.disabled = false;
          msg.textContent = plannerFail(e, "Could not place the order");
        }
      });
    });
    actions.appendChild(go);
    actions.appendChild(cancel);
    body.appendChild(actions);
    body.appendChild(msg);
    if (!parts.length) {
      go.disabled = true;
      msg.textContent = "Nothing to order: the stash has none of these materials available.";
    }
    openWindow("Order build materials", body, "pl-order-window");
  }

  var PLANNER_NAME_PREFIX = "Build: ";

  pl.order.addEventListener("click", function () {
    var s = plannerStash();
    if (!s || !planner.plan || !can("plannerOrder")) return;
    pl.order.disabled = true;
    plOrderStatus("Planning the order...", false);
    api("/api/v1/planner/" + encodeURIComponent(planner.plan.id) + "/order", { method: "POST", body: plannerOrderBody({ dryRun: true }) }).then(function (dry) {
      plOrderStatus("", false);
      plannerConfirm(dry);
    }, function (e) {
      plOrderStatus(plannerFail(e, "Could not plan the order"), true);
    }).then(function () {
      plannerRenderActions();
    });
  });

  pl.again.addEventListener("click", function () {
    var o = planner.ordered;
    if (!o || !Array.isArray(o.again) || !o.again.length) return;
    pl.again.disabled = true;
    plannerPlace(o.again[0], function () { plannerRenderActions(); });
  });

  pl.next.addEventListener("click", function () {
    var o = planner.ordered;
    if (!o || !Array.isArray(o.remaining) || !o.remaining.length) return;
    pl.next.disabled = true;
    plannerPlace(o.remaining[0], function () { plannerRenderActions(); });
  });

  pl.dismiss.addEventListener("click", function () {
    var p = planner.plan;
    if (!p) return;
    pl.dismiss.disabled = true;
    api("/api/v1/planner/" + encodeURIComponent(p.id) + "/delete", { method: "POST", body: {} }).then(null, function () {}).then(function () {
      pl.dismiss.disabled = false;
      planner.plan = null;
      planner.seq++;
      plannerResetOptions();
      plannerSave();
      plOrderStatus("", false);
      plStatus("Dismissed. The material list was deleted from the server.", false);
      plannerRender();
    });
  });

  // ---------- build planner: inputs ----------

  pl.choose.addEventListener("click", function (e) {
    e.stopPropagation();
    pl.file.click();
  });
  pl.drop.addEventListener("click", function (e) {
    if (e.target === pl.drop || e.target.classList.contains("pl-drop-text")) pl.file.click();
  });
  pl.drop.addEventListener("keydown", function (e) {
    if (e.target === pl.drop && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      pl.file.click();
    }
  });
  pl.file.addEventListener("change", function () {
    if (pl.file.files && pl.file.files[0]) plannerUpload(pl.file.files[0]);
  });
  ["dragenter", "dragover"].forEach(function (type) {
    pl.drop.addEventListener(type, function (e) {
      e.preventDefault();
      pl.drop.classList.add("over");
    });
  });
  ["dragleave", "drop"].forEach(function (type) {
    pl.drop.addEventListener(type, function () { pl.drop.classList.remove("over"); });
  });
  pl.drop.addEventListener("drop", function (e) {
    e.preventDefault();
    var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) plannerUpload(f);
  });
  pl.stash.addEventListener("change", function () {
    planner.stash = pl.stash.value || null;
    planner.ordered = null;
    plOrderStatus("", false);
    plannerLoad();
  });
  pl.craft.addEventListener("change", function () {
    planner.craftPref = pl.craft.checked;
    plannerSave();
    plannerRenderTiles(planner.plan);
    plannerRenderActions();
    plannerScheduleLoad();
  });
  pl.mult.addEventListener("input", function () {
    if (pl.mult.value !== "") plannerScheduleLoad();
  });
  pl.mult.addEventListener("change", function () {
    pl.mult.value = String(clampInt(pl.mult.value, 1, 64));
    plannerScheduleLoad();
  });
  [pl.round, pl.fluids, pl.containers].forEach(function (c) {
    c.addEventListener("change", plannerLoad);
  });
  pl.filter.addEventListener("input", function () {
    planner.filter = pl.filter.value;
    if (planner.plan && planner.plan.items) plannerRenderRows(planner.plan);
  });
  pl.onlyMissing.addEventListener("change", function () {
    planner.onlyMissing = pl.onlyMissing.checked;
    if (planner.plan && planner.plan.items) plannerRenderRows(planner.plan);
  });
  pl.table.querySelector("thead").addEventListener("click", function (e) {
    var b = e.target.closest ? e.target.closest(".pl-sort") : null;
    if (!b) return;
    var key = b.dataset.sort;
    if (planner.sort.key !== key) planner.sort = { key: key, dir: key === "name" ? 1 : -1 };
    else if ((planner.sort.dir > 0) === (key === "name")) planner.sort.dir = -planner.sort.dir;
    else planner.sort = { key: "", dir: 1 }; // the third click: creative order again
    if (planner.plan && planner.plan.items) plannerRenderRows(planner.plan);
  });

  // ---------- bots overview ----------

  var bots = { body: null };

  function botDoing(s) {
    var o = s.orders && typeof s.orders === "object" ? s.orders : null;
    var active = o && o.active && typeof o.active === "object" ? o.active : null;
    var c = s.containers && typeof s.containers === "object" ? s.containers : null;
    var stale = c ? num(c.stale, 0) : 0;
    if (active) return { text: "Order " + str(active.id) + ": " + (str(active.phase) || STATUS_LABEL[active.status] || str(active.status)) };
    if (str(s.orderBlocker)) return { text: "Can't take orders: " + str(s.orderBlocker), warn: true };
    if (typeof s.rescanPending === "number") return { text: "Rescan requested" };
    if (str(s.rescanBlocked)) return { text: "Can't rescan: " + str(s.rescanBlocked), warn: true };
    if (stale > 0) return { text: "Re-reading containers: " + grouped(Math.max(0, num(c.total, 0) - stale)) + " / " + grouped(num(c.total, 0)) };
    return { text: "Idle" };
  }

  function cell(cls, text) {
    var d = document.createElement("div");
    d.className = "bots-cell " + cls;
    d.textContent = text;
    return d;
  }

  function renderBots() {
    if (!bots.body) return;
    var frag = document.createDocumentFragment();
    var head = document.createElement("div");
    head.className = "bots-row bots-head";
    ["Bot", "Stash", "Status", "Doing", "Delivery container", "Open orders", ""].forEach(function (t) { head.appendChild(cell("", t)); });
    frag.appendChild(head);
    if (!state.stashes.length) {
      frag.appendChild(line("window-info", "No bots yet. Use Link addon to connect one."));
    }
    state.stashes.forEach(function (s) {
      var key = stashKey(s);
      var row = document.createElement("div");
      row.className = "bots-row" + (key === state.selected ? " selected" : "");
      row.title = "Show this stash";
      var who = document.createElement("div");
      who.className = "bots-cell bots-who";
      var dot = document.createElement("span");
      setDot(dot, !!s.online);
      who.appendChild(dot);
      who.appendChild(document.createTextNode(" " + (str(s.owner) || str(s.player) || "(no owner)")));
      row.appendChild(who);
      var where = cell("", str(s.stash) + " @ ");
      where.appendChild(hiddenSpan(str(s.server)));
      row.appendChild(where);
      row.appendChild(cell("", (s.online ? "online" : "offline") + " \u00b7 " + ago(num(s.lastSeen, null))));
      var doing = botDoing(s);
      row.appendChild(hide(cell(doing.warn ? "warn" : "", doing.text)));
      var d = s.delivery;
      var dc = cell(d && typeof d === "object" ? "" : "warn",
        d && typeof d === "object" ? num(d.x, 0) + ", " + num(d.y, 0) + ", " + num(d.z, 0) : "not set");
      row.appendChild(d && typeof d === "object" ? hide(dc) : dc);
      row.appendChild(cell("bots-num", String(s.orders && typeof s.orders === "object" ? num(s.orders.open, 0) : 0)));
      var actions = document.createElement("div");
      actions.className = "bots-cell";
      if (str(s.owner) && can("link")) {
        var rel = document.createElement("button");
        rel.type = "button";
        rel.className = "mc-button small danger";
        rel.textContent = "Release";
        rel.addEventListener("click", function (e) {
          e.stopPropagation();
          releaseStash(s);
        });
        actions.appendChild(rel);
      }
      row.appendChild(actions);
      var warn = conflictText(s);
      if (warn) {
        var w = cell("warn bots-conflict", warn);
        row.appendChild(w);
      }
      row.addEventListener("click", function () {
        selectStash(key);
        closeWindow();
        if (dash.page === "bots") showPage("stash");
      });
      frag.appendChild(row);
    });
    bots.body.replaceChildren(frag);
  }

  function releaseStash(s) {
    var sure = window.confirm("Release stash '" + str(s.stash) + "' from " + str(s.owner) + "?\n\nAnother bot can then take it over by " +
      "using this stash name. If " + str(s.owner) + " is still running with this stash name, it takes it back on its next heartbeat.");
    if (!sure) return;
    api("/api/v1/stashes/release", { method: "POST", body: { server: s.server, stash: s.stash } }).then(function () {
      toast("Released '" + str(s.stash) + "'");
      poll();
    }, function (e) {
      toast("Could not release" + (e && e.body && e.body.error ? ": " + e.body.error : e && e.status ? " (HTTP " + e.status + ")" : ""));
    });
  }


  // ---------- manual rescan ----------
  // The bot acknowledges a request (rescanPending goes back to null), marks every container stale and re-reads
  // them; progress comes from the stash's containers.stale count. "watching" is this page's memory of a rescan
  // it has seen requested, so "Rescan complete" is only shown after one.

  var RESCAN_DONE_MS = 6000;
  var rescan = { key: null, busy: false, watching: false, lastPending: null, ackAt: 0, sawStale: false, doneUntil: 0 };

  function updateRescan() {
    var s = currentStash();
    if (!s || state.unreachable || !can("rescan")) {
      el.rescanBox.hidden = true;
      return;
    }
    el.rescanBox.hidden = false;
    if (rescan.key !== state.selected) {
      rescan = { key: state.selected, busy: false, watching: false, lastPending: null, ackAt: 0, sawStale: false, doneUntil: 0 };
    }
    var pending = typeof s.rescanPending === "number" ? s.rescanPending : null;
    var blocked = str(s.rescanBlocked);
    var c = s.containers && typeof s.containers === "object" ? s.containers : null;
    var total = c ? Math.max(0, num(c.total, 0)) : 0;
    var stale = c ? Math.max(0, num(c.stale, 0)) : 0;
    var now = serverNow();

    if (pending !== null) {
      rescan.watching = true;
      rescan.lastPending = pending;
      rescan.ackAt = 0;
      rescan.sawStale = false;
    } else if (rescan.lastPending !== null) {
      rescan.lastPending = null; // acknowledged by the bot
      rescan.ackAt = now;
    }
    if (rescan.watching && pending === null && stale > 0) rescan.sawStale = true;

    var text = "";
    var warn = false;
    var progress = -1;
    if (pending !== null && !s.online) {
      text = "Bot offline: rescan will start when it reconnects";
      warn = true;
    } else if (pending !== null) {
      text = "Rescan requested: waiting for the bot";
    } else if (blocked && rescan.watching) {
      text = "Can't rescan yet: " + blocked;
      warn = true;
    } else if (rescan.watching && stale > 0) {
      text = "Re-reading containers: " + grouped(Math.max(0, total - stale)) + " / " + grouped(total);
      progress = total > 0 ? (total - stale) / total : 0;
    } else if (rescan.watching && (rescan.sawStale || (rescan.ackAt && num(s.indexedAt, 0) > rescan.ackAt))) {
      rescan.watching = false;
      rescan.doneUntil = now + RESCAN_DONE_MS;
      text = "Rescan complete";
    } else if (rescan.watching) {
      text = "Rescan started: waiting for the bot's index";
    } else if (now < rescan.doneUntil) {
      text = "Rescan complete";
    }
    if (el.rescanStatus.textContent !== text) el.rescanStatus.textContent = text;
    el.rescanStatus.classList.toggle("warn", warn);
    el.rescanStatus.classList.toggle("done", text === "Rescan complete");
    el.rescanProgress.hidden = progress < 0;
    if (progress >= 0) el.rescanFill.style.transform = "scaleX(" + Math.max(0, Math.min(1, progress)) + ")";
    el.rescanBtn.disabled = rescan.busy || pending !== null;
    el.rescanBtn.title = pending !== null ? "A rescan is already waiting for the bot" : "Ask the bot to re-read every container";
  }

  el.rescanBtn.addEventListener("click", function () {
    var s = currentStash();
    if (!s || rescan.busy) return;
    var sure = window.confirm("Rescan this stash?\n\nThe bot will re-read every container in this stash (nearest first) and pick up " +
      "new containers inside the stash area. Items already listed stay visible until their container is read again. " +
      "Works with sorting turned off.");
    if (!sure) return;
    rescan.busy = true;
    updateRescan();
    api("/api/v1/rescan", { method: "POST", body: { server: s.server, stash: s.stash } }).then(function (r) {
      s.rescanPending = r && typeof r.rescanId === "number" ? r.rescanId : 0;
      rescan.busy = false;
      updateRescan();
      toast("Rescan requested");
      poll();
    }, function (e) {
      rescan.busy = false;
      updateRescan();
      toast(e && e.status === 404 ? "The server doesn't know this stash yet." :
        "Could not request a rescan" + (e && e.body && e.body.error ? ": " + e.body.error : e && e.status ? " (HTTP " + e.status + ")" : ""));
    });
  });

  // ---------- items ----------

  function clearIndex() {
    state.index = null;
    state.loadedWant = null;
    state.pendingWant = null;
    state.loadToken++;
    state.items = [];
    state.byKey = new Map();
    state.shown = [];
    state.shulkers = [];
    state.shulkerByKey = new Map();
    renderTabs();
    el.grid.replaceChildren();
    hideTooltip();
    updateSummary(0, 0);
  }

  function showEmpty() {
    el.emptyText.textContent = !can("view")
      ? "Your account has no access to stashes; ask the owner."
      : currentStash()
      ? "No stash index yet for this stash. Waiting for the addon to send one."
      : "No stash index yet. Turn on stash-manager in the addon and set its API key.";
    el.empty.hidden = false;
  }

  function splitId(id) {
    var i = id.indexOf(":");
    return i < 0 ? ["minecraft", id] : [id.slice(0, i), id.slice(i + 1)];
  }

  /**
   * The v token names the icon source generation and the index's Minecraft version (the server renders
   * from that version's game jar). Changing ICON_SOURCE_TOKEN or the version changes every URL, so icons the
   * browser cached for a week under an older scheme (e.g. wiki PNGs) are not reused.
   */
  function iconUrl(id, mc) {
    var p = splitId(id);
    return "/icon/" + encodeURIComponent(p[0]) + "/" + encodeURIComponent(p[1]) + ".png?v=" +
      encodeURIComponent(mc + "-" + ICON_SOURCE_TOKEN);
  }

  function indexMcVersion(index) {
    var mc = str(index.mcVersion);
    return /^[0-9][0-9.]{0,31}$/.test(mc) ? mc : DEFAULT_MC;
  }

  function retryDelay(tries) {
    return tries <= ICON_RETRY_DELAYS.length ? ICON_RETRY_DELAYS[tries - 1] : 10000;
  }

  function normalizeItem(it) {
    var id = str(it.id);
    var name = str(it.name) || id;
    return {
      key: str(it.key),
      id: id,
      name: name,
      englishName: str(it.englishName) || name,
      count: Math.max(0, Math.floor(num(it.count, 0))),
      maxStack: Math.max(1, Math.floor(num(it.maxStack, 64))),
      details: Array.isArray(it.details) ? it.details.filter(function (d) { return typeof d === "string"; }) : [],
      enchanted: it.enchanted === true,
      tab: typeof it.tab === "string" && TAB_INDEX.hasOwnProperty(it.tab) ? it.tab : "other",
      tabOrder: num(it.tabOrder, 1000000),
      // Bots that report durability send these only for a damaged, damageable stack.
      damage: Math.max(0, Math.floor(num(it.damage, 0))),
      maxDamage: Math.max(0, Math.floor(num(it.maxDamage, 0))),
      // Repair with XP bottles (PROTOCOL.md): the server computes repairable / repairBottles; mending is the bot's flag.
      mending: it.mending === true || (Array.isArray(it.details) && it.details.some(function (d) {
        return typeof d === "string" && /^mending(?: (?:[ivxlcdm]+|[0-9]+))?$/i.test(d.trim());
      })),
      repairable: it.repairable === true,
      repairBottles: Math.max(0, Math.floor(num(it.repairBottles, 0)))
    };
  }

  var BOTTLE_KEY = "minecraft:experience_bottle";

  /** One bottle is about 14 durability (about 7 XP, 2 durability per XP point under Mending). */
  function bottlesFor(damage) {
    return damage > 0 ? Math.ceil(damage / 14) : 0;
  }

  /** Why a stash item can't be repaired ("no Mending", "not damaged", "not a single item"), or "" when it can. */
  function repairReason(item) {
    if (!item) return "no Mending";
    if (/^minecraft:.*shulker_box$/.test(item.id)) return "not a single item";
    if (!item.mending) return "no Mending";
    if (!(item.damage > 0)) return "not damaged";
    if (item.maxStack !== 1) return "not a single item";
    return "";
  }

  /** Remaining durability from 0 to 1 for a damaged item, or -1 when it isn't damaged (or the bot doesn't say). */
  function durabilityLeft(item) {
    if (!item || !(item.maxDamage > 0) || !(item.damage > 0)) return -1;
    return Math.max(0, Math.min(1, (item.maxDamage - item.damage) / item.maxDamage));
  }

  /**
   * The game's durability bar on an icon host (a .slot or an .icon-wrap): a black bar 13/16 of the icon wide and
   * 2/16 high near the bottom, with the coloured part over its top half. Width round(13 x left) of 13, colour from
   * green to red (the game's hsv(left / 3, 1, 1), which is hsl(120 x left, 100%, 50%)). Removed when not damaged.
   */
  function setDurability(host, item) {
    if (!host) return;
    var left = durabilityLeft(item);
    var bar = null;
    for (var c = host.firstElementChild; c; c = c.nextElementSibling) if (c.classList.contains("dura")) bar = c;
    if (left < 0) {
      if (bar) bar.remove();
      return;
    }
    if (!bar) {
      bar = document.createElement("span");
      bar.className = "dura";
      bar.setAttribute("aria-hidden", "true");
      var fill = document.createElement("span");
      fill.className = "dura-fill";
      bar.appendChild(fill);
      host.appendChild(bar);
    }
    var px = Math.round(13 * left);
    bar.firstChild.style.width = (px / 13 * 100) + "%";
    bar.firstChild.style.backgroundColor = "hsl(" + Math.round(120 * left) + ", 100%, 50%)";
  }

  function haystack(item) {
    return [item.name, item.englishName, item.id].concat(item.details).join("\n").toLowerCase();
  }

  /** Adds or removes the glint. It is only added once the real icon has loaded, since it is masked by it. */
  function syncGlint(entry) {
    if (entry.item.enchanted && entry.iconLoaded) {
      if (!entry.glint) {
        var glint = document.createElement("div");
        glint.className = "glint";
        // Set through the CSSOM (allowed by the CSP). The URL is our own /icon/ path (encodeURIComponent
        // output), already in the browser's image cache, so the mask costs no extra request.
        var mask = "url(\"" + entry.img.getAttribute("src").replace(/["\\]/g, "") + "\")";
        glint.style.webkitMaskImage = mask;
        glint.style.maskImage = mask;
        glint.appendChild(document.createElement("div")).className = "sheen";
        entry.slot.insertBefore(glint, entry.countEl);
        entry.glint = glint;
      }
    } else if (entry.glint) {
      entry.glint.remove();
      entry.glint = null;
    }
  }

  /** The number a slot shows: for a shulker-box item with filled groups, only the empty ones. */
  function slotCount(entry) {
    return typeof entry.emptyCount === "number" ? entry.emptyCount : entry.item.count;
  }

  function setCount(entry) {
    var n = slotCount(entry);
    var text = n === 1 ? "" : compact(n);
    if (entry.countEl.textContent !== text) entry.countEl.textContent = text;
  }

  function createEntry(item, mc) {
    var slot = document.createElement("div");
    slot.className = "slot";
    var img = document.createElement("img");
    img.alt = "";
    img.loading = "lazy";
    img.decoding = "async";
    img.width = 32;
    img.height = 32;
    img.draggable = false;
    var countEl = document.createElement("span");
    countEl.className = "count";
    slot.appendChild(img);
    slot.appendChild(countEl);
    setDurability(slot, item);
    // Touch layout only (CSS hides it elsewhere): the shift-click action, as a visible button.
    var addBtn = document.createElement("button");
    addBtn.type = "button";
    addBtn.className = "slot-add";
    addBtn.textContent = "+";
    addBtn.setAttribute("aria-label", "Add a stack of " + item.name + " to the cart");
    slot.appendChild(addBtn);
    slot.addEventListener("click", gridSlotClick);

    var entry = {
      item: item,
      slot: slot,
      img: img,
      countEl: countEl,
      glint: null,
      iconLoaded: false,
      tries: 0,
      url: iconUrl(item.id, mc),
      ns: splitId(item.id)[0].toLowerCase(),
      hay: haystack(item)
    };

    img.addEventListener("load", function () {
      if (img.getAttribute("src") === PLACEHOLDER) return;
      entry.iconLoaded = true;
      syncGlint(entry);
    });
    // The server answers 503 while it is still fetching an icon. Keep the placeholder up and ask again
    // quickly (1s, 2s, 3s, 5s, then every 10s); the query string only defeats the browser's image cache.
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      entry.tries++;
      if (entry.tries > ICON_RETRIES) return;
      var n = entry.tries;
      window.setTimeout(function () {
        if (img.isConnected && !entry.iconLoaded) img.src = entry.url + "&retry=" + n;
      }, retryDelay(n));
    });
    img.src = entry.url;
    setCount(entry);
    if (item.isShulker) setFace(entry, shulkerFace(item.contents));
    slotEntry.set(slot, entry);
    return entry;
  }

  // ---------- filled-shulker icons ----------
  // A filled shulker is drawn as its box with the item it holds most of on top (by total count over all its
  // stacks, items of the same id counted together; a tie goes to the item in the lowest slot). When it holds
  // more than one kind of item, the second most common one is drawn small in the top-left corner as the
  // "mixed" marker. A plain shulker icon always means an empty one. The tooltip and the shulker window still
  // list the full contents.

  /** { id, second } for a shulker's contents (second: null unless mixed), or null when it is empty. */
  function shulkerFace(contents) {
    if (!contents || !contents.length) return null;
    var totals = new Map();
    var firstSlot = new Map();
    contents.forEach(function (c) {
      var it = itemByKey(c.key);
      var id = it ? it.id : String(c.key).split("#")[0];
      totals.set(id, (totals.get(id) || 0) + c.count);
      if (!firstSlot.has(id) || c.slot < firstSlot.get(id)) firstSlot.set(id, c.slot);
    });
    var ids = Array.from(totals.keys()).sort(function (x, y) {
      return totals.get(y) - totals.get(x) || firstSlot.get(x) - firstSlot.get(y);
    });
    return ids.length ? { id: ids[0], second: ids.length > 1 ? ids[1] : null } : null;
  }

  /**
   * Puts (or clears, for null) the contents icon and the mixed marker on a slot-like holder: anything with
   * .slot and .countEl (grid entries, pooled plan slots, icon wrappers). Images only reload when a URL changes.
   */
  function setFace(h, face) {
    h.faceImg = swapFaceImage(h, h.faceImg, face ? iconUrl(face.id, state.mc) : null, "face");
    h.face2Img = swapFaceImage(h, h.face2Img, face && face.second ? iconUrl(face.second, state.mc) : null, "face face-second");
    h.slot.classList.toggle("has-face", !!face);
  }

  function swapFaceImage(h, img, url, cls) {
    if (img && img.dataset.url === url) return img;
    if (img) img.remove();
    return url ? faceImage(url, h, cls) : null;
  }

  /** A contents <img>, with the same 503 retry as the slot icon. */
  function faceImage(url, h, cls) {
    var img = document.createElement("img");
    img.alt = "";
    img.className = cls;
    img.decoding = "async";
    img.width = 20;
    img.height = 20;
    img.draggable = false;
    img.dataset.url = url;
    var tries = 0;
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      tries++;
      if (tries > ICON_RETRIES) return;
      var n = tries;
      window.setTimeout(function () {
        if (img.isConnected) img.src = url + "&retry=" + n;
      }, retryDelay(n));
    });
    img.src = url;
    h.slot.insertBefore(img, h.countEl || null);
    return img;
  }

  /** iconImg for a line that may be a filled shulker: the icon plus its contents icon, in a small wrapper. */
  function iconWithContents(id, contents, size) {
    var face = shulkerFace(contents);
    var icon = iconImg(id, size);
    if (!face) return icon;
    var wrap = document.createElement("span");
    wrap.className = "icon-wrap";
    wrap.appendChild(icon);
    setFace({ slot: wrap, countEl: null }, face);
    return wrap;
  }

  /**
   * Applies a new index. Slots are reused by item key, so an index refresh (frequent: the addon pushes on
   * every stash change) updates counts and details in place and never recreates images or glints.
   */
  function updateItems(index) {
    var raw = Array.isArray(index.items) ? index.items : [];
    var mc = indexMcVersion(index);
    var next = new Map();
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var it = raw[i];
      if (!it || typeof it !== "object") continue;
      var item = normalizeItem(it);
      if (!item.key || next.has(item.key)) continue;
      var entry = state.byKey.get(item.key);
      if (!entry || entry.item.id !== item.id || entry.url !== iconUrl(item.id, mc)) {
        entry = createEntry(item, mc);
      } else {
        var old = entry.item;
        entry.item = item;
        if (old.count !== item.count) setCount(entry);
        if (old.enchanted !== item.enchanted) syncGlint(entry);
        if (old.damage !== item.damage || old.maxDamage !== item.maxDamage) setDurability(entry.slot, item);
        if (old.name !== item.name || old.englishName !== item.englishName || old.details.join("\n") !== item.details.join("\n")) {
          entry.hay = haystack(item);
        }
      }
      next.set(item.key, entry);
      out.push(entry);
    }
    state.byKey = next; // entries for removed keys are dropped here and leave the DOM in renderNow
    state.items = out;
    state.mc = mc;
    updateShulkers(index, mc);
  }

  function parseQuery(q) {
    var terms = [];
    var namespaces = [];
    q.toLowerCase().split(/\s+/).forEach(function (t) {
      if (!t) return;
      if (t.charAt(0) === "@") {
        if (t.length > 1) namespaces.push(t.slice(1));
      } else {
        terms.push(t);
      }
    });
    return { terms: terms, namespaces: namespaces };
  }

  function matches(entry, query) {
    for (var i = 0; i < query.namespaces.length; i++) {
      if (entry.ns.indexOf(query.namespaces[i]) !== 0) return false;
    }
    for (var j = 0; j < query.terms.length; j++) {
      if (entry.hay.indexOf(query.terms[j]) < 0) return false;
    }
    return true;
  }

  var collator = typeof Intl !== "undefined" ? new Intl.Collator(undefined, { sensitivity: "base", numeric: true }) : null;

  function cmp(a, b) {
    if (collator) return collator.compare(a, b);
    return a < b ? -1 : a > b ? 1 : 0;
  }

  /** Damaged copies of the same item: the most worn first, undamaged ones last. */
  function duraOrder(a, b) {
    if (a.item.id !== b.item.id) return 0;
    var x = durabilityLeft(a.item), y = durabilityLeft(b.item);
    return (x < 0 ? 2 : x) - (y < 0 ? 2 : y);
  }

  var sorters = {
    creative: function (a, b) {
      return (TAB_INDEX[a.item.tab] - TAB_INDEX[b.item.tab]) || (a.item.tabOrder - b.item.tabOrder) ||
        cmp(a.item.name, b.item.name) || duraOrder(a, b) || cmp(a.item.key, b.item.key);
    },
    count: function (a, b) {
      return b.item.count - a.item.count || cmp(a.item.name, b.item.name) || cmp(a.item.key, b.item.key);
    },
    name: function (a, b) {
      return cmp(a.item.name, b.item.name) || b.item.count - a.item.count || cmp(a.item.key, b.item.key);
    },
    id: function (a, b) {
      return cmp(a.item.id, b.item.id) || cmp(a.item.key, b.item.key);
    }
  };

  function sameEntries(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  function renderNow() {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = 0;
    var query = parseQuery(el.search.value);
    var sortName = el.sort.value;
    if (state.tab === "shulkers" && sortName === "creative") sortName = "count";
    var sorter = sorters[sortName] || sorters.count;
    var source = tabEntries(state.tab);
    var shown;
    if (state.tab === "shulkers") {
      shown = source.filter(function (e) { return matches(e, query); });
      shown.sort(sorter);
    } else {
      // A shulker-box item stays in the list (as the anchor for its filled groups) when only a group matches.
      shown = source.filter(function (e) {
        return matches(e, query) || (e.filledGroups && e.filledGroups.some(function (g) { return matches(g, query); }));
      });
      shown.sort(sorter);
      shown = withFilledGroups(shown, query);
    }
    var total = 0;
    for (var i = 0; i < shown.length; i++) total += slotCount(shown[i]);

    // Only touch the grid when the set or order of slots changed. Count changes were already
    // written into the existing slots by updateItems.
    if (!sameEntries(shown, state.shown)) {
      var frag = document.createDocumentFragment();
      for (var j = 0; j < shown.length; j++) frag.appendChild(shown[j].slot);
      el.grid.replaceChildren(frag);
      state.shown = shown;
    }

    var loaded = state.loadedWant !== null;
    if (loaded && !state.items.length) {
      el.emptyText.textContent = "This stash index has no items.";
      el.empty.hidden = false;
    } else if (loaded) {
      el.empty.hidden = true;
    }
    updateSummary(shown.length, total);
    refreshTooltip();
  }

  function queueRender(delay) {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = window.setTimeout(renderNow, delay || 0);
  }

  /** How many slots the current tab has without a search (filled shulker groups count as their own slots). */
  function tabSlotCount() {
    var source = tabEntries(state.tab);
    if (state.tab === "shulkers") return source.length;
    var n = 0;
    source.forEach(function (e) {
      n += e.filledGroups ? (e.emptyCount > 0 ? 1 : 0) + e.filledGroups.length : 1;
    });
    return n;
  }

  function updateSummary(shownCount, shownTotal) {
    var all = state.items.length ? tabSlotCount() : 0;
    if (!all) {
      el.summary.textContent = "";
      return;
    }
    var what = state.tab === "shulkers" ? " filled shulker kinds \u00b7 total " : " slots \u00b7 total ";
    el.summary.textContent = "Showing " + grouped(shownCount) + " of " + grouped(all) + what +
      grouped(shownTotal || 0) + (state.tab === "shulkers" ? " shulkers" : " items");
  }

  // ---------- tooltip ----------
  // Layout is read only when the tooltip's content changes (showTooltip) and on resize. Mouse moves just
  // store the position; one requestAnimationFrame callback per frame writes a transform.

  var tip = { w: 0, h: 0, x: 0, y: 0, raf: 0 };
  var viewport = { w: 0, h: 0 };

  function measureViewport() {
    viewport.w = document.documentElement.clientWidth;
    viewport.h = document.documentElement.clientHeight;
  }
  measureViewport();
  window.addEventListener("resize", measureViewport, { passive: true });

  function line(cls, text) {
    var d = document.createElement("div");
    d.className = cls;
    d.textContent = text;
    return d;
  }

  function plural(text, word) {
    return text + " " + word + (text === "1" ? "" : "s");
  }

  function showTooltip(entry) {
    var it = entry.item;
    var frag = document.createDocumentFragment();
    var nameCls = "tt-name" + (it.enchanted ? " ench" : "") + (it.name !== it.englishName ? " custom" : "");
    frag.appendChild(line(nameCls, it.name));
    for (var i = 0; i < it.details.length; i++) frag.appendChild(line("tt-line", it.details[i]));
    if (durabilityLeft(it) >= 0 && !it.details.some(function (d) { return /^Durability/i.test(d); })) {
      frag.appendChild(line("tt-line", "Durability: " + grouped(it.maxDamage - it.damage) + " / " + grouped(it.maxDamage)));
    }
    if (it.plain) {
      // Planned shulkers and stacks in the cart view: the details already say everything.
    } else if (it.isShulker) {
      frag.appendChild(line("tt-count", "\u00d7" + grouped(it.count) + (it.count === 1 ? " shulker" : " identical shulkers")));
      frag.appendChild(line("tt-hint", "Right-click to look inside"));
    } else {
      var stacks = approx(it.count / it.maxStack);
      var shulkers = approx(it.count / (it.maxStack * 27));
      frag.appendChild(line("tt-count", "Count: " + grouped(it.count) + " (\u2248 " + plural(stacks, "stack") + ", " +
        plural(shulkers, "shulker") + ")"));
      if (typeof entry.emptyCount === "number") {
        frag.appendChild(line("tt-line", grouped(entry.emptyCount) + " empty, " + grouped(it.count - entry.emptyCount) +
          " filled (listed next to it, showing what they hold)"));
      }
      if (isShulkerBoxId(it.id) && state.byKey.get(it.key) === entry) frag.appendChild(line("tt-hint", "Right-click to see the filled ones"));
    }
    frag.appendChild(line("tt-id", it.id));
    el.tooltip.replaceChildren(frag);
    el.tooltip.hidden = false;
    // The one layout read per hovered item.
    tip.w = el.tooltip.offsetWidth;
    tip.h = el.tooltip.offsetHeight;
    state.hovered = entry;
  }

  function hideTooltip() {
    state.hovered = null;
    el.tooltip.hidden = true;
  }

  function moveTooltip(x, y) {
    tip.x = x;
    tip.y = y;
    if (!tip.raf) tip.raf = window.requestAnimationFrame(applyTooltip);
  }

  function applyTooltip() {
    tip.raf = 0;
    if (!state.hovered) return;
    if (!state.hovered.slot.isConnected) {
      hideTooltip(); // its slot was replaced by a re-render
      return;
    }
    var left = tip.x + 14;
    var top = tip.y - 16;
    if (left + tip.w + 4 > viewport.w) left = tip.x - tip.w - 14;
    if (left < 4) left = 4;
    if (top + tip.h + 4 > viewport.h) top = viewport.h - tip.h - 4;
    if (top < 4) top = 4;
    el.tooltip.style.transform = "translate(" + Math.round(left) + "px, " + Math.round(top) + "px)";
  }

  function entryAt(x, y) {
    var target = document.elementFromPoint(x, y);
    var slot = target && target.closest ? target.closest(".slot") : null;
    if (!slot) return null;
    return slotEntry.get(slot) || null;
  }

  /** After a re-render, keep the tooltip if the cursor is still over the same item key. */
  function refreshTooltip() {
    if (!state.hovered) return;
    var key = state.hovered.item.key;
    var entry = state.mouse.over ? entryAt(state.mouse.x, state.mouse.y) : null;
    if (entry && entry.item.key === key) {
      showTooltip(entry);
      moveTooltip(state.mouse.x, state.mouse.y);
    } else {
      hideTooltip();
    }
  }

  // Delegated on the document so slots in windows and the order picker get tooltips too.
  document.addEventListener("mouseover", function (e) {
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry || drag.active) {
      if (state.hovered) hideTooltip();
      return;
    }
    if (entry === state.hovered) return;
    showTooltip(entry);
    moveTooltip(e.clientX, e.clientY);
  });

  document.addEventListener("mousemove", function (e) {
    state.mouse.x = e.clientX;
    state.mouse.y = e.clientY;
    state.mouse.over = true;
    if (state.hovered) moveTooltip(e.clientX, e.clientY);
  }, { passive: true });

  document.documentElement.addEventListener("mouseleave", function () {
    state.mouse.over = false;
    hideTooltip();
  });
  document.addEventListener("scroll", hideTooltip, { passive: true, capture: true });

  // ---------- link addon panel ----------

  var link = {
    open: false,
    autoOpened: false,
    data: null,
    showKey: false,
    timer: 0,
    urlsShown: null
  };

  function maybeAutoOpenLink() {
    // Only once the dashboard bot list is known: a server with proxy bots but no stash yet doesn't need the panel.
    if (link.autoOpened || link.open || !dash.botsLoaded || !state.polledOnce || dash.bots.length || auth.blocked || !can("link")) return;
    var anyIndex = state.stashes.some(function (s) { return s.indexedAt != null; });
    if (anyIndex) return;
    link.autoOpened = true;
    openLink();
  }

  function openLink() {
    link.open = true;
    el.linkModal.hidden = false;
    hideTooltip();
    refreshLink();
    window.clearInterval(link.timer);
    link.timer = window.setInterval(refreshLink, LINK_POLL_MS);
    el.linkClose.focus();
  }

  function closeLink() {
    link.open = false;
    el.linkModal.hidden = true;
    window.clearInterval(link.timer);
    link.timer = 0;
    el.linkOpen.focus();
  }

  function refreshLink() {
    if (auth.blocked) return;
    api("/api/v1/link").then(function (d) {
      link.data = d;
      renderLink();
    }, function () {
      setDot(el.linkDot, false);
      el.linkStatusText.textContent = "Server unreachable";
    });
  }

  function urlList() {
    var list = [window.location.origin];
    var extra = link.data && Array.isArray(link.data.urls) ? link.data.urls : [];
    extra.forEach(function (u) {
      if (typeof u === "string" && list.indexOf(u) < 0) list.push(u);
    });
    return list;
  }

  function renderLink() {
    var d = link.data;
    if (!d) return;

    var urls = urlList();
    var joined = urls.join("\n");
    if (joined !== link.urlsShown) {
      link.urlsShown = joined;
      var frag = document.createDocumentFragment();
      urls.forEach(function (u, i) {
        var row = document.createElement("div");
        row.className = "copy-row";
        var code = document.createElement("code");
        code.className = "copy-value";
        code.textContent = u;
        hide(code);
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "mc-button streamer-block";
        btn.textContent = "Copy";
        btn.disabled = streamerOn();
        btn.addEventListener("click", function () {
          if (streamerOn()) return;
          copyText(u, code);
        });
        row.appendChild(code);
        if (i === 0) {
          var hint = document.createElement("span");
          hint.className = "note";
          hint.textContent = "this page";
          row.appendChild(hint);
        }
        row.appendChild(btn);
        frag.appendChild(row);
      });
      el.linkUrls.replaceChildren(frag);
    }

    renderKey();

    var list = Array.isArray(d.bots) ? d.bots.filter(function (b) { return b && typeof b === "object"; }) : [];
    var frag2 = document.createDocumentFragment();
    list.forEach(function (b) {
      var row = document.createElement("div");
      row.className = "link-status";
      var dot = document.createElement("span");
      setDot(dot, serverNow() - num(b.lastSeen, 0) < 45000);
      row.appendChild(dot);
      row.appendChild(document.createTextNode((str(b.player) || "addon") + ": " + str(b.stash) + " @ "));
      row.appendChild(hiddenSpan(str(b.server)));
      row.appendChild(document.createTextNode(", last seen " + ago(num(b.lastSeen, null))));
      frag2.appendChild(row);
    });
    el.linkBots.replaceChildren(frag2);
    el.linkStatus.hidden = list.length > 0;
    setDot(el.linkDot, false);
    el.linkStatusText.textContent = "Waiting for addon...";
  }

  function renderKey() {
    // Sub-users never get the key (it lets a bot do anything): no key, Show, Copy or Regenerate for them.
    var ownerOnly = !!(link.data && link.data.apiKeyOwnerOnly);
    el.linkKeyToggle.hidden = ownerOnly;
    el.linkKeyCopy.hidden = ownerOnly;
    el.linkRegen.hidden = ownerOnly;
    if (ownerOnly) {
      el.linkKey.textContent = "Only the owner can see the API key.";
      el.linkKey.classList.remove("masked");
      return;
    }
    var key = link.data ? str(link.data.apiKey) : "";
    if (!key && link.data && link.data.apiKeySet) {
      el.linkKey.textContent = new Array(44).join("\u2022");
      el.linkKey.classList.add("masked");
      el.linkKeyToggle.textContent = "Show";
      return;
    }
    if (link.showKey) {
      el.linkKey.textContent = key;
      el.linkKey.classList.remove("masked");
    } else {
      el.linkKey.textContent = new Array(Math.min(key.length, 43) + 1).join("\u2022");
      el.linkKey.classList.add("masked");
    }
    el.linkKeyToggle.textContent = link.showKey ? "Hide" : "Show";
  }

  function note(text, warn) {
    el.linkCopyNote.textContent = text;
    el.linkCopyNote.classList.toggle("warn", !!warn);
  }

  function selectNode(node) {
    var range = document.createRange();
    range.selectNodeContents(node);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  /** Clipboard API where allowed (secure contexts only); otherwise select the text for Ctrl+C. */
  function copyText(text, node) {
    function fallback() {
      selectNode(node);
      var ok = false;
      try {
        ok = document.execCommand("copy");
      } catch (e) {
        ok = false;
      }
      note(ok ? "Copied." : "Selected. Press Ctrl+C to copy.", !ok);
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { note("Copied."); }, fallback);
    } else {
      fallback();
    }
  }

  el.linkOpen.addEventListener("click", openLink);
  el.linkClose.addEventListener("click", closeLink);
  el.linkModal.addEventListener("click", function (e) {
    if (e.target === el.linkModal) closeLink();
  });
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    if (currentWindow) closeWindow();
    else if (link.open) closeLink();
  });
  /** The key is only sent in admin (sudo) mode: ask for the password first, then fetch it. */
  function withKey(action) {
    if (link.data && str(link.data.apiKey)) {
      action();
      return;
    }
    askPassword().then(function () {
      return api("/api/v1/link").then(function (d) {
        link.data = d;
        sudoState(Date.now() + 15 * 60 * 1000);
        renderLink();
        action();
      });
    }, function () {});
  }

  el.linkKeyToggle.addEventListener("click", function () {
    if (streamerOn()) return;
    withKey(function () {
      link.showKey = !link.showKey;
      renderKey();
    });
  });
  el.linkKeyCopy.addEventListener("click", function () {
    if (streamerOn()) return;
    withKey(copyKey);
  });

  function copyKey() {
    if (!link.data) return;
    // The fallback selects the visible text, so it has to be the real key.
    if (!(navigator.clipboard && window.isSecureContext) && !link.showKey) {
      link.showKey = true;
      renderKey();
    }
    copyText(str(link.data.apiKey), el.linkKey);
  }
  el.linkRegen.addEventListener("click", function () {
    var sure = window.confirm("Make a new API key?\n\nThe old key stops working immediately. " +
      "The addon will be rejected until you paste the new key into its api-key setting.");
    if (!sure) return;
    el.linkRegen.disabled = true;
    withSudo(function () { return api("/api/v1/link/regenerate", { method: "POST", body: {} }); }).then(function (r) {
      if (!link.data) link.data = {};
      link.data.apiKey = str(r.apiKey);
      link.data.linked = null;
      link.showKey = true;
      renderLink();
      el.linkKey.classList.add("fresh");
      el.linkKeyNote.textContent = "New key made. Re-paste it into the addon's api-key setting.";
      el.linkKeyNote.hidden = false;
      note("");
    }, function (e) {
      if (e && e.body && e.body.error === "cancelled") return;
      note("Could not regenerate the key" + (e && e.status ? " (HTTP " + e.status + ")" : "") + ".", true);
    }).then(function () {
      el.linkRegen.disabled = false;
    });
  });

  // ---------- creative tabs ----------

  var CREATIVE_TABS = [
    { id: "building_blocks", name: "Building Blocks", icon: "minecraft:bricks" },
    { id: "colored_blocks", name: "Colored Blocks", icon: "minecraft:cyan_wool" },
    { id: "natural_blocks", name: "Natural Blocks", icon: "minecraft:grass_block" },
    { id: "functional_blocks", name: "Functional Blocks", icon: "minecraft:oak_sign" },
    { id: "redstone_blocks", name: "Redstone Blocks", icon: "minecraft:redstone" },
    { id: "tools_and_utilities", name: "Tools & Utilities", icon: "minecraft:diamond_pickaxe" },
    { id: "combat", name: "Combat", icon: "minecraft:netherite_sword" },
    { id: "food_and_drinks", name: "Food & Drinks", icon: "minecraft:golden_apple" },
    { id: "ingredients", name: "Ingredients", icon: "minecraft:iron_ingot" },
    { id: "spawn_eggs", name: "Spawn Eggs", icon: "minecraft:pig_spawn_egg" },
    { id: "op_blocks", name: "Operator Utilities", icon: "minecraft:command_block" },
    { id: "other", name: "Other", icon: "minecraft:chest" }
  ];
  var TAB_ALL = { id: "all", name: "Search", icon: "minecraft:compass" };
  var TAB_SHULKERS = { id: "shulkers", name: "Shulkers", icon: "minecraft:shulker_box" };
  var TAB_ORDERS = { id: "orders", name: "Orders", icon: "minecraft:writable_book" };
  var TAB_KITS = { id: "kits", name: "Kits", icon: "minecraft:purple_shulker_box" };
  var TAB_KB = { id: "kitbuilder", name: "Kit Builder", icon: "minecraft:crafting_table" };
  var TAB_INDEX = {};
  CREATIVE_TABS.forEach(function (t, i) { TAB_INDEX[t.id] = i; });

  var tabsShown = "";

  function isCreativeTab(id) {
    return TAB_INDEX.hasOwnProperty(id);
  }

  function isShulkerBoxId(id) {
    return /^minecraft:([a-z_]+_)?shulker_box$/.test(id);
  }

  function tabInfo(id) {
    if (id === "all") return TAB_ALL;
    if (id === "shulkers") return TAB_SHULKERS;
    if (id === "orders") return TAB_ORDERS;
    if (id === "kits") return TAB_KITS;
    if (id === "kitbuilder") return TAB_KB;
    return CREATIVE_TABS[TAB_INDEX[id]] || TAB_ALL;
  }

  /** The tabs to show: Search, the creative tabs that have items, Shulkers (if any are filled), then Orders. */
  function visibleTabs() {
    var present = {};
    for (var i = 0; i < state.items.length; i++) present[state.items[i].item.tab] = true;
    var out = [TAB_ALL];
    CREATIVE_TABS.forEach(function (t) {
      if (present[t.id]) out.push(t);
    });
    if (state.shulkers.length) out.push(TAB_SHULKERS);
    // Kits shows positions and names players typed: not for a user the owner forced into streamer mode (the server says 403).
    if (can("view") && !forcedStreamer()) out.push(TAB_KITS);
    // The Kit Builder shows kit names and designs, no positions: a forced streamer sees it (the server hides the chests).
    if (can("view")) out.push(TAB_KB);
    out.push(TAB_ORDERS);
    return out;
  }

  /** The entries a tab shows (before search). */
  function tabEntries(id) {
    if (id === "shulkers") return state.shulkers;
    if (id === "all" || id === "orders" || id === "kits" || id === "kitbuilder") return state.items;
    return state.items.filter(function (e) { return e.item.tab === id; });
  }

  /** A small icon <img> for tabs, buttons and cart lines, with the same 503 retry as the grid. */
  function iconImg(id, size) {
    var img = document.createElement("img");
    img.alt = "";
    img.width = size || 32;
    img.height = size || 32;
    img.draggable = false;
    img.className = "icon";
    var url = iconUrl(id, state.mc);
    var tries = 0;
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      tries++;
      if (tries > ICON_RETRIES) return;
      var n = tries;
      window.setTimeout(function () {
        if (img.isConnected) img.src = url + "&retry=" + n;
      }, retryDelay(n));
    });
    img.src = url;
    return img;
  }

  function renderTabs() {
    var tabs = visibleTabs();
    var ids = tabs.map(function (t) { return t.id; });
    if (ids.indexOf(state.tab) < 0) {
      state.tab = "all";
      if ((el.viewKits && !el.viewKits.hidden) || (el.viewKb && !el.viewKb.hidden)) {
        // The Kits tab went away under the user (their permissions changed): back to the items.
        el.viewKits.hidden = true;
        el.viewKb.hidden = true;
        el.viewItems.hidden = false;
        kitsSync();
        kbSync();
      }
    }
    var signature = ids.join(",") + "|" + state.mc;
    if (signature !== tabsShown) {
      tabsShown = signature;
      var left = document.createDocumentFragment();
      var right = document.createDocumentFragment();
      tabs.forEach(function (t) {
        var b = document.createElement("button");
        b.type = "button";
        var rightTab = t.id === "orders" || t.id === "kits" || t.id === "kitbuilder";
        b.className = "tab" + (rightTab ? " tab-orders" : "");
        b.setAttribute("role", "tab");
        b.dataset.tab = t.id;
        b.title = t.name;
        b.setAttribute("aria-label", t.name);
        b.appendChild(iconImg(t.icon, 32));
        b.addEventListener("click", function () { selectTab(t.id); });
        (rightTab ? right : left).appendChild(b);
      });
      el.tabs.replaceChildren(left);
      el.tabsRight.replaceChildren(right);
    }
    var buttons = document.querySelectorAll(".tab");
    for (var i = 0; i < buttons.length; i++) {
      var on = buttons[i].dataset.tab === state.tab;
      buttons[i].classList.toggle("selected", on);
      buttons[i].setAttribute("aria-selected", on ? "true" : "false");
    }
    el.panelLabel.textContent = state.tab === "all" ? "Search Items" : tabInfo(state.tab).name;
  }

  function selectTab(id) {
    if (id === "orders" || dash.page === "orders") {
      dash.page = id === "orders" ? "orders" : "stash";
      var navs = el.dashNav.querySelectorAll(".nav-btn");
      for (var n = 0; n < navs.length; n++) navs[n].classList.toggle("selected", navs[n].dataset.page === dash.page);
    }
    if (id === state.tab) return;
    state.tab = id;
    hideTooltip();
    var orders = id === "orders";
    var kitsTab = id === "kits";
    var kbTab = id === "kitbuilder";
    el.viewItems.hidden = orders || kitsTab || kbTab;
    el.viewOrders.hidden = !orders;
    el.viewKits.hidden = !kitsTab;
    el.viewKb.hidden = !kbTab;
    kitsSync();
    kbSync();
    if (kitsTab) {
      kitsRender();
      craftSetRender();
      areaSetRender();
      repairRender();
      pearlRender();
    } else if (kbTab) {
      kbRender();
    } else if (!orders) {
      el.sort.value = isCreativeTab(id) ? state.sortTab : state.sortAll;
      el.gridWrap.scrollTop = 0;
      renderNow();
    } else {
      renderPicker();
      renderCart();
      scheduleOrdersPoll(0);
    }
    renderTabs();
  }

  // Left/right arrows switch tabs when no text field has focus and no window is open.
  document.addEventListener("keydown", function (e) {
    if ((e.key !== "ArrowLeft" && e.key !== "ArrowRight") || e.altKey || e.ctrlKey || e.metaKey) return;
    var a = document.activeElement;
    if (a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA" || a.tagName === "SELECT")) return;
    if (currentWindow || link.open || !state.items.length || (dash.page !== "stash" && dash.page !== "orders")) return;
    var ids = visibleTabs().map(function (t) { return t.id; });
    var i = ids.indexOf(state.tab);
    var next = ids[(i + (e.key === "ArrowRight" ? 1 : ids.length - 1)) % ids.length];
    e.preventDefault();
    selectTab(next);
  });

  // ---------- shulker groups ----------

  function itemByKey(key) {
    var e = state.byKey.get(key);
    return e ? e.item : null;
  }

  function itemName(key) {
    var it = itemByKey(key);
    return it ? it.name : key;
  }

  /** "Diamond ×1,728, Golden Apple ×64, …" and "N stacks" for a shulker group's tooltip. */
  function shulkerSummary(contents) {
    var totals = new Map();
    contents.forEach(function (c) { totals.set(c.key, (totals.get(c.key) || 0) + c.count); });
    var top = Array.from(totals.entries()).sort(function (a, b) { return b[1] - a[1]; });
    var lines = top.slice(0, 4).map(function (t) { return itemName(t[0]) + " ×" + grouped(t[1]); });
    if (top.length > 4) lines.push("…and " + (top.length - 4) + " more");
    lines.push(plural(String(contents.length), "stack"));
    return lines;
  }

  function normalizeShulker(g) {
    var contents = Array.isArray(g.contents) ? g.contents.filter(function (c) {
      return c && typeof c === "object" && typeof c.key === "string" && num(c.slot, -1) >= 0 && num(c.slot, -1) <= 26;
    }).map(function (c) {
      return { slot: Math.floor(c.slot), key: c.key, count: Math.max(0, Math.floor(num(c.count, 0))) };
    }) : [];
    var id = str(g.id);
    var base = itemByKey(str(g.itemKey) || id);
    return {
      key: str(g.key),
      id: id,
      name: str(g.name) || (base ? base.name : id),
      englishName: base ? base.englishName : str(g.name) || id,
      count: Math.max(0, Math.floor(num(g.count, 0))),
      maxStack: 1,
      details: [],
      enchanted: false,
      tab: "shulkers",
      tabOrder: 0,
      isShulker: true,
      itemKey: str(g.itemKey),
      contents: contents
    };
  }

  /** Builds the Shulkers tab entries, reusing slots by group key like updateItems does for items. */
  function updateShulkers(index, mc) {
    var raw = Array.isArray(index.shulkers) ? index.shulkers : [];
    var prev = state.shulkerByKey || new Map();
    var next = new Map();
    var out = [];
    raw.forEach(function (g) {
      if (!g || typeof g !== "object" || !str(g.key) || next.has(str(g.key))) return;
      var item = normalizeShulker(g);
      item.details = shulkerSummary(item.contents);
      var entry = prev.get(item.key);
      if (!entry || entry.url !== iconUrl(item.id, mc)) {
        entry = createEntry(item, mc);
      } else {
        var old = entry.item;
        entry.item = item;
        if (old.count !== item.count) setCount(entry);
      }
      setFace(entry, shulkerFace(item.contents)); // after the items: names and ids come from them
      entry.hay = haystack(item);
      next.set(item.key, entry);
      out.push(entry);
    });
    state.shulkerByKey = next;
    state.shulkers = out;
    state.hasShulkerData = Array.isArray(index.shulkers);
    updateEmptyCounts();
  }

  /**
   * A shulker-box item counts every box of its kind, filled or not. Where filled groups exist, its slot in the
   * creative tabs and Search shows only the empty ones (plain icon), and the filled groups are listed right
   * after it with their contents icons (see withFilledGroups). Groups match their base item like
   * filledGroupsFor does: by itemKey, or by id for older indexes.
   */
  function updateEmptyCounts() {
    var filled = new Map();
    state.items.forEach(function (e) {
      if (!isShulkerBoxId(e.item.id)) return;
      var groups = filledGroupsFor(e.item);
      if (groups.length) filled.set(e, groups);
    });
    state.items.forEach(function (e) {
      var groups = filled.get(e) || null;
      var before = e.emptyCount;
      e.filledGroups = groups;
      e.emptyCount = groups ? Math.max(0, e.item.count - groups.reduce(function (n, g) { return n + g.item.count; }, 0)) : undefined;
      if (before !== e.emptyCount) setCount(e);
    });
  }

  /**
   * The creative tabs and Search: each shulker-box item with filled groups becomes its empty boxes (if any are
   * left, and it matches the search) followed by its filled groups that match, most first. Groups are found by
   * their own name and contents too, so searching "diamond" shows the shulkers full of diamonds.
   */
  function withFilledGroups(sorted, query) {
    var out = [];
    sorted.forEach(function (e) {
      if (!e.filledGroups) {
        out.push(e);
        return;
      }
      if (e.emptyCount > 0 && matches(e, query)) out.push(e);
      e.filledGroups.filter(function (g) { return matches(g, query); })
        .sort(sorters.count)
        .forEach(function (g) { out.push(g); });
    });
    return out;
  }

  function filledGroupsFor(item) {
    return state.shulkers.filter(function (e) {
      var g = e.item;
      return g.itemKey ? g.itemKey === item.key : g.id === item.id;
    });
  }

  // ---------- windows (shulker preview, chooser) ----------

  var currentWindow = null;

  function closeWindow() {
    if (!currentWindow) return;
    currentWindow.remove();
    currentWindow = null;
    hideTooltip();
  }

  /** A vanilla-GUI-style window on a backdrop, with a clickable X. Escape and the backdrop also close it. */
  function openWindow(title, body, extraClass) {
    closeWindow();
    var backdrop = document.createElement("div");
    backdrop.className = "modal-backdrop";
    var win = document.createElement("div");
    win.className = "panel window" + (extraClass ? " " + extraClass : "");
    win.setAttribute("role", "dialog");
    win.setAttribute("aria-modal", "true");
    var head = document.createElement("div");
    head.className = "window-head";
    var h = document.createElement("div");
    h.className = "window-title";
    h.textContent = title;
    var x = document.createElement("button");
    x.type = "button";
    x.className = "mc-button small";
    x.textContent = "X";
    x.setAttribute("aria-label", "Close");
    x.addEventListener("click", closeWindow);
    head.appendChild(h);
    head.appendChild(x);
    win.appendChild(head);
    win.appendChild(body);
    backdrop.appendChild(win);
    backdrop.addEventListener("mousedown", function (e) {
      if (e.target === backdrop) closeWindow();
    });
    document.body.appendChild(backdrop);
    currentWindow = backdrop;
    x.focus();
    return win;
  }

  function contentEntry(c) {
    var base = itemByKey(c.key);
    var item = base ? Object.assign({}, base, { count: c.count }) : {
      key: c.key, id: c.key.split("#")[0], name: c.key, englishName: c.key, count: c.count,
      maxStack: 64, details: [], enchanted: false, tab: "other", tabOrder: 1000000
    };
    return createEntry(item, state.mc);
  }

  /** The shulker-box preview: the 9x3 grid with contents at their slots, plus "Add to order". */
  function openShulkerPreview(group) {
    var body = document.createElement("div");
    var grid = document.createElement("div");
    grid.className = "shulker-grid";
    var bySlot = {};
    group.contents.forEach(function (c) { bySlot[c.slot] = c; });
    for (var i = 0; i < 27; i++) {
      if (bySlot[i]) {
        grid.appendChild(contentEntry(bySlot[i]).slot);
      } else {
        var empty = document.createElement("div");
        empty.className = "slot empty-slot";
        grid.appendChild(empty);
      }
    }
    body.appendChild(grid);

    var info = document.createElement("div");
    info.className = "window-info";
    info.textContent = group.count === 1 ? "1 shulker like this" : grouped(group.count) + " identical shulkers like this";
    body.appendChild(info);

    var avail = availableLeft("shulker", group.key);
    var row = document.createElement("div");
    row.className = "window-actions";
    var input = document.createElement("input");
    input.type = "number";
    input.className = "mc-input qty-input";
    input.min = "1";
    input.max = String(Math.max(1, avail));
    input.value = "1";
    var add = document.createElement("button");
    add.type = "button";
    add.className = "mc-button";
    add.textContent = "Add to order";
    var msg = document.createElement("span");
    msg.className = "note";
    if (!can("order")) {
      add.hidden = true;
      input.hidden = true;
    } else if (avail < 1) {
      add.disabled = true;
      input.disabled = true;
      msg.textContent = "None left to order (reserved or already in the cart).";
    } else {
      msg.textContent = grouped(avail) + " available";
    }
    add.addEventListener("click", function () {
      var n = clampInt(input.value, 1, availableLeft("shulker", group.key));
      if (n < 1) return;
      if (!addToCart("shulker", group.key, n)) return;
      toast("Added " + n + " × " + group.name + " (packed) to the cart");
      closeWindow();
    });
    row.appendChild(input);
    row.appendChild(add);
    row.appendChild(msg);
    body.appendChild(row);
    openWindow(group.name, body, "shulker-window");
  }

  /** Right-click on an aggregated shulker-box item: choose one of its filled groups. */
  function openShulkerChooser(item) {
    var groups = filledGroupsFor(item);
    var body = document.createElement("div");
    if (!groups.length) {
      var p = document.createElement("p");
      p.className = "window-info";
      p.textContent = state.hasShulkerData
        ? "All empty. None of these shulkers hold items."
        : "This index has no shulker contents. Update the Qwerte addon to see what is inside.";
      body.appendChild(p);
      openWindow(item.name, body, "chooser-window");
      return;
    }
    var hint = document.createElement("div");
    hint.className = "window-info";
    hint.textContent = "Filled ones (" + grouped(groups.reduce(function (n, e) { return n + e.item.count; }, 0)) +
      " of " + grouped(item.count) + "). Click one to look inside.";
    body.appendChild(hint);
    var grid = document.createElement("div");
    grid.className = "item-grid chooser-grid";
    groups.forEach(function (g) {
      var e = createEntry(g.item, state.mc);
      e.onActivate = function () { openShulkerPreview(g.item); };
      grid.appendChild(e.slot);
    });
    body.appendChild(grid);
    openWindow(item.name, body, "chooser-window");
  }

  /** What right-click on a slot does (touch: what a tap on a main-grid slot does). */
  function slotContextAction(entry) {
    if (entry.onContext) entry.onContext();
    else if (entry.onActivate) entry.onActivate();
    else if (entry.item.isShulker) openShulkerPreview(entry.item);
    else if (isShulkerBoxId(entry.item.id)) openShulkerChooser(entry.item);
  }

  // Touch equivalents for the main item grid (mouse behaviour is unchanged):
  // tap = right-click (look inside a shulker), long-press = shift-click (add a stack to the cart).
  var LONG_PRESS_MS = 500;
  var LONG_PRESS_SLOP = 10;
  var touchPress = { pointer: "mouse", timer: 0, x: 0, y: 0, firedUntil: 0 };

  document.addEventListener("pointerdown", function (e) { touchPress.pointer = e.pointerType || "mouse"; }, true);

  function cancelLongPress() {
    window.clearTimeout(touchPress.timer);
    touchPress.timer = 0;
  }

  el.grid.addEventListener("pointerdown", function (e) {
    cancelLongPress();
    if (e.pointerType === "mouse" || !e.isPrimary) return;
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry || e.target.closest(".slot-add")) return;
    touchPress.x = e.clientX;
    touchPress.y = e.clientY;
    touchPress.timer = window.setTimeout(function () {
      touchPress.timer = 0;
      touchPress.firedUntil = Date.now() + 1000; // swallow the click (and menu) that follow this press
      hideTooltip();
      quickAdd(entry.item);
    }, LONG_PRESS_MS);
  });
  /**
   * Main-grid taps, registered on each slot (createEntry). A listener on the slot itself makes it a tap target
   * for the browser's touch adjustment, which otherwise snaps a tap on the slot onto its nearby "+" button.
   */
  function gridSlotClick(e) {
    var slot = e.currentTarget;
    var entry = el.grid.contains(slot) ? slotEntry.get(slot) : null;
    if (!entry) return;
    if (e.target.closest(".slot-add")) {
      e.preventDefault();
      quickAdd(entry.item);
    } else if (touchPress.firedUntil > Date.now()) {
      touchPress.firedUntil = 0; // the click that ends a long-press
      e.preventDefault();
      hideTooltip(); // the browser's compatibility mouseover showed it again
    } else if (touchPress.pointer !== "mouse" && !e.shiftKey && !entry.onActivate) {
      e.preventDefault();
      slotContextAction(entry);
    }
  }
  el.grid.addEventListener("pointermove", function (e) {
    if (touchPress.timer && Math.hypot(e.clientX - touchPress.x, e.clientY - touchPress.y) > LONG_PRESS_SLOP) cancelLongPress();
  });
  ["pointerup", "pointercancel", "pointerleave"].forEach(function (t) { el.grid.addEventListener(t, cancelLongPress); });
  el.gridWrap.addEventListener("scroll", cancelLongPress, { passive: true });
  window.addEventListener("scroll", cancelLongPress, { passive: true });

  // Right-click opens shulker windows; the browser menu is suppressed only on slots.
  document.addEventListener("contextmenu", function (e) {
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry) return;
    e.preventDefault();
    // A touch long-press also raises a context menu: on the main grid the press itself is the action.
    if (touchPress.pointer !== "mouse" && el.grid.contains(slot)) return;
    slotContextAction(entry);
  });

  // Left click: windows' clickable slots, the order picker, and shift-click to add a stack to the cart.
  document.addEventListener("click", function (e) {
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry || e.defaultPrevented) return; // handled by the item grid's touch handler
    if (entry.onActivate) {
      entry.onActivate();
      return;
    }
    if (e.shiftKey && el.grid.contains(slot)) {
      e.preventDefault();
      quickAdd(entry.item);
    }
  });

  function quickAdd(item) {
    if (!can("order")) {
      toast("Your account can't place orders.");
      return;
    }
    var type = item.isShulker ? "shulker" : "item";
    var left = availableLeft(type, item.key);
    var n = Math.min(item.isShulker ? 1 : item.maxStack, left);
    if (n < 1) {
      toast("None left to order: " + item.name);
      return;
    }
    if (!addToCart(type, item.key, n)) return;
    if (!plan.toasted) toast("Added " + n + " × " + displayName(type, item) + " to the cart");
  }

  // ---------- toast ----------

  var toastTimer = 0;

  function toast(text) {
    el.toast.textContent = text;
    if (streamerOn()) el.toast.setAttribute("data-sensitive", "");
    else el.toast.removeAttribute("data-sensitive");
    el.toast.hidden = false;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { el.toast.hidden = true; }, 2500);
  }

  // ---------- orders: availability and cart ----------

  var orders = {
    list: [],
    available: null, // { items: {key: n}, shulkers: {key: n} } from the server
    timer: 0,
    loading: false,
    token: 0,
    expanded: new Set(),
    retryErrors: new Map() // order id -> why the last retry was refused
  };
  var cart = { lines: [], badLine: -1, repairAll: false };
  var KB_ID = /^t-[0-9a-z]{8}$/; // a Kit Builder kit's id (a kit line's key)
  var picker = { level: "types", type: null, entry: null, search: "", slots: [], qtyHost: null };

  function clampInt(v, lo, hi) {
    var n = Math.floor(Number(v));
    if (!isFinite(n)) n = lo;
    return Math.max(lo, Math.min(hi, n));
  }

  /** What the server says can still be ordered (index count minus open orders), before this cart. */
  function availableTotal(type, key) {
    if (type === "kit") return MAX_SHULKERS; // the server checks the stock at placement and answers with the shortfall
    var a = orders.available;
    var map = a ? (type === "shulker" ? a.shulkers : a.items) : null;
    if (map && typeof map[key] === "number") return map[key];
    if (type === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(key);
      return g ? g.item.count : 0;
    }
    var it = itemByKey(key);
    if (!it) return 0;
    if (isShulkerBoxId(it.id)) {
      var filled = filledGroupsFor(it).reduce(function (n, e) { return n + e.item.count; }, 0);
      return Math.max(0, it.count - filled);
    }
    return it.count;
  }

  function cartCount(type, key) {
    var n = 0;
    cart.lines.forEach(function (l) { if (l.type === type && l.key === key) n += l.count; });
    return n;
  }

  /** Available for a new addition: server availability minus what this cart already holds. */
  function availableLeft(type, key) {
    return Math.max(0, availableTotal(type, key) - cartCount(type, key));
  }

  function lineItem(type, key) {
    if (type === "kit") return kbLineItem(key);
    if (type === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(key);
      return g ? g.item : null;
    }
    return itemByKey(key);
  }

  function displayName(type, item) {
    if (type === "kit") return "Kit: " + item.name;
    if (type === "shulker") return item.name + " (packed)";
    if (isShulkerBoxId(item.id)) return "Empty " + item.name;
    return item.name;
  }

  function cartStorageKey() {
    return state.selected ? "stashManager.cart." + state.selected : null;
  }

  function loadCart() {
    cart.lines = [];
    cart.badLine = -1;
    cart.repairAll = false;
    var k = cartStorageKey();
    if (!k) return;
    try {
      cart.repairAll = window.localStorage.getItem(k + ".repairAll") === "1";
    } catch (e) {
      cart.repairAll = false;
    }
    try {
      var raw = JSON.parse(window.localStorage.getItem(k) || "[]");
      if (Array.isArray(raw)) {
        raw.forEach(function (l) {
          if (l && (l.type === "item" || l.type === "shulker" || (l.type === "kit" && KB_ID.test(str(l.key)))) && typeof l.key === "string" && num(l.count, 0) >= 1) {
            var cl = { type: l.type, key: l.key, count: Math.floor(l.count) };
            if (l.type === "item" && l.repair === true) cl.repair = true;
            cart.lines.push(cl);
          }
        });
      }
    } catch (e) {
      cart.lines = [];
    }
  }

  function saveCart() {
    var k = cartStorageKey();
    if (!k) return;
    try {
      window.localStorage.setItem(k, JSON.stringify(cart.lines));
      if (cart.repairAll) window.localStorage.setItem(k + ".repairAll", "1");
      else window.localStorage.removeItem(k + ".repairAll");
    } catch (e) {
      // Storage full or disabled: the cart just isn't remembered.
    }
  }

  // ---------- orders: packing (mirrors the addon's Order.pack) ----------
  // Item lines become slot loads: floor(count / maxStack) full stacks plus one remainder stack. Loads are grouped
  // by item key (keys in ascending UTF-16 order, like Java's TreeMap), the groups are stable-sorted by number of
  // loads (most first), loads inside a group by count (largest first), and the result fills 27-slot shulkers in
  // sequence. Shulker lines and empty-shulker lines are one unit each and come first, in that order.

  var MAX_SHULKERS = 54;
  var SHULKER_SLOTS = 27;

  /** True when an item line for this key means empty shulkers. */
  function isEmptyShulkerLine(l) {
    if (l.type !== "item") return false;
    var it = itemByKey(l.key);
    return !!it && isShulkerBoxId(it.id);
  }

  function lineMaxStack(key) {
    var it = itemByKey(key);
    return it ? it.maxStack : 64;
  }

  /** Shulkers the lines need, without building the plan (cheap, safe for huge counts). */
  function shulkersNeeded(lines) {
    var units = 0;
    var slots = 0;
    lines.forEach(function (l) {
      if (l.type === "shulker" || l.type === "kit" || isEmptyShulkerLine(l)) units += l.count;
      else slots += Math.ceil(l.count / lineMaxStack(l.key));
    });
    return units + Math.ceil(slots / SHULKER_SLOTS);
  }

  /** The planned delivery: units in chest order. null when it needs more than 54 shulkers. */
  function planCart(lines) {
    if (shulkersNeeded(lines) > MAX_SHULKERS) return null;
    var shulkerUnits = [];
    var emptyUnits = [];
    var kitUnits = [];
    var byKey = {};
    var keys = [];
    lines.forEach(function (l) {
      var i;
      if (l.type === "shulker") {
        for (i = 0; i < l.count; i++) shulkerUnits.push({ kind: "shulker", key: l.key });
      } else if (l.type === "kit") {
        for (i = 0; i < l.count; i++) kitUnits.push({ kind: "kit", key: l.key });
      } else if (isEmptyShulkerLine(l)) {
        for (i = 0; i < l.count; i++) emptyUnits.push({ kind: "empty", key: l.key });
      } else {
        var max = Math.max(1, lineMaxStack(l.key));
        if (!Object.prototype.hasOwnProperty.call(byKey, l.key)) {
          byKey[l.key] = [];
          keys.push(l.key);
        }
        for (var left = l.count; left > 0; left -= max) byKey[l.key].push({ key: l.key, count: Math.min(max, left) });
      }
    });
    keys.sort(function (a, b) { return a < b ? -1 : a > b ? 1 : 0; });
    var groups = keys.map(function (k) { return byKey[k]; });
    groups.sort(function (a, b) { return b.length - a.length; }); // Array.prototype.sort is stable
    var bins = [];
    groups.forEach(function (g) {
      g.sort(function (a, b) { return b.count - a.count; });
      g.forEach(function (lot) {
        if (!bins.length || bins[bins.length - 1].lots.length >= SHULKER_SLOTS) bins.push({ kind: "bin", lots: [] });
        bins[bins.length - 1].lots.push(lot);
      });
    });
    // The server's delivery order: packed shulkers, empty shulkers, kits, then the packed bins.
    return shulkerUnits.concat(emptyUnits, kitUnits, bins);
  }

  function capMessage(n) {
    var now = Math.min(shulkersNeeded(cart.lines), MAX_SHULKERS);
    return "That needs " + n + " shulkers. One order can fill at most " + MAX_SHULKERS + " (one double chest); the cart plans " + now + ".";
  }

  // ---------- orders: cart changes ----------

  /**
   * Adds n of a line, unless the cart would then need more than 54 shulkers (then nothing changes and a
   * message is shown). Returns whether it was added.
   */
  function addToCart(type, key, n) {
    var next = cart.lines.map(function (l) { return l.repair ? { type: l.type, key: l.key, count: l.count, repair: true } : { type: l.type, key: l.key, count: l.count }; });
    var found = null;
    next.forEach(function (l) { if (l.type === type && l.key === key) found = l; });
    if (found) found.count += n;
    else next.push({ type: type, key: key, count: n });
    var need = shulkersNeeded(next);
    if (need > MAX_SHULKERS) {
      refuse(capMessage(need));
      return false;
    }
    cart.lines = next;
    cart.badLine = -1;
    plan.selected = null; // follow the last shulker, so the user watches it fill
    plan.watchFull = true;
    saveCart();
    renderCart();
    updatePickerCounts();
    return true;
  }

  /** Removes up to n of a line (the whole line when it reaches 0). */
  function removeFromCart(type, key, n) {
    for (var i = 0; i < cart.lines.length; i++) {
      var l = cart.lines[i];
      if (l.type !== type || l.key !== key) continue;
      l.count -= n;
      if (l.count < 1) cart.lines.splice(i, 1);
      break;
    }
    cart.badLine = -1;
    saveCart();
    renderCart();
    updatePickerCounts();
  }

  function refuse(text) {
    toast(text);
    el.cartStatus.textContent = text;
    el.cartStatus.classList.add("warn");
  }

  function clearStatus() {
    el.cartStatus.textContent = "";
    el.cartStatus.classList.remove("warn");
  }

  /** Double-click or drop: one stack, or with Shift a shulker's worth (27 stacks), clamped to what is left. */
  function addStacks(type, item, shulkerWorth) {
    var left = availableLeft(type, item.key);
    var unit = type === "shulker" ? 1 : item.maxStack;
    var n = Math.min(shulkerWorth && type !== "shulker" ? unit * SHULKER_SLOTS : unit, left);
    if (n < 1) {
      toast("None left to order: " + displayName(type, item));
      return;
    }
    if (addToCart(type, item.key, n)) {
      clearStatus();
      if (!plan.toasted) toast("Added " + grouped(n) + " × " + displayName(type, item));
    }
  }

  function onStashChanged() {
    dlv.token++;
    dlv.key = null;
    dlv.hash = null;
    dlv.data = null;
    deliveryMaybeLoad();
    loadCart();
    orders.list = [];
    orders.available = null;
    orders.token++;
    picker.level = "types";
    picker.type = null;
    picker.entry = null;
    plan.selected = null;
    plan.fullCount = -1;
    resetNames();
    el.cartStatus.textContent = "";
    if (state.tab === "orders") {
      renderPicker();
      renderCart();
    }
    renderBotStatus();
    renderOrderList();
    scheduleOrdersPoll(0);
    kitsReset();
    kbReset();
  }

  /** Re-renders the parts of the Orders tab that depend on the index (after every index refresh). */
  function refreshOrdersView() {
    if (state.tab !== "orders") return;
    if (!el.pickerBody.contains(document.activeElement) && !drag.pe) renderPicker();
    else updatePickerCounts();
    renderCart();
  }

  // ---------- orders: bot status ----------

  function renderBotStatus() {
    var s = currentStash();
    if (!s) {
      el.deliveryStatus.textContent = "";
      el.deliveryStatus.className = "bot-line";
      el.orderBlocker.hidden = true;
      renderCraftInfo();
      return;
    }
    var d = s.delivery;
    if (d && typeof d === "object" && typeof d.x === "number" && typeof d.y === "number" && typeof d.z === "number") {
      el.deliveryStatus.replaceChildren(document.createTextNode("Delivery container: "),
        hiddenSpan(d.x + ", " + d.y + ", " + d.z + (str(d.dimension) ? " (" + str(d.dimension) + ")" : "")));
      el.deliveryStatus.className = "bot-line ok";
    } else {
      el.deliveryStatus.textContent = "No delivery container set: in Meteor open stash-manager → Delivery → " +
        "select-delivery, then right-click a chest.";
      el.deliveryStatus.className = "bot-line warn";
    }
    var blocker = str(s.orderBlocker);
    el.orderBlocker.hidden = !blocker;
    el.orderBlocker.textContent = blocker ? "Bot can't take orders right now: " + blocker : "";
    renderNamesWarn();
  }

  // ---------- orders: shulker names ----------
  // No names, one name for every shulker, or a name per planned shulker (in delivery order, as planCart lays them
  // out). The rows are updated in place so typing isn't interrupted when the cart or the index refreshes; names
  // typed for a shulker are kept by its index while the cart changes.

  var names = { mode: "none", all: "", each: [], rows: [] };

  function resetNames() {
    names.all = "";
    names.each = [];
    el.namesAll.value = "";
    names.rows.forEach(function (r) { r.input.value = ""; });
    setNamesMode("none");
  }

  function setNamesMode(mode) {
    names.mode = mode;
    Array.prototype.forEach.call(el.namesSeg.querySelectorAll(".seg-btn"), function (b) {
      b.setAttribute("aria-pressed", b.getAttribute("data-mode") === mode ? "true" : "false");
    });
    el.namesAll.hidden = mode !== "all";
    el.namesEach.hidden = mode !== "each";
    renderNames();
  }

  el.namesSeg.addEventListener("click", function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest(".seg-btn") : null;
    if (!b) return;
    setNamesMode(b.getAttribute("data-mode"));
    if (names.mode === "all") el.namesAll.focus();
  });

  el.namesAll.addEventListener("input", function () {
    names.all = el.namesAll.value;
    renderNamesWarn();
  });

  /** What a planned shulker will hold: "Diamond ×128, Golden Apple ×64", "Packed shulker: …", "Empty red shulker". */
  function unitSummary(u) {
    if (u.kind === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(u.key);
      return "Packed shulker: " + (g ? g.item.name : u.key);
    }
    if (u.kind === "kit") {
      var kt = kbTemplate(u.key);
      return "Kit: " + (streamerOn() ? "a kit" : kt ? kt.name : u.key) + " (named after the kit)";
    }
    if (u.kind === "empty") {
      var it = itemByKey(u.key);
      var colour = (it ? it.id : "").replace(/^minecraft:/, "").replace(/_?shulker_box$/, "").replace(/_/g, " ");
      return "Empty " + (colour ? colour + " " : "") + "shulker";
    }
    var totals = new Map();
    u.lots.forEach(function (lot) { totals.set(lot.key, (totals.get(lot.key) || 0) + lot.count); });
    var top = Array.from(totals.entries()).sort(function (a, b) { return b[1] - a[1]; });
    var text = top.slice(0, 3).map(function (t) { return itemName(t[0]) + " ×" + grouped(t[1]); }).join(", ");
    return top.length > 3 ? text + ", +" + (top.length - 3) + " more" : text;
  }

  function nameRow(i) {
    var row = document.createElement("div");
    row.className = "names-row";
    var label = document.createElement("label");
    label.className = "names-label";
    label.htmlFor = "names-each-" + i;
    label.textContent = "Shulker " + (i + 1);
    var what = document.createElement("span");
    what.className = "names-what";
    var input = document.createElement("input");
    input.type = "text";
    input.id = "names-each-" + i;
    input.className = "mc-input";
    input.maxLength = 50;
    input.placeholder = "unnamed";
    input.autocomplete = "off";
    input.spellcheck = false;
    input.value = names.each[i] || "";
    input.addEventListener("input", function () {
      names.each[i] = input.value;
      renderNamesWarn();
    });
    row.appendChild(label);
    row.appendChild(what);
    row.appendChild(input);
    return { row: row, what: what, input: input, text: null };
  }

  function renderNames() {
    var units = plan.units;
    if (names.mode === "each") {
      while (names.rows.length < units.length) {
        var r = nameRow(names.rows.length);
        names.rows.push(r);
        el.namesRows.appendChild(r.row);
      }
      while (names.rows.length > units.length) {
        var gone = names.rows.pop();
        if (gone.row.contains(document.activeElement)) gone.input.blur();
        gone.row.remove();
      }
      units.forEach(function (u, i) {
        var text = unitSummary(u);
        var r = names.rows[i];
        var blur = u.kind === "kit" && streamerOn();
        var shownKey = text + "|" + blur;
        if (r.text !== shownKey) {
          r.text = shownKey;
          r.what.textContent = text;
          r.what.title = blur ? "" : text;
          if (blur) r.what.setAttribute("data-sensitive", "");
          else r.what.removeAttribute("data-sensitive");
        }
        // A kit is named with its template's name: what is typed for it is ignored by the bot.
        r.input.disabled = u.kind === "kit";
        r.input.placeholder = u.kind === "kit" ? "named after the kit" : "unnamed";
      });
      var hint = el.namesRows.querySelector(".names-empty");
      if (!units.length && !hint) el.namesRows.appendChild(line("note names-empty", "Add items to the cart to name its shulkers."));
      if (units.length && hint) hint.remove();
    }
    renderNamesWarn();
  }

  /** How many planned shulkers would get a name. */
  function namedCount() {
    var n = plan.units.length;
    if (names.mode === "all") return names.all.trim() ? n : 0;
    if (names.mode !== "each") return 0;
    var c = 0;
    for (var i = 0; i < n; i++) if (names.each[i] && names.each[i].trim() && plan.units[i].kind !== "kit") c++;
    return c;
  }

  /** The order's names field: null, {mode: "all", name} or {mode: "each", names: [one per planned shulker]}. */
  function namesBody() {
    if (names.mode === "all") return names.all.trim() ? { mode: "all", name: names.all.trim() } : null;
    if (names.mode !== "each") return null;
    var list = [];
    // At most 54 (the server's cap): a bigger cart is refused for its size anyway, and that error should be the one shown
    for (var i = 0; i < Math.min(plan.units.length, 54); i++) list.push(plan.units[i].kind === "kit" ? "" : (names.each[i] || "").trim());
    return list.some(function (v) { return v; }) ? { mode: "each", names: list } : null;
  }

  /** Warnings from the stash's bot (heartbeat "naming"). Only a warning: the order can still be placed. */
  function renderNamesWarn() {
    var text = namesWarnText(currentStash(), namedCount());
    if (el.namesWarn.textContent !== text) el.namesWarn.textContent = text;
    el.namesWarn.hidden = !text;
    renderCraftInfo();
  }

  /** The naming warning for n named shulkers from stash s's bot, or "" (also used by the build planner). */
  function namesWarnText(s, n) {
    var text = "";
    if (s && n > 0) {
      var nm = s.naming && typeof s.naming === "object" ? s.naming : null;
      if (!nm) {
        text = "This bot can't name shulkers; they'll arrive unnamed.";
      } else if (nm.anvil === false && typeof nm.anvilSource === "string") {
        // A newer bot finds an anvil in the stash area, or places one from stock, when none is set.
        text = "No anvil yet: the bot will look for one in the stash area, or place one from the stash. " +
          "If it can't, the shulkers arrive unnamed.";
      } else if (nm.anvil === false) {
        text = "No anvil set; the shulkers will arrive unnamed. Set one on the bot: in Meteor, stash-manager → select-anvil; " +
          "on the proxy, stash anvil here.";
      } else if (typeof nm.xpLevels === "number" && nm.xpLevels >= 0 && nm.xpLevels < n) {
        text = "May run out of XP: about 1 level per shulker. The bot has " + plural(String(nm.xpLevels), "level") + " for " +
          plural(String(n), "named shulker") + "; any it can't name arrive unnamed.";
      }
    }
    return text;
  }

  /** "3 delivery chests, 1 full, can put up more" from the bot's heartbeat "deliveryStorage", or "". */
  function storageText(ds) {
    if (!ds || typeof ds !== "object") return "";
    var chests = Math.max(0, Math.floor(num(ds.chests, 0)));
    var full = Math.max(0, Math.floor(num(ds.full, 0)));
    if (!chests) return "";
    var text = plural(grouped(chests), "delivery chest") + (full ? ", " + grouped(full) + " full" : "");
    var blocked = str(ds.blocked);
    if (ds.canExpand === true) text += ", can put up more when full";
    else if (blocked) text += full >= chests ? ": waiting for a player to empty one (" + blocked + ")" : " (can't put up more: " + blocked + ")";
    else if (ds.autoExpand === false) text += " (auto expand is off)";
    return text + ".";
  }

  /** Info from the stash's bot (heartbeat "crafting" and "deliveryStorage"). */
  function renderCraftInfo() {
    var s = currentStash();
    var c = s && s.crafting && typeof s.crafting === "object" ? s.crafting : null;
    var text = "";
    if (c && c.enabled === true) {
      // A newer bot says where its table comes from (tableSource); without one yet it can find, place or craft one.
      var src = typeof c.tableSource === "string" ? c.tableSource : "";
      var named = src === "set" || src === "found" || src === "placed" || src === "crafted";
      if (c.table === true) {
        text = "The bot may craft missing empty shulker boxes from the stash's materials" + (named ? " (crafting table: " + src + ")." : ".");
      } else if (src) {
        text = "No crafting table yet: the bot will look for one in the stash area, place one from the stash, or craft one from planks or logs.";
      } else {
        text = "Crafting is on, but no crafting table is set: the bot can't craft shulker boxes.";
      }
    }
    var st = storageText(s && s.deliveryStorage);
    if (st) text = text ? text + " " + st : st;
    if (el.craftInfo.textContent !== text) el.craftInfo.textContent = text;
    el.craftInfo.hidden = !text;
  }

  // ---------- orders: delivery chest and shulker view ----------
  // A fixed pool of 54 chest slots and 27 shulker slots, updated in place. Icons are only reloaded when their
  // URL changes. Pooled slots are registered in slotEntry, so they get the usual tooltip.

  var plan = { units: [], selected: null, fullCount: -1, watchFull: false, toasted: false, chest: [], bin: [], slotUnit: [], unitSlot: [] };

  // ---------- orders: what is already in the delivery storage ----------
  // The bot's latest report (PROTOCOL.md, "Additions: delivery storage contents"), fetched when the stash list's
  // summary changes. Only for the delivery chest preview: never part of the stock counts. Names come from players:
  // textContent only.

  var dlv = { key: null, hash: null, data: null, token: 0 };

  function deliveryMaybeLoad() {
    var s = currentStash();
    var sum = s && s.deliveryContents && typeof s.deliveryContents === "object" ? s.deliveryContents : null;
    var key = s ? stashKey(s) : null;
    if (!sum) {
      if (dlv.data || dlv.key !== key) {
        dlv.key = key;
        dlv.hash = null;
        dlv.data = null;
        if (state.tab === "orders") renderCart();
      }
      return;
    }
    var hash = str(sum.hash) + "|" + num(sum.receivedAt, 0);
    if (dlv.key === key && dlv.hash === hash) return;
    var token = ++dlv.token;
    api("/api/v1/delivery?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== dlv.token) return;
      dlv.key = key;
      dlv.hash = hash;
      dlv.data = d && typeof d === "object" && Array.isArray(d.chests) ? d : null;
      if (state.tab === "orders") renderCart();
    }, function () {});
  }

  /** The report's chests (in fill order), or [] (an older bot, or no report yet). */
  function deliveryChests() {
    var s = currentStash();
    if (!dlv.data || !s || dlv.key !== stashKey(s)) return [];
    return dlv.data.chests.filter(function (c) { return c && typeof c === "object" && (c.size === 27 || c.size === 54); });
  }

  /** A readable name for an item id: from the stash index when it has the item, else the id. */
  function nameForId(id) {
    for (var i = 0; i < state.items.length; i++) if (state.items[i].id === id) return state.items[i].name;
    return id.replace(/^minecraft:/, "");
  }

  function poolSlot(extraClass, bar) {
    var slot = document.createElement("div");
    slot.className = "slot " + extraClass;
    var img = document.createElement("img");
    img.alt = "";
    img.width = 32;
    img.height = 32;
    img.draggable = false;
    img.hidden = true;
    var countEl = document.createElement("span");
    countEl.className = "count";
    slot.appendChild(img);
    slot.appendChild(countEl);
    var p = { slot: slot, img: img, countEl: countEl, url: null, tries: 0, item: null, fill: null };
    if (bar) {
      var b = document.createElement("div");
      b.className = "fill-bar";
      p.fill = document.createElement("div");
      p.fill.className = "fill";
      b.appendChild(p.fill);
      slot.appendChild(b);
    }
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      p.tries++;
      if (p.tries > ICON_RETRIES) return;
      var n = p.tries;
      var url = p.url;
      window.setTimeout(function () {
        if (p.url === url && img.isConnected) img.src = url + "&retry=" + n;
      }, retryDelay(n));
    });
    return p;
  }

  /** Shows an item in a pooled slot (null clears it). item is what the tooltip shows; contents a shulker's. */
  function fillSlot(p, id, item, countText, contents) {
    setFace(p, id ? shulkerFace(contents) : null);
    var url = id ? iconUrl(id, state.mc) : null;
    if (url !== p.url) {
      p.url = url;
      p.tries = 0;
      if (url) {
        p.img.hidden = false;
        p.img.src = url;
      } else {
        p.img.hidden = true;
        p.img.removeAttribute("src");
      }
    }
    var text = countText || "";
    if (p.countEl.textContent !== text) p.countEl.textContent = text;
    p.item = item;
    setDurability(p.slot, item);
    if (item) slotEntry.set(p.slot, p);
    else slotEntry.delete(p.slot);
    p.slot.classList.toggle("empty-slot", !item);
  }

  function buildPools() {
    if (plan.chest.length) return;
    var i;
    for (i = 0; i < MAX_SHULKERS; i++) {
      (function (index) {
        var p = poolSlot("chest-slot", true);
        p.onActivate = function () {
          var ui = plan.slotUnit[index];
          if (ui == null || ui < 0) return;
          plan.selected = ui;
          renderPlan();
        };
        p.onContext = function () {
          var ui = plan.slotUnit[index];
          var u = ui == null || ui < 0 ? null : plan.units[ui];
          if (!u) return;
          if (u.kind === "bin") {
            plan.selected = ui;
            renderPlan();
          } else {
            removeFromCart(u.kind === "shulker" ? "shulker" : u.kind === "kit" ? "kit" : "item", u.key, 1);
          }
        };
        plan.chest.push(p);
        el.chestGrid.appendChild(p.slot);
      })(i);
    }
    for (i = 0; i < SHULKER_SLOTS; i++) {
      (function (index) {
        var p = poolSlot("bin-slot", false);
        p.onContext = function () {
          var u = plan.units[shownUnit()];
          if (u && u.kind === "bin" && u.lots[index]) removeFromCart("item", u.lots[index].key, u.lots[index].count);
        };
        plan.bin.push(p);
        el.binGrid.appendChild(p.slot);
      })(i);
    }
  }

  /** The unit shown in the 9x3 view: the clicked one, or the last one. */
  function shownUnit() {
    if (!plan.units.length) return -1;
    if (plan.selected !== null && plan.selected < plan.units.length) return plan.selected;
    return plan.units.length - 1;
  }

  function unitInfo(u, index, total) {
    var where = "Shulker " + (index + 1) + " of " + total;
    if (u.kind === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(u.key);
      var gi = g ? g.item : null;
      return {
        id: gi ? gi.id : "minecraft:shulker_box",
        used: gi ? gi.contents.length : 0,
        contents: gi ? gi.contents : [],
        title: (gi ? gi.name : u.key) + " (packed, as it is)",
        details: [where, "Right-click to remove it"]
      };
    }
    if (u.kind === "kit") {
      var kt = kbTemplate(u.key);
      var kcontents = kt ? kbContents(kt) : [];
      return {
        id: kt ? kbShulkerId(kt.color) : "minecraft:shulker_box",
        used: kcontents.length,
        contents: kcontents,
        title: "Kit: " + (streamerOn() ? "a kit" : kt ? kt.name : u.key),
        details: [where, "A finished kit, named after the template", "Right-click to remove it"]
      };
    }
    if (u.kind === "empty") {
      var it = itemByKey(u.key);
      return {
        id: it ? it.id : "minecraft:shulker_box",
        used: 0,
        contents: [],
        title: "Empty " + (it ? it.name : "Shulker Box"),
        details: [where, "Right-click to remove it"]
      };
    }
    return {
      id: "minecraft:shulker_box",
      used: u.lots.length,
      contents: u.lots.map(function (lot, i) { return { slot: i, key: lot.key, count: lot.count }; }),
      title: "Shulker Box (packed for you)",
      details: [where].concat(shulkerSummary(u.lots), ["Click to look inside"])
    };
  }

  function tipItem(id, name, details) {
    return { key: id, id: id, name: name, englishName: name, count: 1, maxStack: 1, details: details, enchanted: false, plain: true };
  }

  /**
   * Where things go in the 54-slot preview: what chest 1 already holds stays in its slots, the planned shulkers take
   * the free slots after them (those past the chest's free room are marked: they go to the next chest).
   */
  function layoutPlan(units) {
    var chests = deliveryChests();
    var c0 = chests.length && chests[0].n === 0 && Array.isArray(chests[0].items) ? chests[0] : null;
    var existing = {};
    var size = c0 ? c0.size : MAX_SHULKERS;
    if (c0) c0.items.forEach(function (it) { if (it && typeof it.slot === "number") existing[it.slot] = it; });
    var slotUnit = [];
    var unitSlot = [];
    var overflow = {};
    var next = 0;
    // Free slots of the chest first, then the rest of the grid for what doesn't fit (the next chest).
    var order = [];
    var i;
    for (i = 0; i < MAX_SHULKERS; i++) if (i < size && !existing[i]) order.push(i);
    for (i = 0; i < MAX_SHULKERS; i++) if (i >= size) order.push(i);
    var freeInChest = order.filter(function (k) { return k < size; }).length;
    for (i = 0; i < MAX_SHULKERS; i++) slotUnit.push(-1);
    units.forEach(function (u, ui) {
      if (next >= order.length) {
        unitSlot.push(-1);
        return;
      }
      var slot = order[next++];
      slotUnit[slot] = ui;
      unitSlot.push(slot);
      if (c0 && (slot >= size || ui >= freeInChest)) overflow[slot] = true;
    });
    return { existing: existing, slotUnit: slotUnit, unitSlot: unitSlot, overflow: overflow, size: c0 ? size : 0 };
  }

  function existingTip(it) {
    var id = str(it.id);
    var name = str(it.name) || nameForId(id);
    var count = Math.max(1, Math.floor(num(it.count, 1)));
    var details = ["Already in the delivery chest", grouped(count) + " \u00d7 " + name];
    if (it.shulker === true) {
      if (Array.isArray(it.contents)) {
        if (!it.contents.length) details.push("Empty");
        it.contents.slice(0, 12).forEach(function (c) {
          if (c && typeof c.id === "string") details.push(grouped(Math.floor(num(c.count, 0))) + " \u00d7 " + nameForId(c.id));
        });
        if (it.contents.length > 12) details.push("and " + (it.contents.length - 12) + " more");
      } else {
        details.push("Contents not reported");
      }
    }
    var tip = tipItem(id, name, details);
    tip.damage = Math.max(0, Math.floor(num(it.damage, 0)));
    tip.maxDamage = Math.max(0, Math.floor(num(it.maxDamage, 0)));
    return tip;
  }

  function renderPlan() {
    buildPools();
    var units = plan.units;
    var shown = shownUnit();
    var lay = layoutPlan(units);
    plan.slotUnit = lay.slotUnit;
    plan.unitSlot = lay.unitSlot;
    for (var i = 0; i < MAX_SHULKERS; i++) {
      var p = plan.chest[i];
      var ex = lay.existing[i];
      var ui = lay.slotUnit[i];
      var u = ui >= 0 ? units[ui] : null;
      p.slot.classList.toggle("existing", !!ex);
      p.slot.classList.toggle("overflow", !!lay.overflow[i]);
      p.slot.classList.toggle("outside", lay.size > 0 && i >= lay.size && !u);
      if (ex) {
        var contents = Array.isArray(ex.contents) ? ex.contents.map(function (c, k) { return { slot: k, key: str(c.id), count: num(c.count, 1) }; }) : null;
        var cnt = Math.floor(num(ex.count, 1));
        fillSlot(p, str(ex.id), existingTip(ex), cnt > 1 ? String(cnt) : "", ex.shulker === true ? contents : null);
        p.fill.style.transform = "scaleX(0)";
        p.slot.classList.remove("full", "selected", "bar-on");
        continue;
      }
      if (!u) {
        fillSlot(p, null, null, "");
        p.fill.style.transform = "scaleX(0)";
        p.slot.classList.remove("full", "selected", "bar-on");
        continue;
      }
      var info = unitInfo(u, ui, units.length);
      var extra = lay.overflow[i] ? ["Doesn't fit in this chest: goes to the next one"] : [];
      fillSlot(p, info.id, tipItem(info.id, info.title, info.details.concat([info.used + "/" + SHULKER_SLOTS + " slots"], extra)), "", info.contents);
      p.fill.style.transform = "scaleX(" + info.used / SHULKER_SLOTS + ")";
      p.slot.classList.add("bar-on");
      p.slot.classList.toggle("full", info.used >= SHULKER_SLOTS);
      p.slot.classList.toggle("selected", ui === shown);
    }
    renderDeliveryFit(units.length, lay);

    var su = shown >= 0 ? units[shown] : null;
    var sinfo = su ? unitInfo(su, shown, units.length) : null;
    var bySlot = {};
    if (sinfo) sinfo.contents.forEach(function (c) { bySlot[c.slot] = c; });
    for (var j = 0; j < SHULKER_SLOTS; j++) {
      var c = bySlot[j];
      if (!c) {
        fillSlot(plan.bin[j], null, null, "");
        continue;
      }
      var base = itemByKey(c.key) ||
        { key: c.key, id: c.key.split("#")[0], name: kbItemLabel(c.key), englishName: kbItemLabel(c.key), maxStack: 64, details: [], enchanted: false };
      var shownItem = Object.assign({}, base, { count: c.count });
      if (su.kind === "bin") shownItem.details = base.details.concat(["Right-click to remove this stack"]);
      fillSlot(plan.bin[j], base.id, shownItem, c.count === 1 ? "" : String(c.count));
    }
    var kind = su ? (su.kind === "bin" ? "" : su.kind === "empty" ? "empty · " : su.kind === "kit" ? "kit · " : "packed · ") : "";
    el.binTitle.textContent = sinfo
      ? "Shulker " + (shown + 1) + " of " + units.length + " · " + kind + sinfo.used + "/" + SHULKER_SLOTS + " slots"
      : "Shulker Box · nothing planned yet";
    el.binGrid.parentNode.classList.toggle("full", !!sinfo && su.kind === "bin" && sinfo.used >= SHULKER_SLOTS);
    el.cartCount.textContent = units.length + " / " + MAX_SHULKERS + " shulkers";
    el.cartCount.classList.toggle("warn", units.length >= MAX_SHULKERS);
    refreshTooltip();
  }

  /**
   * "Delivery chest: N of 54 slots used, M free; this order needs K (as of X ago)", totals over several chests, and a
   * warning when the order won't fit (what the bot will do then, from the heartbeat's deliveryStorage).
   */
  function renderDeliveryFit(need, lay) {
    var chests = deliveryChests();
    var text = "";
    var warn = "";
    if (chests.length) {
      var known = chests.filter(function (c) { return Array.isArray(c.items); });
      var unknown = chests.length - known.length;
      var used = 0;
      var total = 0;
      known.forEach(function (c) {
        used += c.items.length;
        total += c.size;
      });
      var free = total - used;
      var c0 = chests[0];
      var asOf = typeof dlv.data.readAt === "number" && dlv.data.readAt > 0 ? " (as of " + ago(dlv.data.readAt) + ")" : "";
      var needText = need ? "; this order needs " + need : "";
      if (chests.length === 1) {
        text = Array.isArray(c0.items)
          ? "Delivery chest: " + c0.items.length + " of " + c0.size + " slots used, " + (c0.size - c0.items.length) + " free" + needText + asOf + "."
          : "Delivery chest: not seen yet" + needText + ".";
      } else {
        var first = Array.isArray(c0.items)
          ? "the first (filled first) has " + (c0.size - c0.items.length) + " of " + c0.size + " free"
          : "the first is not seen yet";
        text = chests.length + " delivery chests: " + (known.length ? used + " of " + total + " slots used, " + free + " free" : "none seen yet") +
          (unknown && known.length ? " (" + unknown + " not seen yet)" : "") + "; " + first + needText + asOf + ".";
      }
      if (need > free && !unknown) {
        var s = currentStash();
        var ds = s && s.deliveryStorage && typeof s.deliveryStorage === "object" ? s.deliveryStorage : null;
        warn = "This order needs " + need + " slots but only " + free + " are free: " + (ds && ds.autoExpand === true
          ? "the bot will put up more chests (if it can)."
          : "the order will pause until a chest is emptied.");
      } else if (lay && Object.keys(lay.overflow).length) {
        warn = "Not all of it fits in the first chest: the rest goes into the next one.";
      }
    }
    if (el.deliveryFit.textContent !== text) el.deliveryFit.textContent = text;
    el.deliveryFit.hidden = !text;
    if (el.deliveryWarn.textContent !== warn) el.deliveryWarn.textContent = warn;
    el.deliveryWarn.hidden = !warn;
  }

  function flash(node) {
    node.classList.remove("flash");
    void node.offsetWidth; // restart the animation
    node.classList.add("flash");
  }

  /** "Shulker 2 full" when an addition filled another planned shulker. */
  function noticeFull() {
    var full = [];
    plan.units.forEach(function (u, i) { if (u.kind === "bin" && u.lots.length >= SHULKER_SLOTS) full.push(i); });
    plan.toasted = false;
    if (plan.watchFull && plan.fullCount >= 0 && full.length > plan.fullCount) {
      var last = full[full.length - 1];
      toast("Shulker " + (last + 1) + " full (27/27)");
      plan.toasted = true;
      var lastSlot = plan.unitSlot[last];
      if (lastSlot != null && lastSlot >= 0 && plan.chest[lastSlot]) flash(plan.chest[lastSlot].slot);
      if (shownUnit() === last) flash(el.binGrid.parentNode);
    }
    plan.fullCount = full.length;
    plan.watchFull = false;
  }

  // ---------- orders: cart pane ----------

  function renderCart() {
    var frag = document.createDocumentFragment();
    var packed = 0;
    var empties = 0;
    var kitCount = 0;
    var totalItems = 0;
    kbWantCartNames();
    if (!cart.lines.length) {
      var empty = document.createElement("div");
      empty.className = "note";
      empty.textContent = "The cart is empty. Drag items here, double-click them, or shift-click items in the other tabs.";
      frag.appendChild(empty);
    }
    cart.lines.forEach(function (l, i) {
      var item = lineItem(l.type, l.key);
      var row = document.createElement("div");
      row.className = "cart-line" + (i === cart.badLine ? " bad" : "");
      var cartIcon = item ? iconWithContents(item.id, l.type === "shulker" || l.type === "kit" ? item.contents : null, 32) : iconImg("minecraft:barrier", 32);
      if (item && durabilityLeft(item) >= 0) {
        if (!cartIcon.classList.contains("icon-wrap")) {
          var wrapIcon = document.createElement("span");
          wrapIcon.className = "icon-wrap";
          wrapIcon.appendChild(cartIcon);
          cartIcon = wrapIcon;
        }
        setDurability(cartIcon, item);
      }
      row.appendChild(cartIcon);
      var name = document.createElement("span");
      name.className = "cart-name";
      name.textContent = item ? displayName(l.type, item) : l.type === "kit" ? "Kit: " + l.key + " (not loaded or deleted)" : l.key + " (no longer in the stash)";
      if (l.type === "kit" && streamerOn()) hide(name);
      row.appendChild(name);
      var input = document.createElement("input");
      input.type = "number";
      input.className = "mc-input qty-input";
      input.min = "1";
      var max = Math.max(1, availableTotal(l.type, l.key) - (cartCount(l.type, l.key) - l.count));
      input.max = String(max);
      input.value = String(l.count);
      input.setAttribute("aria-label", "Count");
      input.addEventListener("change", function () {
        var maxNow = Math.max(1, availableTotal(l.type, l.key) - (cartCount(l.type, l.key) - l.count));
        var n = clampInt(input.value, 1, maxNow);
        var old = l.count;
        l.count = n;
        var need = shulkersNeeded(cart.lines);
        if (need > MAX_SHULKERS && n > old) {
          l.count = old;
          input.value = String(old);
          refuse(capMessage(need));
          return;
        }
        cart.badLine = -1;
        plan.watchFull = n > old;
        saveCart();
        renderCart();
        updatePickerCounts();
      });
      row.appendChild(input);
      var rp = repairLineControl(l, item);
      if (rp) row.appendChild(rp);
      var rm = document.createElement("button");
      rm.type = "button";
      rm.className = "mc-button small danger";
      rm.textContent = "×";
      rm.setAttribute("aria-label", "Remove");
      rm.addEventListener("click", function () {
        cart.lines.splice(i, 1);
        cart.badLine = -1;
        saveCart();
        renderCart();
        updatePickerCounts();
      });
      row.appendChild(rm);
      frag.appendChild(row);
      if (l.type === "shulker") packed += l.count;
      else if (l.type === "kit") kitCount += l.count;
      else if (isEmptyShulkerLine(l)) empties += l.count;
      else totalItems += l.count;
    });
    el.cartLines.replaceChildren(frag);

    var units = planCart(cart.lines);
    var need = shulkersNeeded(cart.lines);
    plan.units = units || [];
    renderPlan();
    renderNames();
    noticeFull();
    if (!units) {
      // A stored cart (or a new index with a smaller maxStack) can be over the limit.
      el.cartCount.textContent = need + " / " + MAX_SHULKERS + " shulkers";
      el.cartCount.classList.add("warn");
    }
    var parts = [];
    if (totalItems) parts.push(grouped(totalItems) + (totalItems === 1 ? " item" : " items"));
    if (packed) parts.push(plural(String(packed), "packed shulker"));
    if (empties) parts.push(plural(String(empties), "empty shulker"));
    if (kitCount) parts.push(plural(String(kitCount), "kit"));
    el.cartTotals.textContent = cart.lines.length
      ? parts.join(" + ") + " · " + plural(String(need), "shulker") + " to deliver" + (units ? "" : ". Too many: one order can fill at most " + MAX_SHULKERS + " (one double chest).")
      : "";
    el.cartTotals.classList.toggle("warn", !units);
    el.cartPlace.disabled = !cart.lines.length || !state.items.length || !units;
    el.cartClear.disabled = !cart.lines.length;
    renderRepair();
    var s = currentStash();
    el.cartOffline.hidden = !(s && !s.online);
  }

  // ---------- orders: repair with XP bottles ----------
  // Mending items can be repaired by the bot with Bottles o' Enchanting from the stash before delivery. The estimate
  // is a guess (about 14 durability per bottle); bottles are not reserved, and the bot repairs as far as they go.

  /** Bottles to fully repair one stash item (0 when it can't be repaired). */
  function itemBottles(item) {
    if (!item || repairReason(item)) return 0;
    return item.repairBottles > 0 ? item.repairBottles : bottlesFor(item.damage);
  }

  /** Whether the cart wants this line repaired: an item line with the cart-wide switch or its own toggle. */
  function lineWantsRepair(l) {
    if (l.type !== "item") return false;
    if (l.repair === true) return true;
    var it = itemByKey(l.key);
    return cart.repairAll && !!it && it.damage > 0;
  }

  /** The cart's estimate: bottles needed, bottles in the stash after open orders, and the wanted lines. */
  function cartRepairEstimate() {
    var needed = 0;
    var wanted = 0;
    var fixable = 0;
    cart.lines.forEach(function (l) {
      if (!lineWantsRepair(l)) return;
      wanted++;
      var b = l.count * itemBottles(itemByKey(l.key));
      if (b > 0) fixable++;
      needed += b;
    });
    var a = orders.available;
    var have = a && typeof a.bottles === "number" ? a.bottles : availableTotal("item", BOTTLE_KEY);
    return { needed: needed, available: have, wanted: wanted, fixable: fixable };
  }

  /** The per-line control: a Repair checkbox on a repairable item line, a note on a damaged one that can't be, else null. */
  function repairLineControl(l, item) {
    if (l.type !== "item" || !item || isEmptyShulkerLine(l)) return null;
    var reason = repairReason(item);
    if (reason) {
      if (!(item.damage > 0)) return null; // nothing to repair, nothing to say
      var no = document.createElement("span");
      no.className = "note repair-no";
      no.textContent = "can't repair (" + reason + ")";
      return no;
    }
    var label = document.createElement("label");
    label.className = "unset user-check repair-toggle";
    var box = document.createElement("input");
    box.type = "checkbox";
    box.checked = cart.repairAll || l.repair === true;
    box.disabled = cart.repairAll;
    box.setAttribute("aria-label", "Repair this item");
    box.addEventListener("change", function () {
      if (box.checked) l.repair = true;
      else delete l.repair;
      saveCart();
      renderRepair();
    });
    label.appendChild(box);
    label.appendChild(document.createTextNode(" Repair"));
    if (cart.repairAll) label.title = "Repair all items is on";
    return label;
  }

  function renderRepair() {
    var est = cartRepairEstimate();
    if (el.repairAll.checked !== cart.repairAll) el.repairAll.checked = cart.repairAll;
    var info = "";
    var warn = "";
    if (est.wanted > 0) {
      info = est.needed > 0
        ? "About " + plural(grouped(est.needed), "bottle") + " needed, " + grouped(est.available) + " in the stash."
        : "None of the chosen items can be repaired (they need Mending and some damage).";
      if (est.needed > est.available) {
        warn = "Not enough Bottles o' Enchanting in the stash: the bot repairs as far as the bottles go and delivers the rest as they are.";
      }
    }
    var s = currentStash();
    if (est.wanted > 0 && est.fixable > 0 && s && s.repair !== true) {
      warn = (warn ? warn + " " : "") + "This bot can't repair items; they'll arrive as they are.";
    }
    if (el.repairInfo.textContent !== info) el.repairInfo.textContent = info;
    el.repairInfo.hidden = !info;
    if (el.repairWarn.textContent !== warn) el.repairWarn.textContent = warn;
    el.repairWarn.hidden = !warn;
  }

  el.repairAll.addEventListener("change", function () {
    cart.repairAll = el.repairAll.checked;
    saveCart();
    if (!el.cartLines.contains(document.activeElement)) renderCart();
    else renderRepair();
  });

  el.cartClear.addEventListener("click", function () {
    if (!cart.lines.length || !window.confirm("Remove everything from the cart?")) return;
    cart.lines = [];
    cart.badLine = -1;
    plan.selected = null;
    clearStatus();
    saveCart();
    renderCart();
    updatePickerCounts();
  });

  /** The text of a refused order. In streamer mode the kit shortfall is built from the structured list (item names only), other texts are generic. */
  function orderErrorText(e, body) {
    var fallback = "Could not place the order" + (e && e.status ? " (HTTP " + e.status + ")" : "") + ".";
    if (!(body && body.error)) return fallback;
    if (!streamerOn()) return body.error;
    if (Array.isArray(body.short) && body.short.length) {
      return "Not enough items to make those kits. Short: " + kitItemsText(body.short) + ".";
    }
    return "The order was refused (details are hidden in streamer mode).";
  }

  el.cartPlace.addEventListener("click", function () {
    var s = currentStash();
    if (!s || !cart.lines.length) return;
    el.cartPlace.disabled = true;
    el.cartStatus.textContent = "Placing the order...";
    el.cartStatus.classList.remove("warn");
    var sent = cart.lines.slice();
    api("/api/v1/orders", {
      method: "POST",
      body: { server: s.server, stash: s.stash, names: namesBody(), repairAll: cart.repairAll, lines: sent }
    }).then(function () {
      cart.lines = [];
      cart.repairAll = false;
      el.repairAll.checked = false;
      cart.badLine = -1;
      plan.selected = null;
      saveCart();
      resetNames();
      el.cartStatus.textContent = s.online ? "Order placed." : "Order placed. It will start when the bot reconnects.";
      toast("Order placed");
      renderCart();
      updatePickerCounts();
      scheduleOrdersPoll(0);
    }, function (e) {
      var body = e && e.body;
      cart.badLine = body && typeof body.line === "number" ? body.line : -1;
      el.cartStatus.textContent = craftConflictText(e) || orderErrorText(e, body);
      if (streamerOn()) el.cartStatus.setAttribute("data-sensitive", "");
      else el.cartStatus.removeAttribute("data-sensitive");
      el.cartStatus.classList.add("warn");
      renderCart();
      scheduleOrdersPoll(0);
    });
  });

  // ---------- orders: drag and drop, double-click ----------
  // Pointer events instead of HTML5 drag and drop: the slots and their tooltips stay as they are, and the ghost
  // is moved with a transform once per animation frame.

  var drag = { pe: null, active: false, x0: 0, y0: 0, x: 0, y: 0, shift: false, raf: 0, over: false, suppressClick: false };
  var DRAG_THRESHOLD = 5;

  function pickEntryFrom(target) {
    var slot = target && target.closest ? target.closest(".picker-grid .slot") : null;
    var pe = slot ? slotEntry.get(slot) : null;
    return pe && pe.pick ? pe : null;
  }

  el.pickerBody.addEventListener("pointerdown", function (e) {
    if (e.button !== 0 || !e.isPrimary || e.pointerType === "touch") return;
    var pe = pickEntryFrom(e.target);
    if (!pe) return;
    drag.pe = pe;
    drag.active = false;
    drag.x0 = e.clientX;
    drag.y0 = e.clientY;
  });

  function startDrag() {
    drag.active = true;
    hideTooltip();
    var img = document.createElement("img");
    img.alt = "";
    img.width = 32;
    img.height = 32;
    img.src = drag.pe.img.getAttribute("src") || PLACEHOLDER;
    var label = document.createElement("span");
    label.className = "ghost-label";
    el.dragGhost.replaceChildren(img, label);
    el.dragGhost.hidden = false;
    document.body.classList.add("dragging");
    el.cartDrop.classList.add("drop-zone");
  }

  function applyDrag() {
    drag.raf = 0;
    if (!drag.active) return;
    el.dragGhost.style.transform = "translate(" + Math.round(drag.x - 16) + "px, " + Math.round(drag.y - 16) + "px)";
    var t = document.elementFromPoint(drag.x, drag.y);
    var over = !!t && el.cartDrop.contains(t);
    if (over !== drag.over) {
      drag.over = over;
      el.cartDrop.classList.toggle("drop-over", over);
    }
    var label = el.dragGhost.lastChild;
    var text = drag.pe.pick.type === "shulker" ? "+1" : drag.shift ? "+1 shulker" : "+1 stack";
    if (label && label.textContent !== text) label.textContent = text;
  }

  function endDrag() {
    drag.pe = null;
    drag.active = false;
    drag.over = false;
    el.dragGhost.hidden = true;
    el.dragGhost.replaceChildren();
    document.body.classList.remove("dragging");
    el.cartDrop.classList.remove("drop-zone", "drop-over");
  }

  document.addEventListener("pointermove", function (e) {
    if (!drag.pe) return;
    if (!drag.active) {
      if (Math.abs(e.clientX - drag.x0) + Math.abs(e.clientY - drag.y0) < DRAG_THRESHOLD) return;
      startDrag();
    }
    drag.x = e.clientX;
    drag.y = e.clientY;
    drag.shift = e.shiftKey;
    if (!drag.raf) drag.raf = window.requestAnimationFrame(applyDrag);
  }, { passive: true });

  document.addEventListener("pointerup", function (e) {
    if (!drag.pe) return;
    var pe = drag.pe;
    var wasActive = drag.active;
    endDrag();
    if (!wasActive) return;
    // The click that follows this pointerup must not also select the item.
    drag.suppressClick = true;
    window.setTimeout(function () { drag.suppressClick = false; }, 0);
    var t = document.elementFromPoint(e.clientX, e.clientY);
    if (t && el.cartDrop.contains(t)) addStacks(pe.pick.type, pe.pick.item, e.shiftKey);
  });

  document.addEventListener("pointercancel", function () {
    if (drag.pe) endDrag();
  });
  window.addEventListener("blur", function () {
    if (drag.pe) endDrag();
  });

  el.pickerBody.addEventListener("dblclick", function (e) {
    var pe = pickEntryFrom(e.target);
    if (!pe) return;
    e.preventDefault();
    var sel = window.getSelection && window.getSelection();
    if (sel && sel.removeAllRanges) sel.removeAllRanges();
    addStacks(pe.pick.type, pe.pick.item, e.shiftKey);
  });

  // ---------- orders: picker pane ----------

  function crumb(text, onClick) {
    if (!onClick) {
      var span = document.createElement("span");
      span.className = "crumb current";
      span.textContent = text;
      return span;
    }
    var b = document.createElement("button");
    b.type = "button";
    b.className = "crumb";
    b.textContent = text;
    b.addEventListener("click", onClick);
    return b;
  }

  function pickerTypes() {
    var out = visibleTabs().filter(function (t) { return isCreativeTab(t.id); });
    if (state.shulkers.length) out.push({ id: "packed", name: "Shulker boxes (packed)", icon: "minecraft:shulker_box" });
    return out;
  }

  function renderCrumbs() {
    var crumbs = document.createDocumentFragment();
    var toTypes = function () { picker.level = "types"; picker.type = null; picker.entry = null; renderPicker(); };
    if (picker.level === "types") {
      crumbs.appendChild(crumb("Item type", null));
    } else {
      var type = picker.type === "packed" ? { name: "Shulker boxes (packed)" } : tabInfo(picker.type);
      crumbs.appendChild(crumb("Item type", toTypes));
      crumbs.appendChild(document.createTextNode(" › "));
      if (!picker.entry) {
        crumbs.appendChild(crumb(type.name, null));
      } else {
        crumbs.appendChild(crumb(type.name, function () { selectPick(null); }));
        crumbs.appendChild(document.createTextNode(" › "));
        crumbs.appendChild(crumb(displayName(picker.entry.type, picker.entry.item), null));
      }
    }
    el.crumbs.replaceChildren(crumbs);
  }

  function renderPicker() {
    var body = document.createDocumentFragment();
    picker.slots = [];
    picker.qtyHost = null;
    if (!state.items.length) {
      el.crumbs.replaceChildren();
      var none = document.createElement("div");
      none.className = "note";
      none.textContent = "No stash index yet.";
      el.pickerBody.replaceChildren(none);
      return;
    }
    if (picker.level === "types") {
      var list = document.createElement("div");
      list.className = "type-buttons";
      pickerTypes().forEach(function (t) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = "mc-button type-button";
        b.appendChild(iconImg(t.icon, 32));
        var label = document.createElement("span");
        label.textContent = t.name;
        b.appendChild(label);
        b.addEventListener("click", function () {
          picker.level = "items";
          picker.type = t.id;
          picker.entry = null;
          picker.search = "";
          renderPicker();
        });
        list.appendChild(b);
      });
      body.appendChild(list);
    } else {
      body.appendChild(pickerItems());
      var host = document.createElement("div");
      host.className = "qty-host";
      picker.qtyHost = host;
      body.appendChild(host);
    }
    renderCrumbs();
    el.pickerBody.replaceChildren(body);
    renderQty();
    if (state.hovered && !state.hovered.slot.isConnected) hideTooltip();
    var search = el.pickerBody.querySelector(".picker-search");
    if (search && picker.focusSearch) {
      search.focus();
      search.setSelectionRange(search.value.length, search.value.length);
    }
    picker.focusSearch = false;
  }

  /** Selects an item in the grid (or nothing) and shows its quantity panel under the grid; the grid itself stays. */
  function selectPick(pick) {
    picker.entry = pick;
    picker.slots.forEach(function (pe) {
      pe.slot.classList.toggle("picked", !!pick && pe.pick.type === pick.type && pe.pick.item.key === pick.item.key);
    });
    renderCrumbs();
    renderQty();
  }

  function renderQty() {
    if (!picker.qtyHost) return;
    picker.qtyHost.replaceChildren(picker.entry ? pickerQuantity() : document.createDocumentFragment());
  }

  /** Writes what is left to order into the picker's slots in place, and refreshes the quantity panel. */
  function updatePickerCounts() {
    if (!picker.slots) return;
    picker.slots.forEach(function (pe) {
      var left = availableLeft(pe.pick.type, pe.pick.item.key);
      if (pe.item.count !== left) {
        pe.item.count = left;
        setCount(pe);
      }
      pe.slot.classList.toggle("unavailable", left < 1);
    });
    if (picker.qtyHost && !picker.qtyHost.contains(document.activeElement)) renderQty();
  }

  /** The items of the chosen type as slots, with a search box. Counts show what is orderable. */
  function pickerItems() {
    var wrap = document.createElement("div");
    wrap.className = "picker-items";
    var search = document.createElement("input");
    search.type = "search";
    search.className = "mc-input picker-search";
    search.placeholder = "Filter";
    search.value = picker.search;
    var searchTimer = 0;
    search.addEventListener("input", function () {
      picker.search = search.value;
      window.clearTimeout(searchTimer);
      searchTimer = window.setTimeout(function () {
        picker.focusSearch = true;
        renderPicker();
      }, SEARCH_DEBOUNCE_MS);
    });
    wrap.appendChild(search);

    var packed = picker.type === "packed";
    var source = packed ? state.shulkers : tabEntries(picker.type);
    var query = parseQuery(picker.search);
    var list = source.filter(function (e) { return matches(e, query); }).slice();
    list.sort(packed ? sorters.count : sorters.creative);
    var grid = document.createElement("div");
    grid.className = "item-grid picker-grid";
    list.forEach(function (e) {
      var type = packed ? "shulker" : "item";
      var left = availableLeft(type, e.item.key);
      var label = displayName(type, e.item);
      // Keep the italic "custom name" style only for items that really have a custom name.
      var shown = Object.assign({}, e.item, {
        count: left, name: label, englishName: e.item.name === e.item.englishName ? label : e.item.englishName
      });
      var pe = createEntry(shown, state.mc);
      pe.pick = { type: type, item: e.item };
      if (left < 1) pe.slot.classList.add("unavailable");
      if (picker.entry && picker.entry.type === type && picker.entry.item.key === e.item.key) pe.slot.classList.add("picked");
      pe.onActivate = function () {
        if (drag.suppressClick) return;
        selectPick({ type: type, item: e.item });
      };
      picker.slots.push(pe);
      grid.appendChild(pe.slot);
    });
    if (!list.length) {
      var none = document.createElement("div");
      none.className = "note";
      none.textContent = "Nothing matches.";
      wrap.appendChild(none);
    }
    wrap.appendChild(grid);
    return wrap;
  }

  /** "= 3 stacks + 12 · ≈ 0.1 shulker" */
  function packReadout(n, maxStack, type) {
    if (type === "shulker") return n ? "= " + plural(String(n), "packed shulker") : "";
    var stacks = Math.floor(n / maxStack);
    var rest = n % maxStack;
    var parts = [];
    if (stacks) parts.push(plural(String(stacks), "stack"));
    if (rest || !stacks) parts.push(String(rest));
    return "= " + parts.join(" + ") + " · ≈ " + plural(approx(n / (maxStack * 27)), "shulker");
  }

  /** The quantity panel for the selected item, under the grid. */
  function pickerQuantity() {
    var type = picker.entry.type;
    var item = lineItem(type, picker.entry.item.key) || picker.entry.item;
    var wrap = document.createElement("div");
    wrap.className = "qty-panel";
    var head = document.createElement("div");
    head.className = "qty-head";
    head.appendChild(iconWithContents(item.id, type === "shulker" ? item.contents : null, 32));
    var name = document.createElement("div");
    name.className = "qty-name";
    name.textContent = displayName(type, item);
    head.appendChild(name);
    wrap.appendChild(head);

    var left = availableLeft(type, item.key);
    var info = document.createElement("div");
    info.className = "note";
    var inCart = cartCount(type, item.key);
    info.textContent = grouped(left) + " available" + (inCart ? " (" + grouped(inCart) + " already in the cart)" : "");
    wrap.appendChild(info);

    var row = document.createElement("div");
    row.className = "qty-row";
    var input = document.createElement("input");
    input.type = "number";
    input.className = "mc-input qty-input";
    input.min = "0";
    input.max = String(left);
    input.value = String(Math.min(left, type === "shulker" ? 1 : item.maxStack));
    row.appendChild(input);
    var readout = document.createElement("div");
    readout.className = "qty-readout";
    var add = document.createElement("button");
    var update = function () {
      var n = clampInt(input.value, 0, left);
      readout.textContent = packReadout(n, item.maxStack, type);
      add.disabled = n < 1;
    };
    var quick = function (label, fn) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "mc-button small";
      b.textContent = label;
      b.addEventListener("click", function () {
        input.value = String(clampInt(fn(clampInt(input.value, 0, left)), 0, left));
        update();
      });
      row.appendChild(b);
    };
    quick("+1", function (n) { return n + 1; });
    if (type === "shulker") {
      quick("+5", function (n) { return n + 5; });
    } else {
      quick("+1 stack", function (n) { return n + item.maxStack; });
      quick("+1 shulker", function (n) { return n + item.maxStack * 27; });
    }
    quick("Max", function () { return left; });
    quick("Clear", function () { return 0; });
    wrap.appendChild(row);
    wrap.appendChild(readout);

    add.type = "button";
    add.className = "mc-button";
    add.textContent = "Add to cart";
    add.addEventListener("click", function () {
      var n = clampInt(input.value, 0, availableLeft(type, item.key));
      if (n < 1) return;
      if (addToCart(type, item.key, n)) {
        clearStatus();
        if (!plan.toasted) toast("Added " + grouped(n) + " × " + displayName(type, item) + " to the cart");
        renderQty();
      }
    });
    input.addEventListener("input", update);
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter") add.click();
    });
    wrap.appendChild(add);
    if (left < 1) {
      input.disabled = true;
      var none = document.createElement("div");
      none.className = "note warn";
      none.textContent = "None left: the rest is reserved by open orders or already in the cart.";
      wrap.appendChild(none);
    }
    update();
    return wrap;
  }

  // ---------- orders: list ----------

  var STATUS_LABEL = {
    pending: "Pending", accepted: "Accepted", in_progress: "In progress",
    delivered: "Delivered", failed: "Failed", cancelled: "Cancelled"
  };
  var OPEN_STATUS = { pending: true, accepted: true, in_progress: true };

  function scheduleOrdersPoll(delay) {
    window.clearTimeout(orders.timer);
    orders.timer = window.setTimeout(pollOrders, delay);
  }

  function pollOrders() {
    var s = currentStash();
    var next = state.tab === "orders" && !document.hidden ? 3000 : 15000;
    if (!s || orders.loading || auth.blocked) {
      scheduleOrdersPoll(next);
      return;
    }
    orders.loading = true;
    var token = orders.token;
    api("/api/v1/orders?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash))
      .then(function (d) {
        if (token !== orders.token) return;
        orders.list = d && Array.isArray(d.orders) ? d.orders : [];
        var available = d && d.available && typeof d.available === "object" ? d.available : null;
        var changed = JSON.stringify(available) !== JSON.stringify(orders.available);
        orders.available = available;
        renderOrderList();
        // Only redraw the cart and picker when availability changed, and never under the user's cursor/focus.
        if (state.tab === "orders" && changed) {
          if (!el.cartLines.contains(document.activeElement)) renderCart();
          updatePickerCounts();
        }
      }, function () {
        // Keep the last list; the header already shows "Server unreachable" when that is the problem.
      })
      .then(function () {
        orders.loading = false;
        scheduleOrdersPoll(state.tab === "orders" && !document.hidden ? 3000 : 15000);
      });
  }

  /** An item key's display name for the repair report. */
  function repairItemName(key) {
    var item = itemByKey(key);
    return item ? displayName("item", item) : str(key);
  }

  /** The order's repair estimate (made when it was placed) and, once the bot reports, what the repair did. */
  function appendRepairInfo(card, o) {
    var est = o.repairEstimate && typeof o.repairEstimate === "object" ? o.repairEstimate : null;
    if (est) {
      var text = "Repair" + (o.repairAll === true ? " (all items)" : "") + ": about " +
        plural(grouped(num(est.needed, 0)), "bottle") + " needed, " + grouped(num(est.available, 0)) + " in the stash when placed";
      card.appendChild(line("order-repair" + (est.short === true ? " order-repair-short" : ""), text +
        (est.short === true ? ". Short on bottles: the bot repairs as far as they go." : ".")));
    }
    var rr = FINISHED_STATUS[o.status] && o.result && typeof o.result === "object" && o.result.repair && typeof o.result.repair === "object"
      ? o.result.repair : null;
    if (!rr) return;
    var partial = Array.isArray(rr.partial) ? rr.partial : [];
    var skipped = Array.isArray(rr.skipped) ? rr.skipped : [];
    card.appendChild(line("order-repair", "Repaired " + plural(grouped(num(rr.repaired, 0)), "item") + ", " +
      plural(grouped(num(rr.bottlesUsed, 0)), "bottle") + " used."));
    if (!partial.length && !skipped.length) return;
    var d = document.createElement("details");
    d.className = "order-repair-details";
    var openKey = "repair:" + o.id;
    d.open = orders.expanded.has(openKey);
    d.addEventListener("toggle", function () {
      if (d.open) orders.expanded.add(openKey);
      else orders.expanded.delete(openKey);
    });
    var sum = document.createElement("summary");
    sum.textContent = plural(grouped(partial.length + skipped.length), "item") + " not fully repaired";
    d.appendChild(sum);
    partial.forEach(function (p) {
      if (!p || typeof p !== "object") return;
      var left = num(p.damage, 0);
      var max = num(p.maxDamage, 0);
      d.appendChild(line("order-line", repairItemName(p.key) + ": " + grouped(left) + (max > 0 ? " / " + grouped(max) : "") + " damage left"));
    });
    skipped.forEach(function (p) {
      if (!p || typeof p !== "object") return;
      d.appendChild(line("order-line", repairItemName(p.key) + ": not repaired (" + str(p.reason) + ")"));
    });
    card.appendChild(d);
  }

  function linesSummary(o) {
    return o.lines.map(function (l) {
      if (l.type === "kit") return "Kit: " + grouped(l.count) + " × " + kbOrderKitName(o, str(l.key));
      var item = lineItem(l.type, l.key);
      // A craft line: made by the bot from stash materials ("Craft: 8 Spruce Stairs").
      if (l.type === "craft") return "Craft: " + grouped(l.count) + " " + (item ? item.name : plPretty(str(l.key)));
      return grouped(l.count) + " × " + (item ? displayName(l.type, item) : l.key);
    });
  }

  var CRAFT_SHORT_WHY = {
    no_table: "no crafting table was set",
    table_missing: "there was no crafting table at the set spot",
    table_blocked: "the bot couldn't reach or open the table",
    ingredients_short: "the ingredients ran short",
    inventory_full: "the bot's inventory was full",
    recipe_mismatch: "the bot's recipes didn't match the server's",
    grid_stuck: "the crafting grid got stuck",
    timeout: "it took too long",
    interrupted: "a player got in the way",
    cap: "the bot reached its crafting limit",
    cancelled: "the order was cancelled"
  };

  /** "6 Spruce Log, 3 Stick" for a list of { id|key, count } (the order's craft lists). */
  function craftListText(list, idField, numField) {
    var arr = Array.isArray(list) ? list.filter(function (e) { return e && typeof e === "object" && num(e[numField], 0) > 0; }) : [];
    var shown = arr.slice(0, 8).map(function (e) {
      var item = itemByKey(str(e[idField]));
      return grouped(e[numField]) + " " + (item ? item.name : plPretty(str(e[idField])));
    });
    if (arr.length > shown.length) shown.push("and " + (arr.length - shown.length) + " more");
    return shown.join(", ");
  }

  /** What the bot is to craft for an order, and once it is done what it made, what fell short and what went back. */
  function appendCraftInfo(card, o) {
    var plan = o.craft && typeof o.craft === "object" ? o.craft : null;
    var res = FINISHED_STATUS[o.status] && o.result && typeof o.result === "object" && o.result.craft && typeof o.result.craft === "object"
      ? o.result.craft : null;
    if (res) {
      var made = Array.isArray(res.made) ? res.made.filter(function (m) { return m && typeof m === "object"; }) : [];
      var short = Array.isArray(res.short) ? res.short.filter(function (m) { return m && typeof m === "object"; }) : [];
      if (made.length) {
        card.appendChild(line("order-craft", "Crafted: " + made.map(function (m) {
          var item = itemByKey(str(m.key));
          var name = item ? item.name : plPretty(str(m.key));
          return num(m.made, 0) >= num(m.wanted, 0) ? grouped(num(m.made, 0)) + " " + name
            : grouped(num(m.made, 0)) + " of " + grouped(num(m.wanted, 0)) + " " + name;
        }).join(", ") + "."));
      }
      if (short.length) {
        card.appendChild(line("order-craft order-craft-short", "Not crafted: " + short.map(function (m) {
          var item = itemByKey(str(m.key));
          var why = str(m.reason);
          return grouped(num(m.count, 0)) + " " + (item ? item.name : plPretty(str(m.key))) + " (" +
            (CRAFT_SHORT_WHY[why] || (why && why !== "(hidden)" ? why : "reason hidden")) + ")";
        }).join(", ") + "."));
      }
      var used = craftListText(res.used, "id", "count");
      var back = craftListText(res.returned, "id", "count");
      if (used || back || num(res.fills, 0) > 0) {
        var d = document.createElement("details");
        d.className = "order-craft-details";
        var openKey = "craft:" + o.id;
        d.open = orders.expanded.has(openKey);
        d.addEventListener("toggle", function () {
          if (d.open) orders.expanded.add(openKey);
          else orders.expanded.delete(openKey);
        });
        var sum = document.createElement("summary");
        sum.textContent = "Crafting details";
        d.appendChild(sum);
        if (used) d.appendChild(line("order-line", "Used from the stash: " + used));
        if (back) d.appendChild(line("order-line", "Returned to the stash: " + back));
        if (num(res.fills, 0) > 0) d.appendChild(line("order-line", plural(grouped(num(res.fills, 0)), "craft") + " at the table"));
        card.appendChild(d);
      }
      return;
    }
    if (plan && OPEN_STATUS[o.status]) {
      var take = craftListText(plan.take, "id", "count");
      var fills = num(plan.fills, 0);
      card.appendChild(line("order-craft", "Crafts from the stash: " + (take ? "uses " + take : "nothing extra") +
        (fills > 0 ? " (about " + plural(grouped(fills), "craft") + " at the table)" : "") + "."));
    }
  }

  var FINISHED_STATUS = { delivered: true, failed: true, cancelled: true };

  var KIT_SHORT_WHY = {
    items_short: "the items ran short",
    no_shulker: "there was no empty shulker box left",
    inventory_full: "the bot's inventory was full",
    template_mismatch: "the kit didn't match what the bot knows (it was edited?)",
    interrupted: "a player got in the way",
    timeout: "it took too long",
    cancelled: "the order was cancelled",
    cap: "the bot reached its limit"
  };
  var KIT_NOTE_WORDS = {
    colour_substituted: "No empty shulker of the chosen colour was left, so another colour was used."
  };

  /** A line of text where some parts (kit names) are blurred in streamer mode. parts: strings, or { h: text } for a hidden one. */
  function kitLineNode(cls, parts) {
    var d = document.createElement("div");
    d.className = cls;
    parts.forEach(function (p) {
      if (typeof p === "string") d.appendChild(document.createTextNode(p));
      else d.appendChild(hiddenSpan(p.h));
    });
    return d;
  }

  function kitItemsText(list) {
    var arr = Array.isArray(list) ? list.filter(function (e) { return e && typeof e === "object" && num(e.count, 0) > 0; }) : [];
    var shown = arr.slice(0, 6).map(function (e) { return kbItemLabel(str(e.key) || str(e.match)) + " ×" + grouped(num(e.count, 0)); });
    if (arr.length > shown.length) shown.push("and " + (arr.length - shown.length) + " more");
    return shown.join(", ");
  }

  /** The kit part of an order: what was planned, then what the bot made, what fell short and the notes. */
  function appendKitInfo(card, o) {
    var plan = o.kit && typeof o.kit === "object" ? o.kit : null;
    var res = FINISHED_STATUS[o.status] && o.result && typeof o.result === "object" && o.result.kit && typeof o.result.kit === "object"
      ? o.result.kit : null;
    if (res) {
      var made = Array.isArray(res.made) ? res.made.filter(function (m) { return m && typeof m === "object"; }) : [];
      var short = Array.isArray(res.short) ? res.short.filter(function (m) { return m && typeof m === "object"; }) : [];
      made.forEach(function (m) {
        var del = num(m.delivered, 0);
        var wanted = num(m.wanted, 0);
        var how = [];
        if (num(m.ready, 0) > 0) how.push(num(m.ready, 0) + " already made");
        if (num(m.built, 0) > 0) how.push(num(m.built, 0) + " built");
        card.appendChild(kitLineNode("order-kit", ["Kit: ", { h: kbOrderKitName(o, str(m.template), str(m.name)) },
          " – " + grouped(del) + (del >= wanted ? "" : " of " + grouped(wanted)) + " delivered" + (how.length ? " (" + how.join(", ") + ")" : "") + "."]));
      });
      short.forEach(function (m) {
        var why = str(m.reason);
        var stored = str(m.stored);
        var items = kitItemsText(m.items);
        var tail = (items ? " Short: " + items + "." : "") +
          (stored === "incomplete" ? " Kept as an incomplete kit in its chest, to be topped up; it was not delivered."
            : stored === "returned" ? " Not delivered; the items were put back." : " Not delivered.");
        card.appendChild(kitLineNode("order-kit order-kit-short", ["Not made: " + grouped(num(m.kits, 0)) + " × ",
          { h: kbOrderKitName(o, str(m.template)) }, " (" + (KIT_SHORT_WHY[why] || (why && why !== "(hidden)" ? why : "reason hidden")) + ")." + tail]));
      });
      (Array.isArray(res.notes) ? res.notes : []).forEach(function (n) {
        if (typeof n !== "string") return;
        card.appendChild(line("order-kit", KIT_NOTE_WORDS[n] || n.replace(/_/g, " ") + "."));
      });
      return;
    }
    if (plan && OPEN_STATUS[o.status]) {
      var lines = Array.isArray(plan.lines) ? plan.lines : [];
      lines.forEach(function (l) {
        if (!l || typeof l !== "object" || !l.template) return;
        var ready = num(l.ready, 0);
        var build = num(l.build, 0);
        card.appendChild(kitLineNode("order-kit", ["Kit: ", { h: str(l.template.name) || str(l.template.id) },
          " – " + grouped(num(l.count, 0)) + (ready > 0 ? ", " + ready + " already made" : "") + (build > 0 ? ", " + build + " to build" : "") + "."]));
      });
    }
  }

  function finishedCount() {
    return orders.list.filter(function (o) { return o && FINISHED_STATUS[o.status]; }).length;
  }

  el.ordersClear.addEventListener("click", function () {
    var s = currentStash();
    var n = finishedCount();
    if (!s || !n) return;
    if (!window.confirm("Remove " + n + " finished order" + (n === 1 ? "" : "s") + " from the list? Open orders are kept.")) return;
    el.ordersClear.disabled = true;
    api("/api/v1/orders/clear", { method: "POST", body: { server: s.server, stash: s.stash } }).then(function (r) {
      var removed = r && typeof r.removed === "number" ? r.removed : 0;
      toast("Cleared " + removed + " order" + (removed === 1 ? "" : "s"));
      scheduleOrdersPoll(0);
    }, function (e) {
      toast("Could not clear the history" + (e && e.body && e.body.error ? ": " + e.body.error : e && e.status ? " (HTTP " + e.status + ")" : ""));
      renderOrderList();
    });
  });

  var FORCE_CANCEL_AFTER_MS = 60000;

  /** A requested cancel the bot hasn't confirmed within a minute (or the bot is offline). */
  function canForceCancel(o) {
    if (!o.cancelRequested || (o.status !== "accepted" && o.status !== "in_progress")) return false;
    var s = currentStash();
    if (s && !s.online) return true;
    var since = num(o.cancelRequestedAt, 0) || num(o.updatedAt, 0);
    return serverNow() - since >= FORCE_CANCEL_AFTER_MS;
  }

  function renderOrderList() {
    el.ordersClear.hidden = !can("ordersAll");
    el.ordersClear.disabled = !finishedCount();
    var frag = document.createDocumentFragment();
    if (!orders.list.length) {
      var none = document.createElement("div");
      none.className = "note";
      none.textContent = "No orders yet for this stash.";
      frag.appendChild(none);
    }
    orders.list.forEach(function (o) {
      if (!o || typeof o !== "object" || !Array.isArray(o.lines)) return;
      var card = document.createElement("div");
      card.className = "order-card";
      var head = document.createElement("div");
      head.className = "order-head";
      var badge = document.createElement("span");
      badge.className = "badge st-" + str(o.status);
      badge.textContent = (STATUS_LABEL[o.status] || str(o.status)) + (o.cancelRequested && OPEN_STATUS[o.status] ? " (cancel requested)" : "");
      head.appendChild(badge);
      var when = document.createElement("span");
      when.className = "note";
      when.textContent = new Date(num(o.createdAt, 0)).toLocaleString() + " · " + str(o.id);
      head.appendChild(when);
      var by = o.placedBy && typeof o.placedBy === "object" ? str(o.placedBy.name) : "";
      if (by) head.appendChild(line("note order-by", "by " + by));
      // Cancel / Retry / Force cancel: the user's own orders with "order" or "plannerOrder", any order with "ordersAll".
      var manage = canManageOrder(o);
      if (manage && OPEN_STATUS[o.status] && !o.cancelRequested) {
        var cancel = document.createElement("button");
        cancel.type = "button";
        cancel.className = "mc-button small danger";
        cancel.textContent = "Cancel";
        cancel.addEventListener("click", function () {
          var running = o.status !== "pending";
          if (!window.confirm(running ? "Ask the bot to stop this order at a safe point?" : "Cancel this order?")) return;
          cancel.disabled = true;
          api("/api/v1/orders/" + encodeURIComponent(o.id) + "/cancel", { method: "POST", body: {} }).then(function () {
            toast(running ? "Cancel requested" : "Order cancelled");
            scheduleOrdersPoll(0);
          }, function (e) {
            toast("Could not cancel" + (e && e.body && e.body.error ? ": " + e.body.error : ""));
            scheduleOrdersPoll(0);
          });
        });
        head.appendChild(cancel);
      }
      if (manage && (o.status === "failed" || o.status === "cancelled") && !o.retriedAs) {
        var retry = document.createElement("button");
        retry.type = "button";
        retry.className = "mc-button small";
        retry.textContent = "Retry";
        retry.title = "Place this order again as a new order";
        retry.addEventListener("click", function () {
          retry.disabled = true;
          orders.retryErrors.delete(o.id);
          api("/api/v1/orders/" + encodeURIComponent(o.id) + "/retry", { method: "POST", body: {} }).then(function () {
            toast("Order placed again");
            scheduleOrdersPoll(0);
          }, function (e) {
            var why = craftConflictText(e) || (e && e.body && e.body.error ? str(e.body.error) : "HTTP " + (e && e.status ? e.status : "error"));
            orders.retryErrors.set(o.id, why);
            toast("Could not retry: " + why);
            renderOrderList();
          });
        });
        head.appendChild(retry);
      }
      if (manage && canForceCancel(o)) {
        var force = document.createElement("button");
        force.type = "button";
        force.className = "mc-button small danger";
        force.textContent = "Force cancel";
        force.addEventListener("click", function () {
          if (!window.confirm("The bot hasn't confirmed. Mark this order cancelled anyway? If the bot is still working on it, " +
            "it will stop when it next reports.")) return;
          force.disabled = true;
          api("/api/v1/orders/" + encodeURIComponent(o.id) + "/force-cancel", { method: "POST", body: {} }).then(function () {
            toast("Order cancelled");
            scheduleOrdersPoll(0);
          }, function (e) {
            toast("Could not force cancel" + (e && e.body && e.body.error ? ": " + e.body.error : ""));
            scheduleOrdersPoll(0);
          });
        });
        head.appendChild(force);
      }
      card.appendChild(head);

      if (o.phase || o.progress) {
        var phase = document.createElement("div");
        phase.className = "order-phase";
        phase.textContent = str(o.phase);
        hide(phase);
        card.appendChild(phase);
      }
      if (o.progress && typeof o.progress === "object" && num(o.progress.total, 0) > 0) {
        var bar = document.createElement("div");
        bar.className = "progress";
        var fill = document.createElement("div");
        fill.className = "progress-fill";
        // A transform on the CSSOM (allowed by the CSP) instead of an inline width style.
        fill.style.transform = "scaleX(" + Math.max(0, Math.min(1, num(o.progress.done, 0) / num(o.progress.total, 1))) + ")";
        bar.appendChild(fill);
        bar.title = num(o.progress.done, 0) + " / " + num(o.progress.total, 0);
        card.appendChild(bar);
      }
      if (o.message) {
        var msg = document.createElement("div");
        msg.className = "order-message" + (o.status === "failed" ? " warn" : "");
        msg.textContent = str(o.message);
        hide(msg);
        card.appendChild(msg);
      }
      var nm = o.names && typeof o.names === "object" ? o.names : null;
      if (nm && nm.mode === "all" && typeof nm.name === "string" && nm.name) {
        card.appendChild(line("order-names", "Named: " + nm.name));
      } else if (nm && nm.mode === "each" && Array.isArray(nm.names)) {
        var named = [];
        nm.names.forEach(function (v, i) { if (typeof v === "string" && v.trim()) named.push("Shulker " + (i + 1) + ": " + v); });
        if (named.length) {
          var nd = document.createElement("details");
          nd.className = "order-names";
          var openKey = "names:" + o.id;
          nd.open = orders.expanded.has(openKey);
          nd.addEventListener("toggle", function () {
            if (nd.open) orders.expanded.add(openKey);
            else orders.expanded.delete(openKey);
          });
          var ns = document.createElement("summary");
          ns.textContent = "Named each (" + named.length + ")";
          nd.appendChild(ns);
          named.forEach(function (t) { nd.appendChild(line("order-line", t)); });
          card.appendChild(nd);
        }
      }
      appendRepairInfo(card, o);
      appendCraftInfo(card, o);
      appendKitInfo(card, o);
      if (orders.retryErrors.has(o.id)) card.appendChild(line("note warn", "Retry refused: " + orders.retryErrors.get(o.id)));

      var lines = linesSummary(o);
      var details = document.createElement("details");
      details.className = "order-lines";
      details.open = orders.expanded.has(o.id);
      details.addEventListener("toggle", function () {
        if (details.open) orders.expanded.add(o.id);
        else orders.expanded.delete(o.id);
      });
      var summary = document.createElement("summary");
      summary.textContent = lines.slice(0, 3).join(", ") + (lines.length > 3 ? ", +" + (lines.length - 3) + " more" : "");
      var hasKitLine = o.lines.some(function (l) { return l && l.type === "kit"; });
      if (hasKitLine && streamerOn()) hide(summary); // kit names are blurred in streamer mode
      details.appendChild(summary);
      lines.forEach(function (t, li) {
        var ln = line("order-line", t);
        if (o.lines[li] && o.lines[li].type === "kit" && streamerOn()) hide(ln);
        details.appendChild(ln);
      });
      if (o.result && typeof o.result === "object") {
        details.appendChild(line("order-line note", "Delivered in " + plural(String(num(o.result.shulkers, 0)), "shulker")));
      }
      card.appendChild(details);
      frag.appendChild(card);
    });
    el.orderList.replaceChildren(frag);
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && state.tab === "orders") scheduleOrdersPoll(0);
  });

  // ---------- crafting table settings (in the Kits tab: the stash's settings) ----------
  // GET/POST /api/v1/craft*: where the stash's crafting table is, and what must never be used as an ingredient
  // (CRAFT-CONTRACT.md). The coordinates are positions: blurred in streamer mode, and a user the owner forced into
  // streamer mode gets 403 {permission: "streamer"} for all of it. Polled with the Kits tab.

  var CRAFT_POLL_MS = 5000;
  var CRAFT_DIMS = [["minecraft:overworld", "Overworld"], ["minecraft:the_nether", "The Nether"], ["minecraft:the_end", "The End"]];
  var CRAFT_ID = /^[a-z0-9_.-]+:[a-z0-9_.\/-]{1,128}$/;
  var CRAFT_MAX_NEVER = 200;
  var CRAFT_TABLE_WORDS = {
    auto: "No table is set: the bot will find, place or craft a crafting table.",
    unset: "No crafting table is set.",
    ok: "The bot found the crafting table and can reach it.",
    unloaded: "The bot hasn't been near that spot yet, so it can't check the table. That is fine: it checks when it gets there.",
    not_table: "The bot looked at that spot and there is no crafting table there.",
    unreachable: "The bot can't reach the crafting table.",
    other_dimension: "The table is in a different dimension than the stash.",
    unknown: "The bot hasn't reported on the table yet."
  };
  var craftSet = { timer: 0, loading: false, writing: false, token: 0, data: null, error: "", sig: "", snap: null, listed: 0 };

  function craftEl(id) {
    return $("craft-" + id);
  }

  /** Starts or stops the polling to match what is on screen (the Kits tab). */
  function craftSetSync() {
    window.clearTimeout(craftSet.timer);
    craftSet.timer = 0;
    if (kitsVisible()) craftSetPoll();
  }

  function craftSetReset() {
    craftSet.token++;
    craftSet.data = null;
    craftSet.error = "";
    craftSet.sig = "";
    craftSet.snap = null;
    ["x", "y", "z"].forEach(function (f) { craftEl(f).value = ""; });
    craftSetRender();
    craftSetSync();
  }

  function craftSetPoll() {
    if (craftSet.loading || craftSet.writing) return;
    var s = currentStash();
    if (!s) return;
    craftSet.loading = true;
    var token = craftSet.token;
    api("/api/v1/craft?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== craftSet.token) return;
      craftSet.data = d && typeof d === "object" ? d : null;
      craftSet.error = "";
    }, function (e) {
      if (token !== craftSet.token) return;
      craftSet.data = null;
      craftSet.error = craftError(e, "read");
    }).then(function () {
      craftSet.loading = false;
      if (token !== craftSet.token) {
        craftSetSync();
        return;
      }
      craftSetRender();
      window.clearTimeout(craftSet.timer);
      craftSet.timer = kitsVisible() ? window.setTimeout(craftSetPoll, CRAFT_POLL_MS) : 0;
    });
  }

  /** A failed request as a sentence. */
  function craftError(e, what) {
    var b = e && e.body && typeof e.body === "object" ? e.body : {};
    if (e && e.status === 403 && b.permission === "streamer") return "Crafting settings are hidden while streamer mode is forced on for your account, because they show positions.";
    if (e && e.status === 403) return what === "read" ? "You don't have permission to see the crafting settings." : "Changing the crafting settings needs the “settings” permission.";
    if (e && e.status === 404) return "That stash isn't available.";
    if (e && e.status === 429) return "Too many changes. Wait " + num(b.retryAfter, 1) + " s.";
    if (e && e.status === 503) return "The server can't read its crafting settings (craft.json is damaged). Fix or remove the file and restart the server.";
    if (e && e.network) return "Can't reach the server. Trying again.";
    if (typeof b.error === "string" && b.error) return safeServerText(sentence(b.error), "The server refused that (details are hidden in streamer mode).");
    return what === "read" ? "Couldn't load the crafting settings." : "Couldn't save that.";
  }

  /** The table the editor holds, { x, y, z, dimension }, or null when it is empty or not valid. */
  function craftSetRead() {
    var out = {};
    var ok = true;
    ["x", "y", "z"].forEach(function (f) {
      var raw = craftEl(f).value.trim();
      if (!/^-?[0-9]{1,8}$/.test(raw)) {
        ok = false;
        return;
      }
      out[f] = parseInt(raw, 10);
    });
    if (!ok) return null;
    if (Math.abs(out.x) > 30000000 || Math.abs(out.z) > 30000000 || out.y < -64 || out.y > 320) return null;
    out.dimension = craftEl("dim").value;
    return CRAFT_ID.test(out.dimension) ? out : null;
  }

  function craftSetTouched() {
    return ["x", "y", "z"].some(function (f) { return craftEl(f).value.trim() !== ""; });
  }

  function craftSetCan() {
    return kitsCan() && !craftSet.writing;
  }

  /** The Save / Clear buttons and the hint under the fields, for what the editor holds right now. */
  function craftSetUpdate() {
    var d = craftSet.data;
    var t = craftSetRead();
    var st = d && d.table && typeof d.table === "object" ? d.table : null;
    var same = !!t && !!st && st.x === t.x && st.y === t.y && st.z === t.z && st.dimension === t.dimension;
    craftEl("save").disabled = !d || !t || same || !craftSetCan();
    craftEl("clear").disabled = !d || !st || !craftSetCan();
    var msg = craftEl("msg");
    if (!d) msg.textContent = "";
    else if (!craftSetTouched()) {
      msg.textContent = "Type the crafting table's coordinates" + (Array.isArray(d.seen) && d.seen.length ? ", or pick one the bot saw." : ".") +
        " It must be within 64 blocks of the kits chest, the delivery container or a table the bot saw; if none of those is set, it must be one the bot saw.";
    }
    else if (!t) msg.textContent = "Check the numbers: x and z up to 30,000,000, y from -64 to 320.";
    else if (same) msg.textContent = "This is the table the bot uses.";
    else msg.textContent = "Not saved yet.";
  }

  function craftDimName(id) {
    for (var i = 0; i < CRAFT_DIMS.length; i++) if (CRAFT_DIMS[i][0] === id) return CRAFT_DIMS[i][1];
    return id;
  }

  /** The dimension choices: the three vanilla ones, plus the stash's own and the saved one when they are other. */
  function craftSetDims(extra, only) {
    var sel = craftEl("dim");
    var list = only ? [] : CRAFT_DIMS.slice();
    extra.forEach(function (id) {
      if (id && CRAFT_ID.test(id) && !list.some(function (e) { return e[0] === id; })) list.push([id, id]);
    });
    var key = list.map(function (e) { return e[0]; }).join(",");
    if (sel.dataset.key === key) return;
    var keep = sel.value;
    sel.dataset.key = key;
    sel.replaceChildren();
    list.forEach(function (e) { sel.appendChild(kitOption(e[0], e[1] === e[0] ? craftDimName(e[0]) : e[1])); });
    sel.value = keep && list.some(function (e) { return e[0] === keep; }) ? keep : list[0][0];
  }

  /** Shows the server's table in the editor, unless the user is in the middle of editing it. */
  function craftSetFields(d) {
    var disabled = !d || !craftSetCan();
    ["x", "y", "z", "dim"].forEach(function (f) { craftEl(f).disabled = disabled; });
    var idxDim = state.index && typeof state.index.dimension === "string" ? state.index.dimension : "";
    var st = d && d.table && typeof d.table === "object" ? d.table : null;
    // The server only takes a table in the stash's own dimension; without an index any of the three.
    craftSetDims(CRAFT_ID.test(idxDim) ? [idxDim, st ? str(st.dimension) : ""] : [st ? str(st.dimension) : "", craftEl("dim").value], CRAFT_ID.test(idxDim));
    if (!d) return;
    var fallback = CRAFT_ID.test(idxDim) ? idxDim : "minecraft:overworld";
    var server = st ? [String(st.x), String(st.y), String(st.z), str(st.dimension)] : ["", "", "", fallback];
    var shown = [craftEl("x").value.trim(), craftEl("y").value.trim(), craftEl("z").value.trim(), craftEl("dim").value];
    var same = function (a, b) { return a.join("|") === b.join("|"); };
    if (same(shown, server)) {
      craftSet.snap = server;
    } else if (!craftSet.snap || same(shown, craftSet.snap)) {
      craftEl("x").value = server[0];
      craftEl("y").value = server[1];
      craftEl("z").value = server[2];
      craftEl("dim").value = server[3];
      craftSet.snap = server;
    }
  }

  /** The bot's state in plain words, as notes above the fields. */
  function craftSetBanner(box, d) {
    var bot = d.bot && typeof d.bot === "object" ? d.bot : {};
    var rec = d.recipes && typeof d.recipes === "object" ? d.recipes : {};
    var mc = str(rec.mc) || "this Minecraft version";
    var auto = !d.table && d.tableStatus === "auto";
    var tableOk = d.tableStatus === "ok" || d.tableStatus === "unloaded";
    var notes = [];
    if (bot.supports !== true) {
      notes.push(["warn", "This stash's bot can't craft orders yet (update the plugin or addon). Your settings are saved and apply once it can."]);
    } else {
      if (bot.enabled !== true) notes.push(["warn", "The bot has crafting turned off. Turn on crafting and craft orders in its settings."]);
      if (bot.online !== true) notes.push(["warn", "The bot is offline. It picks the settings up when it is back."]);
      else if (bot.synced !== true) notes.push(["", "The bot hasn't picked up the latest table yet. This takes a few seconds."]);
    }
    if (rec.state === "building") notes.push(["", "The server is still loading the recipes for " + mc + "."]);
    else if (rec.state !== "ready") notes.push(["warn", "The server has no crafting recipes for " + mc + ", so crafted items can't be ordered."]);
    else if (bot.supports === true && bot.recipesMatch !== true) notes.push(["warn", "The bot's recipe table differs from the server's. Update or restart the bot so they match."]);
    if (d.table) {
      var word = CRAFT_TABLE_WORDS[str(d.tableStatus)] || CRAFT_TABLE_WORDS.unknown;
      notes.push([d.tableStatus === "ok" ? "ok" : d.tableStatus === "unloaded" || d.tableStatus === "unknown" ? "" : "off", word]);
      if (d.tableStatus !== "ok" && craftWhyText(d.tableWhy)) notes.push(["off", craftWhyText(d.tableWhy)]);
    } else if (auto) {
      notes.push(["ok", CRAFT_TABLE_WORDS.auto]);
    } else if (bot.supports === true && bot.enabled === true && bot.synced !== true && bot.online === true) {
      notes.push(["", "Waiting for the bot to pick up the latest settings."]);
    } else {
      if (craftWhyText(d.tableWhy)) notes.push(["off", craftWhyText(d.tableWhy)]);
      notes.push(["warn", CRAFT_TABLE_WORDS.unset + " This bot can't find, place or craft one by itself, so set a table here to order crafted items."]);
    }
    var bt = d.botTable && typeof d.botTable === "object" && typeof d.botTable.pos === "string" && /^-?[0-9]+,-?[0-9]+,-?[0-9]+$/.test(d.botTable.pos) ? d.botTable : null;
    var sameAsWebsite = bt && d.table && typeof d.table === "object" && [d.table.x, d.table.y, d.table.z].join(",") === bt.pos;
    if (bt && (!d.table || !sameAsWebsite)) {
      var how = bt.source === "placed" ? "placed by the bot" : bt.source === "crafted" ? "crafted by the bot" : "found by the bot";
      // A position: hidden in streamer mode.
      notes.push(["", "Bot's table: " + (streamerOn() ? "(hidden)" : kitCoords(bt.pos).replace(/,\s*/g, " ")) + " (" + how + ")"]);
    }
    if (bot.supports === true && bot.enabled === true && bot.online === true && bot.synced === true && bot.recipesMatch === true && rec.state === "ready" && ((d.table && tableOk) || auto)) {
      notes.push(["ok", "Ready: the bot can craft orders" + (num(bot.maxFills, 0) > 0 ? ", up to " + plural(grouped(bot.maxFills), "craft") + " at the table per order." : ".")]);
    } else if (bot.supports === true && num(bot.maxFills, 0) > 0) {
      notes.push(["", "The bot makes up to " + plural(grouped(bot.maxFills), "craft") + " at the table per order."]);
    }
    if (!kitsCan()) notes.push(["", "You can look, but changing the crafting table needs the “settings” permission."]);
    box.replaceChildren();
    notes.forEach(function (n) { kitNote(box, n[1], n[0]); });
    box.hidden = !notes.length;
  }

  function craftSetRender() {
    if (!craftEl("banner")) return;
    var d = craftSet.data;
    var sig = JSON.stringify([d, craftSet.error, craftSet.writing, kitsCan(), streamerOn(), !!currentStash(), state.items.length,
      state.index && state.index.dimension]);
    if (sig === craftSet.sig) return;
    var a = document.activeElement;
    if (a && craftEl("dim") === a && !craftSet.writing) return; // not under an open dropdown; the next poll draws it
    craftSet.sig = sig;
    var banner = craftEl("banner");
    var seen = craftEl("seen");
    var chips = craftEl("never-list");
    if (!currentStash()) {
      banner.replaceChildren();
      kitNote(banner, "No stash selected.");
      banner.hidden = false;
    } else if (!d) {
      banner.replaceChildren();
      kitNote(banner, craftSet.error || "Loading…", craftSet.error ? "off" : "");
      banner.hidden = false;
    } else {
      craftSetBanner(banner, d);
    }
    craftSetFields(d);
    craftSetUpdate();
    // Tables the bot saw: positions, so blurred in streamer mode.
    var list = d && Array.isArray(d.seen) ? d.seen.filter(function (v) { return typeof v === "string" && /^-?[0-9]+,-?[0-9]+,-?[0-9]+$/.test(v); }) : [];
    var frag = document.createDocumentFragment();
    list.forEach(function (v) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "mc-button small";
      b.appendChild(hiddenSpan(kitCoords(v)));
      b.disabled = !craftSetCan();
      b.setAttribute("aria-label", "Use the crafting table the bot saw" + (streamerOn() ? "" : " at " + kitCoords(v)));
      b.addEventListener("click", function () {
        var p = v.split(",");
        craftEl("x").value = p[0];
        craftEl("y").value = p[1];
        craftEl("z").value = p[2];
        craftSetUpdate();
      });
      frag.appendChild(b);
    });
    craftEl("seen-list").replaceChildren(frag);
    seen.hidden = !list.length;
    // Never-use list.
    var never = d && Array.isArray(d.neverUse) ? d.neverUse.filter(function (v) { return typeof v === "string"; }) : [];
    var cf = document.createDocumentFragment();
    never.forEach(function (id) {
      var chip = document.createElement("span");
      chip.className = "craft-chip";
      var item = itemByKey(id);
      var label = document.createElement("span");
      label.textContent = item ? item.name : plPretty(id);
      label.title = id;
      chip.appendChild(label);
      var x = document.createElement("button");
      x.type = "button";
      x.textContent = "×";
      x.setAttribute("aria-label", "Allow using " + (item ? item.name : plPretty(id)) + " again");
      x.title = "Allow using it again";
      x.disabled = !craftSetCan();
      x.addEventListener("click", function () {
        craftWrite("/api/v1/craft/never-use", { ids: never.filter(function (v) { return v !== id; }) });
      });
      chip.appendChild(x);
      cf.appendChild(chip);
    });
    if (!never.length && d) cf.appendChild(line("hint", "Nothing is excluded."));
    chips.replaceChildren(cf);
    craftEl("never-add").disabled = !d || !craftSetCan();
    craftEl("never-btn").disabled = !d || !craftSetCan() || never.length >= CRAFT_MAX_NEVER;
    // Item suggestions for the add box: plain items of this stash.
    var dl = craftEl("never-ids");
    if (dl && craftSet.listed !== state.items.length) {
      craftSet.listed = state.items.length;
      var opts = document.createDocumentFragment();
      var done = new Set();
      state.items.forEach(function (e) {
        var it = e.item;
        if (!it || it.key !== it.id || !CRAFT_ID.test(it.id) || done.has(it.id) || done.size >= 2000) return;
        done.add(it.id);
        var o = document.createElement("option");
        o.value = it.id;
        o.label = it.name;
        opts.appendChild(o);
      });
      dl.replaceChildren(opts);
    }
  }

  /** A write; the answer is the same body as GET, so it is drawn straight away. */
  function craftWrite(path, body, done) {
    var s = currentStash();
    if (!s || craftSet.writing) return;
    craftSet.writing = true;
    var token = ++craftSet.token;
    body.server = s.server;
    body.stash = s.stash;
    craftSet.sig = "";
    craftSetRender();
    api(path, { method: "POST", body: body }).then(function (d) {
      if (token !== craftSet.token) return;
      craftSet.data = d && typeof d === "object" ? d : craftSet.data;
      craftSet.error = "";
      toast("Saved. The bot picks it up in a few seconds.");
      if (done) done();
    }, function (e) {
      toast(craftError(e, "write"));
    }).then(function () {
      craftSet.writing = false;
      craftSet.token++;
      craftSet.sig = "";
      craftSetRender();
      craftSetSync();
    });
  }

  ["x", "y", "z"].forEach(function (f) {
    craftEl(f).addEventListener("input", craftSetUpdate);
  });
  craftEl("dim").addEventListener("change", craftSetUpdate);
  craftEl("save").addEventListener("click", function () {
    var t = craftSetRead();
    if (t) craftWrite("/api/v1/craft/table", { table: t });
  });
  craftEl("clear").addEventListener("click", function () {
    craftWrite("/api/v1/craft/table", { table: null }, function () {
      craftSet.snap = null;
      ["x", "y", "z"].forEach(function (f) { craftEl(f).value = ""; });
    });
  });

  // ---------- Repair (REPAIR-CONTRACT v2.0) ----------
  // GET/POST /api/v1/repair*: the stash's repair settings, the bot's status and the last pass. The chest and the item lists show
  // positions, so a user the owner forced into streamer mode gets 403 {permission: "streamer"} for all of it. Polled with the Kits tab.

  var REPAIR_POLL_MS = 5000;
  var REPAIR_SHULKER = 1728;
  var REPAIR_WHY = {
    disabled: "repair mode is off",
    no_settings: "the bot hasn't got these settings yet",
    reserve: "there aren't enough bottles above the reserve",
    no_candidates: "nothing needs repair",
    order: "an order comes first",
    player_near: "a player is nearby",
    foreign: "another module is working",
    sorter_off: "the sorter is off",
    offline: "the bot is offline",
    cooldown: "it waits between passes",
    inventory_full: "the bot's inventory is full",
    kit_conflict: "the needs-repair chest is also a kit chest"
  };
  var REPAIR_ITEM_WHY = {
    "no bottles": "no bottles", reserve: "kept for the reserve", interrupted: "interrupted", "in a shulker": "in a shulker box", "no room": "no room",
    stopped: "stopped", order: "an order came first", cooldown: "tried recently", "in a named shulker": "in a named box (not opened)",
    "in a kit": "in a kit (not opened)", "box cap": "too many boxes this pass", "box failed": "the box couldn't be opened", "player near": "a player was near"
  };
  var repairSet = { timer: 0, loading: false, writing: false, token: 0, data: null, error: "", sig: "", dirty: false };

  function repEl(id) {
    return $("repair-" + id);
  }

  function repairSync() {
    window.clearTimeout(repairSet.timer);
    repairSet.timer = 0;
    if (kitsVisible()) repairPoll();
  }

  function repairReset() {
    repairSet.token++;
    repairSet.data = null;
    repairSet.error = "";
    repairSet.sig = "";
    repairSet.dirty = false;
    repairRender();
    repairSync();
  }

  function repairPoll() {
    if (repairSet.loading || repairSet.writing) return;
    var s = currentStash();
    if (!s) return;
    repairSet.loading = true;
    var token = repairSet.token;
    api("/api/v1/repair?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== repairSet.token) return;
      repairSet.data = d && typeof d === "object" ? d : null;
      repairSet.error = "";
    }, function (e) {
      if (token !== repairSet.token) return;
      repairSet.data = null;
      repairSet.error = repairError(e, "read");
    }).then(function () {
      repairSet.loading = false;
      if (token !== repairSet.token) {
        repairSync();
        return;
      }
      repairRender();
      window.clearTimeout(repairSet.timer);
      repairSet.timer = kitsVisible() ? window.setTimeout(repairPoll, REPAIR_POLL_MS) : 0;
    });
  }

  function repairError(e, what) {
    var b = e && e.body && typeof e.body === "object" ? e.body : {};
    if (e && e.status === 403 && b.permission === "streamer") return "Repair settings are hidden while streamer mode is forced on for your account, because they show positions.";
    if (e && e.status === 403) return what === "read" ? "You don't have permission to see the repair settings." : "Changing the repair settings needs the “settings” permission.";
    if (e && e.status === 404) return "That stash isn't available.";
    if (e && e.status === 429) return "Too many changes. Wait " + num(b.retryAfter, 1) + " s.";
    if (e && e.status === 503) return "The server can't read its repair settings (repair.json is damaged). Fix or remove the file and restart the server.";
    if (e && e.network) return "Can't reach the server. Trying again.";
    if (typeof b.error === "string" && b.error) return safeServerText(sentence(b.error), "The server refused that (details are hidden in streamer mode).");
    return what === "read" ? "Couldn't load the repair settings." : "Couldn't save that.";
  }

  function repairCan() {
    return can("settings") && !forcedStreamer() && !repairSet.writing;
  }

  /** The reserve the three boxes hold, in bottles (null when one is not a whole number). */
  function repairReserveRead() {
    var parts = [repEl("res-shulkers").value, repEl("res-stacks").value, repEl("res-bottles").value];
    var n = [];
    for (var i = 0; i < 3; i++) {
      var t = String(parts[i]).trim();
      if (t === "") t = "0";
      if (!/^[0-9]{1,7}$/.test(t)) return null;
      n.push(parseInt(t, 10));
    }
    var total = n[0] * REPAIR_SHULKER + n[1] * 64 + n[2];
    return total <= 1000000 ? total : null;
  }

  function repairReserveWrite(total) {
    var sh = Math.floor(total / REPAIR_SHULKER);
    var rest = total - sh * REPAIR_SHULKER;
    repEl("res-shulkers").value = String(sh);
    repEl("res-stacks").value = String(Math.floor(rest / 64));
    repEl("res-bottles").value = String(rest % 64);
  }

  function repairThresholdRead() {
    var t = repEl("threshold").value.trim();
    if (!/^[0-9]{1,3}$/.test(t)) return null;
    var v = parseInt(t, 10);
    return v >= 1 && v <= 100 ? v : null;
  }

  /** Plain words for what the bot is doing (bot.state and bot.why). */
  function repairStateText(bot) {
    if (!bot || !bot.supports) return "This stash's bot can't run repair passes (it needs StashSorter 1.17.0+).";
    var why = bot.why && REPAIR_WHY[bot.why] ? REPAIR_WHY[bot.why] : "";
    var p = bot.progress && typeof bot.progress === "object" ? " (" + num(bot.progress.done, 0) + " of " + num(bot.progress.total, 0) + " items)" : "";
    var t = bot.state;
    var out;
    if (t === "off") out = "The bot has repair passes turned off.";
    else if (t === "idle") out = "The bot is idle and will start a pass when something needs repair.";
    else if (t === "waiting") out = "The bot wants to repair but is waiting" + (why ? ": " + why : "") + ".";
    else if (t === "running") out = "The bot is repairing" + p + ".";
    else if (t === "yielding") out = "The bot is putting things back to make way" + (why ? " (" + why + ")" : "") + ".";
    else if (t === "blocked") out = "The bot can't repair right now" + (why ? ": " + why : "") + ".";
    else out = "The bot hasn't reported on repair yet.";
    if (!bot.synced) out += " It hasn't picked up the latest settings yet.";
    if (bot.runPending) out += " A run is requested.";
    if (bot.stopPending) out += " A stop is requested.";
    return out;
  }

  function repairTile(label, value, sub) {
    var t = kitEl("div", "repair-tile");
    t.appendChild(kitEl("div", "repair-tile-n", String(value)));
    t.appendChild(kitEl("div", "repair-tile-l", label));
    if (sub) t.appendChild(kitEl("div", "hint", sub));
    return t;
  }

  function repairPassItemName(key) {
    var id = str(key).split("#")[0];
    var path = id.indexOf(":") >= 0 ? id.split(":")[1] : id;
    var words = path.split("_").join(" ");
    return words.charAt(0).toUpperCase() + words.slice(1);
  }

  function repairRender() {
    var d = repairSet.data;
    var banner = repEl("banner");
    banner.hidden = !repairSet.error;
    banner.textContent = repairSet.error;
    var st = d && d.settings && typeof d.settings === "object" ? d.settings : null;
    var can_ = repairCan();
    var sig = d ? JSON.stringify([d, streamerOn()]) : "";
    var inputs = ["enabled", "auto", "shulkers", "threshold", "res-shulkers", "res-stacks", "res-bottles", "chest"];
    // The controls take the stored values unless the user is editing them.
    if (st && !repairSet.dirty && repairSet.sig !== sig) {
      repEl("enabled").checked = !!st.enabled;
      repEl("auto").checked = !!st.auto;
      repEl("shulkers").checked = st.shulkers !== false;
      repEl("threshold").value = String(num(st.threshold, 50));
      repairReserveWrite(num(st.reserve, 1728));
    }
    if (!st) {
      ["threshold", "res-shulkers", "res-stacks", "res-bottles"].forEach(function (f) { repEl(f).value = ""; });
      ["enabled", "auto", "shulkers"].forEach(function (f) { repEl(f).checked = false; });
    }
    inputs.forEach(function (f) { repEl(f).disabled = !st || !can_; });
    // The chest picker: pickable containers (labels and free slots), plus the current value when it is no longer one of them.
    var sel = repEl("chest");
    if (repairSet.sig !== sig || !sel.options.length) {
      sel.textContent = "";
      var none = document.createElement("option");
      none.value = "";
      none.textContent = "(not set)";
      sel.appendChild(none);
      var list = d && Array.isArray(d.containers) ? d.containers : [];
      var cur = st ? st.needsRepairChest : null;
      var seen = false;
      var n = 0;
      list.forEach(function (c) {
        if (!c.pickable) return;
        var o = document.createElement("option");
        o.value = c.id;
        o.textContent = streamerOn() ? "Chest " + (++n) + " · " + num(c.free, 0) + " free" : kitCoords(c.id) + (c.label ? " " + c.label : "") + " (" + num(c.free, 0) + " free)";
        if (c.id === cur) seen = true;
        sel.appendChild(o);
      });
      if (cur && !seen) {
        var o2 = document.createElement("option");
        o2.value = cur;
        o2.textContent = streamerOn() ? "Chest (not reported)" : kitCoords(cur) + " (not reported)";
        sel.appendChild(o2);
      }
      sel.value = cur || "";
    }
    repEl("chest-clear").disabled = !st || !st.needsRepairChest || !can_;
    var cs = d ? d.needsRepairChestStatus : "";
    repEl("chest-status").textContent = !st ? "" : cs === "unset" ? "No chest: items without Mending stay where they are." :
      cs === "ok" ? "Ready." : cs === "full" ? "That chest is full." : cs === "missing" ? "The bot doesn't see that chest." :
      cs === "reserved" ? "That chest is also used for kits." : "";
    var t = repairThresholdRead();
    var r = repairReserveRead();
    repEl("reserve-note").textContent = r == null ? "Check the reserve numbers." : "Keeps at least " + r + " bottles for orders.";
    var changed = !!st && t != null && r != null && (repEl("enabled").checked !== !!st.enabled || repEl("auto").checked !== !!st.auto ||
      repEl("shulkers").checked !== (st.shulkers !== false) || t !== st.threshold || r !== st.reserve);
    repEl("save").disabled = !st || !can_ || !changed || t == null || r == null;
    var bot = d && d.bot ? d.bot : null;
    var supports = !!bot && !!bot.supports;
    repEl("run").disabled = !can_ || !supports || !st;
    repEl("stop").disabled = !can_ || !supports || !st;
    repEl("state").textContent = d ? repairStateText(bot) : "";
    repEl("msg").textContent = d && !can("settings") ? "You can look at these settings but not change them." : "";
    if (repairSet.sig === sig) return;
    repairSet.sig = sig;
    // The tiles: the bot's own numbers when it sent them, else the server's estimate from the index.
    var tiles = repEl("tiles");
    tiles.textContent = "";
    if (d) {
      var cand = bot && bot.candidates && typeof bot.candidates === "object" ? bot.candidates : null;
      var est = d.estimate && typeof d.estimate === "object" ? d.estimate : null;
      var bt = bot && bot.bottles && typeof bot.bottles === "object" ? bot.bottles : null;
      if (cand) {
        tiles.appendChild(repairTile("Mending items to repair", num(cand.mend, 0), num(cand.mendBottles, 0) + " bottles needed"));
        tiles.appendChild(repairTile("Without Mending (to the needs-repair chest)", num(cand.needsRepair, 0), ""));
        tiles.appendChild(repairTile("In boxes the bot will open", num(cand.inBoxes, 0), num(cand.boxes, 0) + " boxes"));
        tiles.appendChild(repairTile("In named boxes or kits (not opened)", num(cand.inShulkers, 0), "Name a box to keep it closed."));
      } else if (est) {
        tiles.appendChild(repairTile("Mending items to repair", num(est.mend && est.mend.items, 0), num(est.mend && est.mend.bottles, 0) + " bottles needed"));
        tiles.appendChild(repairTile("Without Mending (to the needs-repair chest)", num(est.needsRepair && est.needsRepair.items, 0), ""));
        tiles.appendChild(repairTile("In shulker boxes", num(est.mend && est.mend.inShulkers, 0) + num(est.needsRepair && est.needsRepair.inShulkers, 0),
          "Opened only if unnamed and not a kit."));
      }
      var usable = bt ? num(bt.usable, 0) : est && est.bottles ? num(est.bottles.usable, 0) : null;
      if (usable != null) tiles.appendChild(repairTile("Bottles usable", usable, "Above the reserve."));
    }
    // The last pass.
    var last = repEl("last");
    last.textContent = "";
    var lp = d && d.lastPass && typeof d.lastPass === "object" ? d.lastPass : null;
    if (lp) {
      var head = kitEl("div", "hint", "Last pass (" + str(lp.trigger) + ", " + str(lp.state) + "): " + num(lp.repaired, 0) + " repaired, " + num(lp.partial, 0) +
        " partly, " + num(lp.bottlesUsed, 0) + " bottles used, " + (num(lp.rehomed, 0) + num(lp.toNeedsRepair, 0)) + " moved, " + num(lp.skipped, 0) + " skipped" +
        (lp.boxesOpened ? ", " + num(lp.boxesOpened, 0) + " boxes opened" : "") + ".");
      last.appendChild(head);
      var by = lp.skippedBy && typeof lp.skippedBy === "object" ? Object.keys(lp.skippedBy) : [];
      if (by.length) {
        last.appendChild(kitEl("div", "hint", "Skipped because: " + by.map(function (k) { return (REPAIR_ITEM_WHY[k] || k) + " (" + num(lp.skippedBy[k], 0) + ")"; }).join(", ") + "."));
      }
      var items = Array.isArray(d.items) ? d.items : [];
      items.slice(0, 50).forEach(function (it) {
        var row = kitEl("div", "repair-item");
        var why = it.reason && REPAIR_ITEM_WHY[it.reason] ? " – " + REPAIR_ITEM_WHY[it.reason] : "";
        row.appendChild(kitEl("span", "", repairPassItemName(it.key) + " (" + str(it.status).replace("_", " ") + ")" + why));
        if (it.from || it.to) {
          // The arrow only when the bot says where it went; otherwise just where it was.
          var span = kitEl("span", "hint", it.to ? " " + (it.from ? kitCoords(it.from) : "?") + " → " + kitCoords(it.to) : " in " + kitCoords(it.from));
          span.setAttribute("data-sensitive", "");
          row.appendChild(span);
        }
        last.appendChild(row);
      });
    }
  }

  function repairWrite(path, body, done) {
    var s = currentStash();
    if (!s || repairSet.writing) return;
    repairSet.writing = true;
    var token = ++repairSet.token;
    body.server = s.server;
    body.stash = s.stash;
    repairSet.sig = "";
    repairRender();
    api(path, { method: "POST", body: body }).then(function (d) {
      if (token !== repairSet.token) return;
      repairSet.data = d && typeof d === "object" ? d : repairSet.data;
      repairSet.error = "";
      if (path.indexOf("/settings") >= 0 || path.indexOf("/chest") >= 0) {
        repairSet.dirty = false; // only a save clears the unsaved edits; a run or stop leaves them alone
        toast("Saved. The bot picks it up in a few seconds.");
      } else if (path.indexOf("/run") >= 0) {
        toast("Repair pass requested. The bot starts when it is idle.");
      } else {
        toast("Stop requested.");
      }
      if (done) done();
    }, function (e) {
      toast(repairError(e, "write"));
    }).then(function () {
      repairSet.writing = false;
      repairSet.token++;
      repairSet.sig = "";
      repairRender();
      repairSync();
    });
  }

  ["enabled", "auto", "shulkers"].forEach(function (f) {
    repEl(f).addEventListener("change", function () {
      repairSet.dirty = true;
      repairRender();
    });
  });
  ["threshold", "res-shulkers", "res-stacks", "res-bottles"].forEach(function (f) {
    repEl(f).addEventListener("input", function () {
      repairSet.dirty = true;
      repairRender();
    });
  });
  repEl("save").addEventListener("click", function () {
    var t = repairThresholdRead();
    var r = repairReserveRead();
    if (t == null || r == null) return;
    repairWrite("/api/v1/repair/settings", { enabled: repEl("enabled").checked, auto: repEl("auto").checked, shulkers: repEl("shulkers").checked,
      threshold: t, reserve: r });
  });
  repEl("chest").addEventListener("change", function () {
    var v = repEl("chest").value;
    repairWrite("/api/v1/repair/chest", { container: v || null });
  });
  repEl("chest-clear").addEventListener("click", function () {
    repairWrite("/api/v1/repair/chest", { container: null });
  });
  repEl("run").addEventListener("click", function () {
    repairWrite("/api/v1/repair/run", {});
  });
  repEl("stop").addEventListener("click", function () {
    repairWrite("/api/v1/repair/stop", {});
  });

  // ---------- Pearl travel (PEARL-CONTRACT v1.0) ----------
  // GET/POST /api/v1/pearl*: stations, the bots that can travel, trips and alerts. Names, players and stashes are shown as text only
  // (textContent) and marked data-sensitive; a user the owner forced into streamer mode gets 403 {permission: "streamer"} for all of it.

  var PEARL_POLL_MS = 5000;
  var PEARL_STATE_WORDS = {
    pending: "waiting for the bot", running: "under way", paused: "waiting", completed: "done", failed: "failed", cancelled: "stopped"
  };
  var PEARL_CODE_WORDS = {
    bot_offline: "the bot stopped sending heartbeats", not_ingame: "the bot is not in the game", in_queue: "the bot is in the queue",
    just_joined: "the bot has only just joined", low_pearls: "fewer than 2 pearls", no_pearl_assigned: "no pearl of this bot at that station",
    chamber_empty: "the chamber has no pearl", owner_offline: "the pearl's owner is offline", chamber_unknown: "the loader can't see the chamber",
    chamber_broken: "the chamber is marked broken", chamber_locked: "another trip holds the chamber", loader_locked: "another trip holds the loader",
    loader_stale: "the loader hasn't reported lately", loader_misconfigured: "the loader has PearlPlus temporary mode on",
    no_return_pearl: "no armed pearl for the way back", station_limit: "20 loads in the last hour", cooldown: "a trip ended less than 5 minutes ago",
    player_near: "a player is near", loader_player_near: "a player is near the loader", loader_busy: "the loader is busy",
    loader_no_answer: "the loader did not answer", load_aborted: "the loader gave up", pull_timeout: "no pull arrived in time",
    unexpected_location: "arrived somewhere unexpected: held", arrival_unconfirmed: "the arrival could not be confirmed", stopped: "stopped",
    order_arrived: "an order arrived", server_restarted: "the server restarted", busy_order: "an order is waiting for the stash",
    go_timeout: "the bot did not start the pull in time", uuid_mismatch: "the bot's uuid changed",
    uuid_unpinned: "the owner has not confirmed the bot's uuid"
  };
  var PEARL_ALERT_WORDS = {
    away_order: "An order is waiting for a bot that is away and has no armed pearl to come back.",
    unexpected_location: "A bot arrived somewhere unexpected and is held. Check where it is, then press \u201cIt's home\u201d.",
    rearm_failed: "A bot could not re-arm its pearl after arriving.",
    chamber_broken: "A chamber is marked broken: loads there did nothing twice.",
    arrival_unconfirmed: "A trip could not be confirmed (the server restarted, or nobody reported for 30 minutes); check where the bot is.",
    uuid_mismatch: "A bot reports a different uuid than the one its name was first seen with. It is ignored until the owner trusts the new one."
  };
  var pearlSet = { timer: 0, loading: false, writing: false, token: 0, data: null, error: "", sig: "", pick: {} };

  function pearlEl(id) {
    return $("pearl-" + id);
  }

  function pearlSync() {
    window.clearTimeout(pearlSet.timer);
    pearlSet.timer = 0;
    if (kitsVisible() && can("activity") && !forcedStreamer()) pearlPoll();
  }

  function pearlReset() {
    pearlSet.token++;
    pearlSet.data = null;
    pearlSet.error = "";
    pearlSet.sig = "";
    pearlSet.pick = {};
    pearlRender();
    pearlSync();
  }

  function pearlPoll() {
    if (pearlSet.loading || pearlSet.writing) return;
    pearlSet.loading = true;
    var token = pearlSet.token;
    api("/api/v1/pearl").then(function (d) {
      if (token !== pearlSet.token) return;
      pearlSet.data = d && typeof d === "object" ? d : null;
      pearlSet.error = "";
    }, function (e) {
      if (token !== pearlSet.token) return;
      pearlSet.data = null;
      pearlSet.error = pearlError(e, "read");
    }).then(function () {
      pearlSet.loading = false;
      if (token !== pearlSet.token) {
        pearlSync();
        return;
      }
      pearlRender();
      window.clearTimeout(pearlSet.timer);
      pearlSet.timer = kitsVisible() && can("activity") && !forcedStreamer() ? window.setTimeout(pearlPoll, PEARL_POLL_MS) : 0;
    });
  }

  function pearlError(e, what) {
    var b = e && e.body && typeof e.body === "object" ? e.body : {};
    if (e && e.status === 403 && b.permission === "streamer") return "Pearl travel is hidden while streamer mode is forced on for your account.";
    if (e && e.status === 403) return what === "read" ? "You don't have permission to see pearl travel." : "That needs the \u201csettings\u201d permission (stations: the owner).";
    if (e && e.status === 404) return "That isn't available.";
    if (e && e.status === 429) return "Too many changes. Wait " + num(b.retryAfter, 1) + " s.";
    if (e && e.status === 503) return "Pearl travel is off: the server can't read pearl.json. Fix or remove the file and restart the server.";
    if (e && e.network) return "Can't reach the server. Trying again.";
    if (typeof b.error === "string" && b.error) return safeServerText(sentence(b.error), "The server refused that (details are hidden in streamer mode).");
    return what === "read" ? "Couldn't load pearl travel." : "Couldn't save that.";
  }

  function pearlWrite(path, body, done) {
    if (pearlSet.writing) return;
    pearlSet.writing = true;
    var token = ++pearlSet.token;
    api(path, { method: "POST", body: body }).then(function (d) {
      if (token !== pearlSet.token) return;
      pearlSet.data = d && typeof d === "object" ? d : pearlSet.data;
      pearlSet.error = "";
      if (done) done();
    }, function (e) {
      toast(pearlError(e, "write"));
    }).then(function () {
      pearlSet.writing = false;
      pearlSet.token++;
      pearlSet.sig = "";
      pearlRender();
      pearlSync();
    });
  }

  function pearlText(parent, cls, text, sensitive) {
    var e = kitEl("span", cls, text);
    if (sensitive) e.setAttribute("data-sensitive", "");
    parent.appendChild(e);
    return e;
  }

  function pearlBtn(parent, label, handler, disabled) {
    var b = kitEl("button", "mc-button small", label);
    b.type = "button";
    b.disabled = !!disabled;
    b.addEventListener("click", handler);
    parent.appendChild(b);
    return b;
  }

  function pearlStationName(d, id) {
    var list = d && Array.isArray(d.stations) ? d.stations : [];
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return str(list[i].name);
    return "a station";
  }

  function pearlRender() {
    var d = pearlSet.data;
    var banner = pearlEl("banner");
    banner.hidden = !pearlSet.error;
    banner.textContent = pearlSet.error;
    var owner = !!me.owner;
    var canWrite = can("settings") && !forcedStreamer() && !pearlSet.writing;
    pearlEl("form").hidden = !d || !owner;
    var sig = d ? JSON.stringify([d, owner, canWrite, streamerOn(), pearlSet.pick]) : "";
    if (sig === pearlSet.sig) return;
    pearlSet.sig = sig;
    var alerts = pearlEl("alerts"), trav = pearlEl("travellers"), sts = pearlEl("stations"), trips = pearlEl("trips");
    [alerts, trav, sts, trips].forEach(function (n) { n.textContent = ""; });
    if (!d) return;
    (Array.isArray(d.alerts) ? d.alerts : []).forEach(function (a) {
      var row = kitEl("div", "kits-banner warn");
      pearlText(row, "", PEARL_ALERT_WORDS[a.kind] || "Something needs a look.", false);
      if (a.stash) pearlText(row, "hint", " (" + str(a.stash) + ")", true);
      if (owner && a.kind === "uuid_mismatch") {
        (Array.isArray(d.travellers) ? d.travellers : []).forEach(function (t) {
          if (t.stash !== a.stash || !t.uuidMismatch) return;
          pearlBtn(row, "Trust the new uuid", function () {
            if (!window.confirm(streamerOn() ? "Trust the new uuid of this bot?" : "Trust the new uuid of " + str(t.player) + "? Only do this when the player really changed accounts.")) return;
            if (!t.reportedUuid) return;
            pearlWrite("/api/v1/pearl/pins/reset", { player: t.player, uuid: t.reportedUuid });
          }, pearlSet.writing);
        });
      }
      alerts.appendChild(row);
    });
    var stations = Array.isArray(d.stations) ? d.stations : [];
    (Array.isArray(d.travellers) ? d.travellers : []).forEach(function (t) {
      if (!t.supports) return;
      var row = kitEl("div", "pearl-row");
      pearlText(row, "pearl-name", str(t.stash) + " \u00b7 " + str(t.player), true);
      pearlText(row, "hint", " " + num(t.pearls && t.pearls.carried, 0) + " pearls" + (t.game && t.game !== "in" ? ", " + (t.game === "queue" ? "in the queue" : "offline") : "") +
        (t.online ? "" : ", not sending heartbeats") + (t.away ? ", away" : ""), false);
      if (owner) {
        // The owner confirms the uuid the bot reported: the server never learns it from a heartbeat by itself.
        if (t.uuidConflict) pearlText(row, "kits-banner warn", " Two stashes report different uuids for this name: check which one is real before pinning.", false);
        if (t.pinnedUuid) {
          pearlText(row, "hint", " \u00b7 uuid " + str(t.pinnedUuid), true);
        } else if (t.reportedUuid) {
          pearlText(row, "hint", " \u00b7 reports uuid " + str(t.reportedUuid) + " (not confirmed)", true);
          pearlBtn(row, "Confirm uuid", function () {
            var check = "Check this uuid out of band first (in game, or on a profile site such as namemc.com) before pinning it: a wrong pin lets someone else's bot use this player's pearl.";
            if (!window.confirm(streamerOn() ? "Confirm the uuid this bot reported? " + check : "Confirm that " + str(t.player) + " has the uuid " + str(t.reportedUuid) + "? " + check)) return;
            pearlWrite("/api/v1/pearl/pins/reset", { player: t.player, uuid: t.reportedUuid });
          }, pearlSet.writing);
        }
      }
      var key = str(t.server) + "|" + str(t.stash);
      var sel = document.createElement("select");
      sel.className = "mc-select";
      sel.setAttribute("data-sensitive", "");
      stations.forEach(function (s, si) {
        var o = document.createElement("option");
        o.value = s.id;
        // An open dropdown can't be blurred: in streamer mode the stations are numbered.
        o.textContent = (streamerOn() ? "Station " + (si + 1) : str(s.name)) + (s.server !== t.server ? " (another server)" : "");
        sel.appendChild(o);
      });
      if (pearlSet.pick[key]) sel.value = pearlSet.pick[key];
      sel.addEventListener("change", function () {
        pearlSet.pick[key] = sel.value;
        pearlSet.sig = "";
      });
      sel.disabled = !canWrite || !!t.trip || !!t.away || !stations.length;
      row.appendChild(sel);
      pearlBtn(row, "Go", function () {
        var label = sel.options[sel.selectedIndex] ? sel.options[sel.selectedIndex].textContent : "the station";
        if (!window.confirm(streamerOn() ? "Send this bot by ender pearl?" : "Send " + str(t.player) + " from " + str(t.stash) + " to " + label + " by ender pearl?")) return;
        pearlWrite("/api/v1/pearl/trips", { server: t.server, stash: t.stash, station: sel.value, action: "go" });
      }, !canWrite || !!t.trip || !!t.away || !stations.length);
      pearlBtn(row, "Back", function () {
        pearlWrite("/api/v1/pearl/trips", { server: t.server, stash: t.stash, action: "back" });
      }, !canWrite || !!t.trip || !t.away);
      if (owner && t.away) {
        pearlBtn(row, "It's home", function () {
          pearlWrite("/api/v1/pearl/away/clear", { server: t.server, stash: t.stash });
        }, pearlSet.writing);
      }
      trav.appendChild(row);
    });
    if (!trav.firstChild) trav.appendChild(kitEl("div", "hint", "No bot has said it can travel by pearl yet (it needs the pearl travel setting on in StashSorter 1.18.0 or the addon 0.8.0)."));
    stations.forEach(function (s) {
      var card = kitEl("div", "pearl-station");
      var head = kitEl("div", "pearl-row");
      pearlText(head, "pearl-name", str(s.name), true);
      var ls = s.loaderState || {};
      pearlText(head, "hint", " \u00b7 " + str(s.dim).replace("minecraft:", "").replace("_", " ") + " \u00b7 loader " + str(s.loader && s.loader.player) +
        (ls.online ? ", online" : ", not reporting") + (s.stash ? " \u00b7 home of " + str(s.stash) : ""), true);
      if (ls.warning === "temporaryMode") pearlText(head, "kits-banner warn", " The loader has PearlPlus temporary mode on: trips are refused until it is switched off.", false);
      if (owner) {
        pearlBtn(head, "Remove", function () {
          pearlWrite("/api/v1/pearl/stations/delete", { id: s.id });
        }, pearlSet.writing);
      }
      card.appendChild(head);
      var waiting = owner && Array.isArray(ls.pending) ? ls.pending : [];
      if (waiting.length) {
        pearlText(card, "kits-banner warn", ls.approved ? "New sources report as this loader:" : "This loader is not approved yet. These sources report as it:", false);
      }
      waiting.forEach(function (p) {
        var wait = kitEl("div", "pearl-row");
        var seen = p.lastSeen ? new Date(p.lastSeen).toLocaleTimeString() : "?";
        pearlText(wait, "", str(p.source) + (p.stash ? " (stash " + str(p.stash) + ")" : "") + " \u00b7 last seen " + seen + " ", true);
        pearlBtn(wait, "This is my loader", function () {
          if (!window.confirm(streamerOn() ? "Approve this source as the loader?" : "Approve " + str(p.source) + " as the loader " + str(s.loader && s.loader.player) + "? Trips will use its reports.")) return;
          pearlWrite("/api/v1/pearl/stations/approve", { id: s.id, source: p.source });
        }, pearlSet.writing);
        pearlBtn(wait, "Dismiss", function () {
          pearlWrite("/api/v1/pearl/stations/dismiss", { id: s.id, source: p.source });
        }, pearlSet.writing);
        card.appendChild(wait);
      });
      if (!waiting.length && owner && !ls.approved && ls.source) {
        pearlText(card, "hint", "Waiting for the loader to report.", false);
      }
      if (owner) {
        var al = kitEl("div", "pearl-row");
        pearlText(al, "hint", "Allowed travellers: ", false);
        (Array.isArray(s.allowed) ? s.allowed : []).forEach(function (a) {
          pearlText(al, "", str(a.name) + (a.uuid ? "" : " (no uuid)") + " ", true);
          pearlBtn(al, "Remove", function () {
            pearlWrite("/api/v1/pearl/allowed", { station: s.id, player: a.name, allowed: false });
          }, pearlSet.writing);
        });
        (Array.isArray(d.travellers) ? d.travellers : []).forEach(function (t) {
          var n = str(t.player).toLowerCase();
          var listed = (Array.isArray(s.allowed) ? s.allowed : []).some(function (a) { return a.name === n; });
          if (!t.supports || listed || t.player === undefined) return;
          var b = pearlBtn(al, "Allow " + str(t.player), function () {
            pearlWrite("/api/v1/pearl/allowed", { station: s.id, player: t.player, allowed: true });
          }, pearlSet.writing);
          b.setAttribute("data-sensitive", "");
        });
        card.appendChild(al);
      }
      (Array.isArray(s.chambers) ? s.chambers : []).forEach(function (c) {
        var line = kitEl("div", "pearl-chamber");
        pearlText(line, "", str(c.id) + " \u00b7 " + (c.ownerKnown ? str(c.owner) : "owner unknown"), true);
        pearlText(line, "hint", " \u00b7 " + str(c.state).replace("_", " "), false);
        if (owner && c.state === "broken") {
          pearlBtn(line, "Reset", function () {
            pearlWrite("/api/v1/pearl/chambers/reset", { station: s.id, chamber: c.id });
          }, pearlSet.writing);
        }
        if (canWrite && owner && c.ownerKnown) {
          var mine = s.assign && s.assign[str(c.owner).toLowerCase()] === c.id;
          var useBtn = pearlBtn(line, mine ? "Unassign" : "Use for " + str(c.owner), function () {
            pearlWrite("/api/v1/pearl/assign", { station: s.id, player: c.owner, chamber: mine ? null : c.id });
          }, pearlSet.writing);
          useBtn.setAttribute("data-sensitive", "");
        }
        card.appendChild(line);
      });
      sts.appendChild(card);
    });
    if (!stations.length) sts.appendChild(kitEl("div", "hint", owner ? "No station yet. Add one below: the loader bot's name and the dimension of its chambers." : "No station yet (the owner adds them)."));
    (Array.isArray(d.trips) ? d.trips : []).slice(0, 10).forEach(function (t) {
      var row = kitEl("div", "pearl-row");
      var to = t.params && t.params.route && t.params.route.legs && t.params.route.legs[0] ? pearlStationName(d, t.params.route.legs[0].station) : "a station";
      pearlText(row, "", (t.action === "back" ? "Back to " : "To ") + to + " \u00b7 " + str(t.scope && t.scope.stash), true);
      var why = t.error ? " (" + (PEARL_CODE_WORDS[t.error.code] || str(t.error.code)) + ")" : t.pauseReason ? " (" + (PEARL_CODE_WORDS[t.pauseReason] || str(t.pauseReason)) + ")" : "";
      pearlText(row, "hint", " \u00b7 " + (PEARL_STATE_WORDS[t.state] || str(t.state)) + why + (t.warning ? " \u00b7 check: " + str(t.warning).replace("_", " ") : ""), false);
      if (canWrite && (t.state === "pending" || t.state === "running" || t.state === "paused") && t.checkpoint && ["precheck", "arm_home", "ready"].indexOf(t.checkpoint.step) >= 0) {
        pearlBtn(row, "Stop", function () {
          pearlWrite("/api/v1/pearl/trips/stop", { id: t.id });
        }, pearlSet.writing);
      }
      trips.appendChild(row);
    });
    if (!trips.firstChild) trips.appendChild(kitEl("div", "hint", "No trips yet."));
  }

  pearlEl("add").addEventListener("click", function () {
    var name = pearlEl("name").value.trim();
    var loader = pearlEl("loader").value.trim();
    var home = pearlEl("home").value.trim();
    if (!name || !/^[A-Za-z0-9_]{3,16}$/.test(loader)) {
      toast("Give the station a name and the loader bot's player name (3 to 16 letters, digits or _).");
      return;
    }
    var servers = {};
    (pearlSet.data && Array.isArray(pearlSet.data.travellers) ? pearlSet.data.travellers : []).forEach(function (t) { servers[t.server] = true; });
    var list = Object.keys(servers);
    var s = currentStash();
    var server = s ? s.server : list[0];
    if (!server) {
      toast("Open a stash first: the station is registered on its server.");
      return;
    }
    pearlWrite("/api/v1/pearl/stations", { name: name, server: server, dim: pearlEl("dim").value, loader: { player: loader }, stash: home || null }, function () {
      pearlEl("name").value = "";
      pearlEl("loader").value = "";
      pearlEl("home").value = "";
    });
  });

  // ---------- stash area ----------
  // GET/POST /api/v1/area (AREA-CONTRACT.md): the box the bots keep the stash in. The draft lives only in the page (the six
  // fields) until Save. The numbers are positions: blurred in streamer mode; a user forced into streamer mode gets 403.

  var AREA_POLL_MS = 5000;
  var AREA_FIELDS = ["minx", "miny", "minz", "maxx", "maxy", "maxz"];
  var AREA_LIMITS = { maxW: 256, maxH: 128, maxD: 256, yMin: -64, yMax: 319 };
  var AREA_WHY = {
    busy: "The bot will apply the new area between tasks.",
    far: "The bot is more than 128 blocks from the new area, so it hasn't applied it yet. It does once it is nearer.",
    dimension: "The bot is in another dimension, so it hasn't applied the new area yet."
  };
  var areaSet = { timer: 0, loading: false, writing: false, token: 0, data: null, error: "", sig: "", dirty: false, mapOn: false };

  function areaEl(id) {
    return $("area-" + id);
  }

  /** Polled while the Kits tab or the Live page is on screen (the map draws the area). */
  function areaWanted() {
    var live = dash.page === "viewer" && vw && vw.open;
    return (kitsVisible() || live) && !document.hidden && !auth.blocked && !!currentStash() && !forcedStreamer();
  }

  function areaSetSync() {
    window.clearTimeout(areaSet.timer);
    areaSet.timer = 0;
    if (areaWanted()) areaSetPoll();
    else areaMapRender();
  }

  function areaSetReset() {
    areaSet.token++;
    areaSet.data = null;
    areaSet.error = "";
    areaSet.sig = "";
    areaSet.dirty = false;
    areaSet.mapOn = false;
    AREA_FIELDS.forEach(function (f) { areaEl(f).value = ""; });
    areaSetRender();
    areaSetSync();
  }

  function areaSetPoll() {
    if (areaSet.loading || areaSet.writing) return;
    var s = currentStash();
    if (!s) return;
    areaSet.loading = true;
    var token = areaSet.token;
    api("/api/v1/area?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== areaSet.token) return;
      areaSet.data = d && typeof d === "object" ? d : null;
      areaSet.error = "";
    }, function (e) {
      if (token !== areaSet.token) return;
      areaSet.data = null;
      areaSet.error = areaError(e, "read");
    }).then(function () {
      areaSet.loading = false;
      if (token !== areaSet.token) {
        areaSetSync();
        return;
      }
      areaSetRender();
      window.clearTimeout(areaSet.timer);
      areaSet.timer = areaWanted() ? window.setTimeout(areaSetPoll, AREA_POLL_MS) : 0;
    });
  }

  function areaError(e, what) {
    var b = e && e.body && typeof e.body === "object" ? e.body : {};
    if (e && e.status === 403 && b.permission === "streamer") return "The stash area is hidden while streamer mode is forced on for your account, because it shows positions.";
    if (e && e.status === 403) return what === "read" ? "You don't have permission to see the stash area." : "Changing the stash area needs the “settings” permission.";
    if (e && e.status === 404) return "That stash isn't available.";
    if (e && e.status === 409 && /changed meanwhile/.test(str(b.error))) return "The area changed meanwhile (on the website or in game). Your draft is kept: check it against the new area and save again.";
    if (e && e.status === 429) return "Too many changes. Wait " + num(b.retryAfter, 1) + " s.";
    if (e && e.status === 503) return "The server can't read its stash areas (area.json is damaged). Fix or remove the file and restart the server.";
    if (e && e.network) return "Can't reach the server. Trying again.";
    if (typeof b.error === "string" && b.error) return safeServerText(sentence(b.error), "The server refused that (details are hidden in streamer mode).");
    return what === "read" ? "Couldn't load the stash area." : "Couldn't save that.";
  }

  function areaBoxOf(a) {
    return a && typeof a === "object" && Array.isArray(a.box) && a.box.length === 6 && a.box.every(function (v) { return typeof v === "number"; }) ? a.box : null;
  }

  /** The six fields: null when all are empty, a box when they are six whole numbers, "bad" otherwise. */
  function areaSetRead() {
    var raw = AREA_FIELDS.map(function (f) { return areaEl(f).value.trim(); });
    if (raw.every(function (v) { return v === ""; })) return null;
    var out = [];
    for (var i = 0; i < 6; i++) {
      if (!/^-?[0-9]{1,8}$/.test(raw[i])) return "bad";
      out.push(parseInt(raw[i], 10));
    }
    return out;
  }

  function areaSetWrite(box) {
    AREA_FIELDS.forEach(function (f, i) { areaEl(f).value = box ? String(box[i]) : ""; });
  }

  /** The Y range of a dimension (the contract's table). */
  function areaYRange(dim) {
    var d = str(dim).replace(/^minecraft:/, "");
    return d === "the_nether" || d === "the_end" ? [0, 255] : [AREA_LIMITS.yMin, AREA_LIMITS.yMax];
  }

  /** Why a box can't be saved (a sentence), or "". Pure: the same checks the server makes. */
  function areaCheck(box, dim) {
    if (box[0] > box[3] || box[1] > box[4] || box[2] > box[5]) return "Each minimum must be at most its maximum.";
    if ([box[0], box[2], box[3], box[5]].some(function (v) { return Math.abs(v) > 30000000; })) return "X and Z must be within 30,000,000 blocks of the origin.";
    var w = box[3] - box[0] + 1;
    var h = box[4] - box[1] + 1;
    var d = box[5] - box[2] + 1;
    if (w > AREA_LIMITS.maxW || h > AREA_LIMITS.maxH || d > AREA_LIMITS.maxD) {
      return "That area is " + w + "×" + h + "×" + d + "; the largest is " + AREA_LIMITS.maxW + "×" + AREA_LIMITS.maxH + "×" + AREA_LIMITS.maxD + ".";
    }
    var y = areaYRange(dim);
    if (box[1] < y[0] || box[4] > y[1]) return "The height must be from " + y[0] + " to " + y[1] + " in this dimension.";
    return "";
  }

  /**
   * Expands (or, with a negative n, shrinks) a box by n on a side ("all", "-x", "+x", "-z", "+z", "down", "up"). Shrinking
   * never goes below one block on an axis. Returns a new box.
   */
  function areaExpand(box, side, n) {
    var b = box.slice();
    var all = side === "all";
    if (all || side === "-x") b[0] = Math.min(b[0] - n, b[3]);
    if (all || side === "+x") b[3] = Math.max(b[3] + n, b[0]);
    if (all || side === "down") b[1] = Math.min(b[1] - n, b[4]);
    if (all || side === "up") b[4] = Math.max(b[4] + n, b[1]);
    if (all || side === "-z") b[2] = Math.min(b[2] - n, b[5]);
    if (all || side === "+z") b[5] = Math.max(b[5] + n, b[2]);
    return b;
  }

  function areaSetCan() {
    return kitsCan() && !areaSet.writing;
  }

  function areaDim() {
    var d = areaSet.data;
    // The server answers the namespaced form; a bare name is read as minecraft:<name>.
    if (!d || typeof d.dimension !== "string" || !d.dimension) return "";
    return d.dimension.indexOf(":") < 0 ? "minecraft:" + d.dimension : d.dimension;
  }

  function areaSetUpdate() {
    var d = areaSet.data;
    var cur = areaSetRead();
    var stored = d ? areaBoxOf(d.area) : null;
    var dim = areaDim();
    var msg = areaEl("msg");
    var problem = "";
    if (cur === "bad") problem = "Check the numbers: whole numbers only.";
    else if (cur) problem = areaCheck(cur, dim);
    var same = cur !== "bad" && (cur ? !!stored && stored.join(",") === cur.join(",") : !stored);
    var size = areaEl("size");
    if (cur && cur !== "bad") size.textContent = (cur[3] - cur[0] + 1) + " × " + (cur[4] - cur[1] + 1) + " × " + (cur[5] - cur[2] + 1) +
      " blocks (the largest is " + AREA_LIMITS.maxW + " × " + AREA_LIMITS.maxH + " × " + AREA_LIMITS.maxD + ")";
    else size.textContent = cur === "bad" ? "" : "No area: the bot works around itself.";
    var canSave = !!d && cur !== "bad" && !problem && !same && areaSetCan() && (cur === null || !!dim);
    areaEl("save").disabled = !canSave;
    areaEl("cancel").disabled = !areaSet.dirty || areaSet.writing;
    var bot = d && d.bot && typeof d.bot === "object" ? d.bot : {};
    var cu = areaBoxOf(bot.applied) || (Array.isArray(bot.derived) && bot.derived.length === 6 ? bot.derived : null);
    areaEl("bots").disabled = !d || !cu || !areaSetCan();
    areaEl("clear").disabled = !d || !cur || !areaSetCan();
    ["expand", "n", "side"].forEach(function (f) { areaEl(f).disabled = !d || !areaSetCan(); });
    AREA_FIELDS.forEach(function (f) { areaEl(f).disabled = !d || !areaSetCan(); });
    if (!d) msg.textContent = "";
    else if (problem) msg.textContent = problem;
    else if (cur && !dim) msg.textContent = "This stash's dimension isn't known yet (its bot has not sent an index), so an area can't be saved.";
    else if (areaSet.dirty && !same) msg.textContent = "Not saved yet." + (bot.supports === true ? " If the bot's area is changed in game before you save, the later change wins." : "");
    else msg.textContent = cur ? "This is the saved area." : "Type the box's corners, use the bot's current area, or drag on the Live map.";
    areaMapRender();
  }

  function areaSetFields(d) {
    if (!d || areaSet.dirty) return;
    areaSetWrite(areaBoxOf(d.area));
  }

  function areaTimeText(d) {
    var at = typeof d.updatedAt === "number" ? ago(d.updatedAt) : "";
    if (!d.by) return "No area is saved on the server.";
    if (d.by === "website") return "Set on the website" + (str(d.user) ? " by " + str(d.user) : "") + (at ? ", " + at : "") + ".";
    return "Changed in game" + (at ? ", " + at : "") + ".";
  }

  function areaSetBanner(box, d) {
    var bot = d.bot && typeof d.bot === "object" ? d.bot : {};
    var notes = [];
    if (bot.supports === null || bot.supports === undefined) {
      notes.push(["", "Waiting for the bot to report its area… (it does with its next heartbeats)"]);
    } else if (bot.supports !== true) {
      notes.push(["warn", "This bot will keep its own area until it is updated. Your area is saved and applies once it can take it."]);
    } else {
      if (bot.online !== true) notes.push(["warn", "The bot is offline. It picks the area up when it is back."]);
      if (bot.pending && typeof bot.pending === "object" && AREA_WHY[str(bot.pending.why)]) notes.push(["", AREA_WHY[str(bot.pending.why)]]);
      else if (bot.synced === true && d.area) notes.push(["ok", "The bot uses this area."]);
      else if (bot.synced !== true && bot.online === true) notes.push(["", "The bot hasn't picked up the latest area yet. This takes a few seconds."]);
      var out = num(bot.containersOutside, 0);
      if (out > 0) notes.push(["warn", plural(grouped(out), "container") + " the bot has read lie outside its current area."]);
    }
    var imp = d.impact && typeof d.impact === "object" ? d.impact : null;
    if (imp && num(imp.outside, 0) > 0) notes.push(["warn", plural(grouped(num(imp.outside, 0)), "reported container") + " of " + grouped(num(imp.reported, 0)) + " would be outside the saved area."]);
    notes.push(["", areaTimeText(d)]);
    if (d.homeStale === true) notes.push(["warn", "The stash's dimension changed since its home point was set, so no new area can be saved until the owner resets the home point."]);
    else if (d.homeSource === "bot") notes.push(["", "Home point from the bot's first report. It moves once, to the delivery container, when the bot has one."]);
    else if (d.homeSource === "delivery") notes.push(["", "Home point at the delivery container. New areas must be within " + num(d.limits && d.limits.farBlocks, 256) + " blocks of it."]);
    if (!kitsCan()) notes.push(["", "You can look, but changing the stash area needs the “settings” permission."]);
    box.replaceChildren();
    notes.forEach(function (n) { kitNote(box, n[1], n[0]); });
    box.hidden = !notes.length;
  }

  function areaSetRender() {
    if (!areaEl("banner")) return;
    var d = areaSet.data;
    $("area-set").hidden = forcedStreamer();
    var sig = JSON.stringify([d, areaSet.error, areaSet.writing, areaSet.dirty, kitsCan(), streamerOn(), !!currentStash(), Math.floor(serverNow() / 15000)]);
    if (sig === areaSet.sig) return;
    areaSet.sig = sig;
    var banner = areaEl("banner");
    if (!currentStash()) {
      banner.replaceChildren();
      kitNote(banner, "No stash selected.");
      banner.hidden = false;
    } else if (!d) {
      banner.replaceChildren();
      kitNote(banner, areaSet.error || "Loading…", areaSet.error ? "off" : "");
      banner.hidden = false;
    } else {
      areaSetBanner(banner, d);
    }
    areaSetFields(d);
    areaEl("dim").value = d && d.dimension ? str(d.dimension) : "";
    areaSetUpdate();
  }

  function areaSetDraft(box) {
    areaSetWrite(box);
    areaSet.dirty = true;
    areaSet.sig = "";
    areaSetUpdate();
  }

  function areaWrite(area) {
    var s = currentStash();
    if (!s || areaSet.writing) return;
    areaSet.writing = true;
    var token = ++areaSet.token;
    areaSet.sig = "";
    areaSetRender();
    var body = { server: s.server, stash: s.stash, area: area };
    if (areaSet.data && typeof areaSet.data.rev === "number") body.rev = areaSet.data.rev; // refused when someone else saved meanwhile
    api("/api/v1/area", { method: "POST", body: body }).then(function (d) {
      if (token !== areaSet.token) return;
      areaSet.data = d && typeof d === "object" ? d : areaSet.data;
      areaSet.error = "";
      areaSet.dirty = false;
      toast("Saved. The bot applies it between tasks.");
    }, function (e) {
      toast(areaError(e, "write"));
    }).then(function () {
      areaSet.writing = false;
      areaSet.token++;
      areaSet.sig = "";
      areaSetRender();
      areaSetSync();
    });
  }

  function areaSave() {
    var cur = areaSetRead();
    if (cur === "bad") return;
    if (cur === null) areaWrite(null);
    else areaWrite({ box: cur, dimension: areaDim() });
  }

  function areaCancel() {
    areaSet.dirty = false;
    areaSet.sig = "";
    areaSetRender();
  }

  AREA_FIELDS.forEach(function (f) {
    areaEl(f).addEventListener("input", function () {
      areaSet.dirty = true;
      areaSetUpdate();
    });
  });
  areaEl("save").addEventListener("click", areaSave);
  areaEl("cancel").addEventListener("click", areaCancel);
  areaEl("clear").addEventListener("click", function () { areaSetDraft(null); });
  areaEl("bots").addEventListener("click", function () {
    var bot = areaSet.data && areaSet.data.bot && typeof areaSet.data.bot === "object" ? areaSet.data.bot : {};
    var box = areaBoxOf(bot.applied) || (Array.isArray(bot.derived) && bot.derived.length === 6 ? bot.derived : null);
    if (box) areaSetDraft(box.slice());
  });
  areaEl("expand").addEventListener("click", function () {
    var cur = areaSetRead();
    if (!cur || cur === "bad") {
      areaEl("msg").textContent = "Start with an area: use the bot's current area or type the corners.";
      return;
    }
    var raw = areaEl("n").value.trim();
    if (!/^-?[0-9]{1,3}$/.test(raw) || Math.abs(parseInt(raw, 10)) > 255) {
      areaEl("msg").textContent = "Expand by a whole number from -255 to 255 (negative shrinks).";
      return;
    }
    var next = areaExpand(cur, areaEl("side").value, parseInt(raw, 10));
    var why = areaCheck(next, areaDim());
    if (why) {
      areaEl("msg").textContent = why + " The draft is unchanged.";
      return;
    }
    areaSetDraft(next);
  });

  // ---------- stash area on the live map ----------

  var areaDrag = { start: null, now: null };

  /** The map may show the area when the stash's area is loaded and the viewer's world is that dimension. */
  function areaMapDim() {
    var d = areaSet.data;
    if (!d || !vw || !vw.world || streamerOn()) return false;
    var a = str(d.dimension).replace(/^minecraft:/, "");
    var w = str(vw.world.dim).replace(/^minecraft:/, "");
    // Only when the viewer shows this stash's own bot.
    var b = currentBot();
    var s = currentStash();
    return !!a && a === w && !!b && !!s && !!str(s.player) && str(b.player) === str(s.player);
  }

  function areaMapActive() {
    return areaSet.mapOn && areaMapDim() && kitsCan();
  }

  function areaMapRender() {
    if (!vw || !vel || !vel.areaBtn) return;
    var show = areaMapDim() && kitsCan();
    vel.areaBtn.hidden = !show;
    if (!show) areaSet.mapOn = false;
    vel.areaBtn.setAttribute("aria-pressed", areaSet.mapOn ? "true" : "false");
    var cur = areaSetRead();
    var box = show && areaSet.dirty && cur && cur !== "bad" ? cur : null;
    vel.areaBox.hidden = !(show && (areaSet.mapOn || areaSet.dirty));
    if (!vel.areaBox.hidden) {
      vel.areaText.textContent = box ? "Area draft " + (box[3] - box[0] + 1) + "×" + (box[4] - box[1] + 1) + "×" + (box[5] - box[2] + 1) + (areaCheck(box, areaDim()) ? " — " + areaCheck(box, areaDim()) : "")
        : areaSet.mapOn ? "Drag a rectangle on the map to set the area's X and Z." : "";
      vel.areaSave.disabled = !areaSet.dirty || !!areaEl("save").disabled;
      vel.areaCancel.disabled = !areaSet.dirty;
    }
    if (vDirty) vDirty();
  }

  function areaMapBlock(e) {
    var p = vLocal(e);
    return { x: Math.floor(vw.cx + (p.x - vw.w / 2) / vw.zoom), z: Math.floor(vw.cz + (p.y - vw.h / 2) / vw.zoom) };
  }

  function areaMapDown(e) {
    vel.canvas.setPointerCapture(e.pointerId);
    areaDrag.start = areaMapBlock(e);
    areaDrag.now = areaDrag.start;
    vDirty();
  }

  function areaMapMove(e) {
    areaDrag.now = areaMapBlock(e);
    vDirty();
  }

  function areaMapUp(e) {
    var a = areaDrag.start;
    var b = areaMapBlock(e);
    areaDrag.start = null;
    areaDrag.now = null;
    if (Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z)) < 2) {
      vDirty();
      return; // a click, not a drag
    }
    var cur = areaSetRead();
    var y0;
    var y1;
    if (cur && cur !== "bad") {
      y0 = cur[1];
      y1 = cur[4];
    } else {
      var st = areaBoxOf(areaSet.data && areaSet.data.area);
      var bot = areaSet.data && areaSet.data.bot ? areaSet.data.bot : {};
      var base = st || areaBoxOf(bot.applied) || (Array.isArray(bot.derived) && bot.derived.length === 6 ? bot.derived : null);
      var py = vw.self ? Math.floor(num(vw.self.y, 64)) : 64;
      y0 = base ? base[1] : Math.max(areaYRange(areaDim())[0], py - 16);
      y1 = base ? base[4] : Math.min(areaYRange(areaDim())[1], py + 32);
    }
    var box = [Math.min(a.x, b.x), y0, Math.min(a.z, b.z), Math.max(a.x, b.x), y1, Math.max(a.z, b.z)];
    areaSetDraft(box);
  }

  /** Draws the saved area and the draft (and the rectangle being dragged) on the live map. */
  function areaMapDraw(ctx, toX, toY, dpr, c) {
    if (!areaMapDim()) return;
    var rect = function (box, color, dash, fill) {
      var x0 = toX(box[0]);
      var y0 = toY(box[2]);
      var x1 = toX(box[3] + 1);
      var y1 = toY(box[5] + 1);
      ctx.save();
      ctx.lineWidth = 2 * dpr;
      ctx.strokeStyle = color;
      ctx.setLineDash(dash ? [6 * dpr, 4 * dpr] : []);
      if (fill) {
        ctx.globalAlpha = 0.12;
        ctx.fillStyle = color;
        ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
        ctx.globalAlpha = 1;
      }
      ctx.strokeRect(x0, y0, x1 - x0, y1 - y0);
      ctx.restore();
    };
    var stored = areaBoxOf(areaSet.data && areaSet.data.area);
    if (stored) rect(stored, c.accent, false, true);
    var cur = areaSetRead();
    if (areaSet.dirty && cur && cur !== "bad") rect(cur, c.item, true, true);
    if (areaDrag.start && areaDrag.now) {
      var a = areaDrag.start;
      var b = areaDrag.now;
      rect([Math.min(a.x, b.x), 0, Math.min(a.z, b.z), Math.max(a.x, b.x), 0, Math.max(a.z, b.z)], c.item, true, true);
    }
  }

  function craftNeverAdd() {
    var d = craftSet.data;
    var input = craftEl("never-add");
    var raw = input.value.trim().toLowerCase().replace(/\s+/g, "_");
    if (!d || !raw) return;
    var id = raw.indexOf(":") < 0 ? "minecraft:" + raw : raw;
    if (!CRAFT_ID.test(id)) {
      toast("That isn't an item. Use a name like iron_block or minecraft:iron_block.");
      return;
    }
    var list = Array.isArray(d.neverUse) ? d.neverUse.filter(function (v) { return typeof v === "string"; }) : [];
    if (list.indexOf(id) >= 0) {
      toast("That item is already on the list.");
      return;
    }
    if (list.length >= CRAFT_MAX_NEVER) {
      toast("The list is full (" + CRAFT_MAX_NEVER + " items).");
      return;
    }
    list.push(id);
    craftWrite("/api/v1/craft/never-use", { ids: list }, function () { input.value = ""; });
  }

  craftEl("never-btn").addEventListener("click", craftNeverAdd);
  craftEl("never-add").addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      e.preventDefault();
      craftNeverAdd();
    }
  });

  // ---------- kits ----------
  // The Kits tab (KIT-CONTRACT v2): the type chests and the general kits chest, every kit the bots found grouped by type
  // with the reason it is one, the named boxes that are not kits, and (for older bots only) the v1 scoring. The bots
  // classify boxes; the server computes the rows, chains and statuses; this only draws them. Polled every 5 s while the
  // tab (or the Kit Builder) is open.

  var KIT_POLL_MS = 5000;
  var KIT_MAX_ROWS = 500;
  var kits = { timer: 0, loading: false, token: 0, data: null, error: "", writing: false, sig: "" };
  // The Kit Builder's state (templates body, the open editor); declared here because the Kits tab reads its categories.
  var kb = { timer: 0, loading: false, token: 0, data: null, error: "", writing: false, sig: "", ed: null, ui: null, qty: {}, cartTried: null };
  var KIT_STATUS = {
    pending: ["Waiting for the sorter", ""],
    home: ["In place", "ok"],
    full: ["Target full", "warn"],
    missing: ["Target missing", "off"],
    none: ["Normal sorting", ""],
    absent: ["None in the stash", ""]
  };
  // The seven kit types of the contract (fixed, in tie-break order). noun is the word the reason texts use.
  var KIT_TYPES = [
    { id: "pvp", name: "PvP / Crystal", noun: "PvP", icon: "minecraft:end_crystal", what: "Armour, weapons, totems, crystals, obsidian" },
    { id: "travel", name: "Travel", noun: "travel", icon: "minecraft:elytra", what: "Elytra, rockets, pearls, gunpowder" },
    { id: "redstone", name: "Redstone", noun: "redstone", icon: "minecraft:redstone", what: "Redstone parts, pistons, rails, TNT" },
    { id: "farming", name: "Farming / Food", noun: "farming or food", icon: "minecraft:wheat", what: "Cooked food, crops, seeds, bone meal" },
    { id: "building", name: "Building", noun: "building", icon: "minecraft:bricks", what: "Concrete, wool, glass, terracotta, dyes" },
    { id: "starter", name: "Starter / Base", noun: "starter", icon: "minecraft:crafting_table", what: "Basic tools, armour, a crafting table, a bed" },
    { id: "other", name: "Other kit", noun: "collectible", icon: "minecraft:nether_star", what: "Valuables: beacons, books, heads, discs" }
  ];
  var KIT_SOURCE_WORDS = { rule: "its own chest", template: "the kit's chest", typeChest: "type chest", category: "type chest", kitsChest: "kits chest" };
  var KIT_TYPE_WHY_CODES = { few_kinds: true, mixed: true, no_purpose: true, override_none: true };
  var KIT_TYPE_SOURCE = {
    override: "Set by hand",
    template: "Kit Builder kit",
    template_incomplete: "Incomplete Kit Builder kit"
  };

  function kitEl(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function kitsVisible() {
    return state.tab === "kits" && dash.page === "stash" && !document.hidden && !auth.blocked && !!currentStash() && !forcedStreamer();
  }

  /** The kits body is read while the Kits tab or the Kit Builder is open (the Builder needs the chests and the types). */
  function kitsDataWanted() {
    return (state.tab === "kits" || state.tab === "kitbuilder") && dash.page === "stash" && !document.hidden && !auth.blocked &&
      !!currentStash() && !forcedStreamer();
  }

  /** Starts or stops the polling to match what is on screen. */
  function kitsSync() {
    window.clearTimeout(kits.timer);
    kits.timer = 0;
    if (kitsDataWanted()) kitsPoll();
    craftSetSync();
    areaSetSync();
    repairSync();
    pearlSync();
  }

  function kitsReset() {
    kits.scoringSnap = null;
    kits.token++;
    kits.data = null;
    kits.error = "";
    kits.sig = "";
    kitsRender();
    kitsSync();
    craftSetReset();
    areaSetReset();
    repairReset();
    pearlReset();
  }

  function kitsPoll() {
    if (kits.loading || kits.writing) return;
    var s = currentStash();
    if (!s) return;
    kits.loading = true;
    var token = kits.token;
    api("/api/v1/kits?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== kits.token) return;
      kits.data = d && typeof d === "object" ? d : null;
      kits.error = "";
    }, function (e) {
      if (token !== kits.token) return;
      kits.data = null;
      if (e && e.status === 503) kits.error = "The server can't read its kit settings (kits.json is damaged), so kit sorting is off. Fix or remove the file and restart the server.";
      else if (e && e.status === 404) kits.error = "That stash isn't available.";
      else if (e && e.status === 403) kits.error = "Kit settings are hidden in streamer mode (enforced by the owner).";
      else kits.error = "Can't reach the server. Trying again.";
    }).then(function () {
      kits.loading = false;
      if (token !== kits.token) {
        // The stash changed or a write was made while this poll ran: its answer was dropped, so ask again now.
        kitsSync();
        return;
      }
      kitsRender();
      if (state.tab === "kitbuilder") kbRender();
      window.clearTimeout(kits.timer);
      kits.timer = kitsDataWanted() ? window.setTimeout(kitsPoll, KIT_POLL_MS) : 0;
    });
  }

  function kitsCan() {
    return can("settings") && !forcedStreamer();
  }

  /** "x, y, z" for an id like "120,64,-33". */
  function kitCoords(id) {
    return str(id).split(",").join(", ");
  }

  function kitContainer(id) {
    var list = kits.data && Array.isArray(kits.data.containers) ? kits.data.containers : [];
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  /** The label and position of a container, as one hidden (streamer-blurred) text. */
  function kitPlace(id) {
    if (id === "hidden") return hiddenSpan("hidden");
    var c = kitContainer(id);
    var label = c && str(c.label) ? str(c.label) + " (" + kitCoords(id) + ")" : kitCoords(id);
    return hiddenSpan(label);
  }

  /** The text of a picker option; in streamer mode without label and position (a native option can't be blurred). */
  function kitOptionText(c, index) {
    var free = num(c.free, 0) + " free";
    if (streamerOn()) return "Chest " + (index + 1) + " · " + free;
    var where = kitCoords(c.id);
    return (str(c.label) ? str(c.label) + " (" + where + ")" : where) + " · " + free;
  }

  var KIT_WORD = /(^|[^\p{L}\p{M}])kits?($|[^\p{L}\p{M}])/iu;

  function kitNote(parent, text, kind) {
    var d = document.createElement("div");
    d.className = "kits-note" + (kind ? " " + kind : "");
    d.textContent = text;
    parent.appendChild(d);
    return d;
  }

  // ----- categories, item names and the reason texts (the wording is the contract's) -----

  /** The custom categories known to the page: from the kits body, else the Kit Builder's. [{ id, name }] */
  function kitCustomCats() {
    var out = [];
    var seen = {};
    var add = function (id, name) {
      if (typeof id === "string" && !seen[id] && !KIT_TYPES.some(function (t) { return t.id === id; })) {
        seen[id] = true;
        out.push({ id: id, name: str(name) || id });
      }
    };
    if (kits.data && Array.isArray(kits.data.types)) kits.data.types.forEach(function (t) { if (t && t.custom === true) add(t.id, t.name); });
    if (kb.data && Array.isArray(kb.data.categories)) kb.data.categories.forEach(function (c) { if (c) add(c.id, c.name); });
    return out;
  }

  /** All categories in order: the seven types, then the custom ones. [{ id, name, custom }] */
  function kitAllCats() {
    return KIT_TYPES.map(function (t) { return { id: t.id, name: t.name, custom: false }; })
      .concat(kitCustomCats().map(function (c) { return { id: c.id, name: c.name, custom: true }; }));
  }

  function kitCatName(id) {
    var all = kitAllCats();
    for (var i = 0; i < all.length; i++) if (all[i].id === id) return all[i].name;
    return str(id);
  }

  function kitCatNoun(id) {
    for (var i = 0; i < KIT_TYPES.length; i++) if (KIT_TYPES[i].id === id) return KIT_TYPES[i].noun;
    return kitCatName(id).toLowerCase();
  }

  var kbNames = { src: null, byId: new Map(), matchSrc: null, match: new Map() };

  /** { name, maxStack } of an item id as the stash index knows it, or null. */
  function kbIdInfo(id) {
    if (kbNames.src !== state.items) {
      kbNames.src = state.items;
      kbNames.byId = new Map();
      state.items.forEach(function (e) {
        var cur = kbNames.byId.get(e.item.id);
        // The plain stack's name is the item's usual name; a variant may carry a longer one.
        if (!cur || (e.item.key.indexOf("#") < 0 && cur.variant)) {
          kbNames.byId.set(e.item.id, { name: e.item.name, maxStack: e.item.maxStack, variant: e.item.key.indexOf("#") >= 0 });
        }
      });
    }
    return kbNames.byId.get(id) || null;
  }

  /** The page's usual name for an item id, else the path with spaces ("end crystal"). */
  function kitPlainName(id) {
    var info = kbIdInfo(str(id));
    return info ? info.name : splitId(str(id))[1].replace(/_/g, " ");
  }

  /** A name for a slot or item key: "Totem of Undying", "Netherite Sword" for an "any variant" key too. */
  function kbItemLabel(key) {
    key = str(key);
    var i = key.indexOf("#");
    var base = i < 0 ? key : key.slice(0, i);
    var exact = i >= 0 && key.charAt(i + 1) !== "*" ? state.byKey.get(key) : null;
    if (exact) return exact.item.name;
    var info = kbIdInfo(base);
    return info ? info.name : plPretty(base);
  }

  /** The reason text of a why object as parts: strings, and { h } for a kit name (blurred in streamer mode). */
  function kitReasonParts(why, tplName) {
    if (!why || typeof why !== "object") return [];
    var T = why.type ? kitCatName(why.type) : "";
    var noun = why.type ? kitCatNoun(why.type) : "";
    var items = (Array.isArray(why.items) ? why.items : []).map(kitPlainName);
    var item = items[0] || "";
    var slots = num(why.slots, 0);
    var covered = num(why.covered, 0);
    var kinds = num(why.kinds, 0);
    var words = Array.isArray(why.words) ? why.words : [];
    var assort = T + " kit: " + covered + " of " + slots + " slots are " + noun + " items, " + kinds + " kinds (mostly " + items.join(", ") + ").";
    switch (str(why.code)) {
      case "assortment": return [assort];
      case "named_assortment": return [assort + (words.length ? " The name says “" + str(words[0]) + "”, so half is enough." : "")];
      case "override": return [T + " kit: set by hand for this name."];
      case "template": return [T + " kit: matches the kit “", { h: tplName || str(why.template) }, "”."];
      case "template_incomplete": {
        var miss = (Array.isArray(why.missing) ? why.missing : []).map(function (m) { return kbItemLabel(str(m && m.key)) + " ×" + num(m && m.count, 0); });
        return [T + " kit: an incomplete “", { h: tplName || str(why.template) }, "” (short " + miss.join(", ") +
          "). It is kept and topped up, but not delivered until complete."];
      }
      case "empty": return ["Not a kit: the box is empty."];
      case "stock":
      case "stock_neutral": return ["Not a kit: it only holds " + item + ". A box of one kind of item is stock and goes to the " + item + " chest."];
      case "no_purpose": return ["Not a kit: none of its items has a kit purpose."];
      case "few_kinds": return ["Not a kit: " + covered + " of " + slots + " slots are " + noun + " items, but only " + kinds + " kind(s). A " + T +
        " kit needs " + num(why.minKinds, 0) + "."];
      case "mixed": return ["Not a kit: no common purpose. The closest is " + T + ", with " + covered + " of " + slots + " slots; a kit needs " +
        num(why.need, 0) + "."];
      case "override_none": return ["Not a kit: set by hand for this name."];
      default: return [];
    }
  }

  function kitReasonNode(why, tplName) {
    var parts = kitReasonParts(why, tplName);
    if (!parts.length) return null;
    return kitLineNode("kit-why", parts);
  }

  /** The reasons things may not work yet, from the server's view of the bot. */
  function kitsBanner(box, d) {
    var bot = d.bot && typeof d.bot === "object" ? d.bot : {};
    var notes = [];
    if (bot.supports !== true) {
      notes.push(["warn", "This stash's bot doesn't know about kits yet (update the plugin or addon). Your settings are saved and apply once it does."]);
    } else {
      if (bot.online !== true) notes.push(["warn", "The bot is offline. It picks the settings up when it is back."]);
      else if ((bot.types === true ? bot.typesSynced : bot.synced) !== true) notes.push(["", "The bot hasn't picked up the latest settings yet. This takes a few seconds."]);
      if (bot.types !== true) {
        notes.push(["warn", "This bot is too old for kit types: until it's updated, kits go only to the general kits chest or their own chest, and the type chests may get other items."]);
      }
    }
    if (!d.report) notes.push(["warn", "Waiting for the bot's report."]);
    if (!kitsCan()) notes.push(["", "You can look, but changing kit settings needs the “settings” permission."]);
    box.replaceChildren();
    notes.forEach(function (n) { kitNote(box, n[1], n[0]); });
    box.hidden = !notes.length;
  }

  function kitOption(value, text) {
    var o = document.createElement("option");
    o.value = value;
    o.textContent = text;
    return o;
  }

  /** The containers a kit may go to, as <option>s, plus the current value when it is no longer one of them. */
  function kitOptions(select, current, first) {
    select.replaceChildren();
    if (first) select.appendChild(kitOption("", first));
    var list = kits.data && Array.isArray(kits.data.containers) ? kits.data.containers : [];
    var seen = false;
    list.forEach(function (c, i) {
      if (c.pickable !== true && c.id !== current) return;
      select.appendChild(kitOption(c.id, kitOptionText(c, i) + (c.pickable !== true ? " · can't take kits" : "")));
      if (c.id === current) seen = true;
    });
    if (current && !seen) select.appendChild(kitOption(current, (streamerOn() || current === "hidden" ? "Chest" : kitCoords(current)) + " · not reported"));
    select.value = current || "";
  }

  /** The category <option>s of a "type" picker: first (auto), the seven types, the custom categories, then optionally "not a kit". */
  function kitTypeOptions(select, current, autoText, withNone) {
    select.replaceChildren();
    select.appendChild(kitOption("", autoText));
    kitAllCats().forEach(function (c) { select.appendChild(kitOption(c.id, c.name)); });
    if (withNone) select.appendChild(kitOption("none", "Not a kit"));
    if (current && !Array.prototype.some.call(select.options, function (o) { return o.value === current; })) select.appendChild(kitOption(current, kitCatName(current)));
    select.value = current || "";
  }

  // ----- the chests card -----

  var KIT_CHEST_STATUS = {
    unset: ["Not set", ""],
    ok: ["Ready", "ok"],
    full: ["Full", "warn"],
    missing: ["Missing", "off"]
  };

  function kitChestLine(o) {
    var row = kitEl("div", "kits-line");
    var head = kitEl("div", "kits-line-head");
    head.appendChild(iconImg(o.icon, 24));
    var tx = kitEl("div", "kits-line-text");
    tx.appendChild(kitEl("div", "kits-line-name", o.name));
    tx.appendChild(kitEl("div", "hint", o.what));
    head.appendChild(tx);
    row.appendChild(head);
    var ctl = kitEl("div", "kits-line-ctl");
    var sel = document.createElement("select");
    sel.className = "mc-select";
    sel.setAttribute("aria-label", "Chest for " + o.name);
    kitOptions(sel, o.current, o.current ? "" : "Pick a chest…");
    sel.disabled = !o.can || !o.report;
    sel.addEventListener("change", function () {
      var v = sel.value;
      sel.blur();
      o.onPick(v);
    });
    ctl.appendChild(sel);
    var clear = kitEl("button", "mc-button small", "Clear");
    clear.type = "button";
    clear.disabled = !o.can || !o.current;
    clear.addEventListener("click", function () { o.onClear(); });
    ctl.appendChild(clear);
    var st = o.report || !o.current ? KIT_CHEST_STATUS[o.status] || [str(o.status), ""] : ["Waiting for the bot's report", ""];
    ctl.appendChild(kitEl("span", "status-chip kit-pill" + (st[1] ? " " + st[1] : ""), st[0]));
    ctl.appendChild(kitEl("span", "kits-line-count", o.count + (o.count === 1 ? " kit" : " kits")));
    row.appendChild(ctl);
    if (o.extra) row.appendChild(o.extra);
    return row;
  }

  /** The "signed for other items" check before a chest becomes kit-only. True when the pick may go on. */
  function kitChestSignOk(v) {
    var c = kitContainer(v);
    if (v && c && str(c.label) && !KIT_WORD.test(str(c.label))) {
      return window.confirm((streamerOn() ? "This chest is signed for other items. " : "This chest is signed “" + str(c.label) + "”. ") +
        "As a kit chest it only takes kits; nothing else is sorted into it.\n\nUse it anyway?");
    }
    return true;
  }

  function kitsRenderChests(box, d, can_) {
    var frag = document.createDocumentFragment();
    var types = Array.isArray(d.types) ? d.types : [];
    var known = {};
    types.forEach(function (t) { known[t.id] = t; });
    var cats = KIT_TYPES.map(function (t) { return { id: t.id, name: t.name, what: t.what, icon: t.icon, custom: false }; });
    types.forEach(function (t) {
      if (t.custom === true) cats.push({ id: t.id, name: str(t.name) || t.id, what: "Kits you put in this category yourself", icon: "minecraft:chest", custom: true });
    });
    cats.forEach(function (c) {
      var t = known[c.id] || {};
      var extra = null;
      if (c.custom) {
        extra = kitEl("div", "kits-line-ctl");
        var rn = kitEl("button", "mc-button small", "Rename category");
        rn.type = "button";
        rn.disabled = !can_;
        rn.addEventListener("click", function () { kitRenameCategory(c.id, c.name); });
        var del = kitEl("button", "mc-button small danger", "Delete category");
        del.type = "button";
        del.disabled = !can_;
        del.addEventListener("click", function () { kitDeleteCategory(c.id, c.name); });
        extra.appendChild(rn);
        extra.appendChild(del);
      }
      frag.appendChild(kitChestLine({
        icon: c.icon, name: c.name, what: c.what, current: typeof t.chest === "string" ? t.chest : "", status: str(t.chestStatus) || "unset",
        count: num(t.count, 0), can: can_, report: !!d.report, extra: extra,
        onPick: function (v) {
          if (!v) return;
          if (!kitChestSignOk(v)) {
            kits.sig = "";
            kitsRender();
            return;
          }
          kitsWrite("/api/v1/kits/typeChest", { type: c.id, container: v });
        },
        onClear: function () {
          if (!window.confirm("Stop using a chest for " + c.name + "?\n\nIts kits go to the general kits chest instead.")) return;
          kitsWrite("/api/v1/kits/typeChest", { type: c.id, container: null });
        }
      }));
    });
    var chest = typeof d.kitsChest === "string" ? d.kitsChest : "";
    var inGeneral = 0;
    (Array.isArray(d.rows) ? d.rows : []).forEach(function (r) {
      (Array.isArray(r.locations) ? r.locations : []).forEach(function (l) { if (chest && l.container === chest) inGeneral += num(l.count, 0); });
    });
    var info = null;
    if (chest) {
      var c0 = kitContainer(chest);
      if (c0 && str(c0.label) && !KIT_WORD.test(str(c0.label))) {
        info = kitEl("div", "hint", "Its sign doesn't say kit: while it is the kits chest, only kit shulkers go in it.");
      }
    }
    frag.appendChild(kitChestLine({
      icon: "minecraft:purple_shulker_box", name: "General kits chest", what: "Kits whose type chest is unset, full or missing",
      current: chest, status: str(d.kitsChestStatus) || "unset", count: inGeneral, can: can_, report: !!d.report, extra: info,
      onPick: function (v) {
        if (!v) return;
        if (!kitChestSignOk(v)) {
          kits.sig = "";
          kitsRender();
          return;
        }
        kitsWrite("/api/v1/kits/chest", { container: v });
      },
      onClear: function () {
        if (!window.confirm("Stop using a general kits chest?\n\nKits without a usable own or type chest go back to normal sorting.")) return;
        kitsWrite("/api/v1/kits/chest", { container: null });
      }
    }));
    box.replaceChildren(frag);
  }

  function kitRenameCategory(id, name) {
    var next = window.prompt("New name for the category (up to 32 characters):", streamerOn() ? "" : name);
    if (next == null || !next.trim() || next.trim() === name) return;
    kitsWrite("/api/v1/kits/category", { id: id, name: next.trim() }, true);
  }

  function kitDeleteCategory(id, name) {
    if (!window.confirm("Delete the category " + (streamerOn() ? "" : "“" + name + "” ") + "and its chest setting?\n\nThis only works while no kit or override uses it.")) return;
    kitsWrite("/api/v1/kits/category/delete", { id: id }, true);
  }

  // ----- kit rows -----

  /** A category's short name for a sentence: "PvP / Crystal" is "PvP", "Farming / Food" is "Farming". */
  function kitShortCat(id) {
    return kitCatName(id).split(" / ")[0];
  }

  function kitSourceName(source, r) {
    switch (source) {
      case "rule": return "Own chest";
      case "template": return "Kit chest";
      case "typeChest":
      case "category": return (r.type ? kitShortCat(r.type) : "Type") + " chest";
      default: return "Kits chest";
    }
  }

  function kitSourceLower(source, r) {
    switch (source) {
      case "rule": return "own chest";
      case "template": return "kit's chest";
      case "typeChest":
      case "category": return (r.type ? kitShortCat(r.type) : "type") + " chest";
      default: return "kits chest";
    }
  }

  /** "Own chest full, using the PvP chest": the first chain entry's problem and where the kit goes instead. */
  function kitFallbackText(r, chain) {
    var first = chain[0];
    var text = kitSourceName(str(first.source), r) + " " + (first.status === "missing" ? "missing" : first.status === "full" ? "full" : str(first.status));
    if (typeof r.target === "string") return text + ", using the " + kitSourceLower(str(r.targetSource), r);
    return text + "; nothing else to use";
  }

  function kitWhereNode(r) {
    var where = kitEl("div", "kit-where");
    where.appendChild(document.createTextNode("Now in: "));
    var locs = Array.isArray(r.locations) ? r.locations : [];
    locs.forEach(function (l, i) {
      if (i) where.appendChild(document.createTextNode(", "));
      where.appendChild(kitPlace(str(l.container)));
      where.appendChild(document.createTextNode(" ×" + num(l.count, 0)));
    });
    if (num(r.carried, 0) > 0) {
      if (locs.length) where.appendChild(document.createTextNode(", "));
      where.appendChild(document.createTextNode("carried ×" + num(r.carried, 0)));
    }
    if (!locs.length && !(num(r.carried, 0) > 0)) where.appendChild(document.createTextNode("not in the stash"));
    return where;
  }

  function kitGoesNode(r) {
    var goes = kitEl("div", "kit-goes");
    goes.appendChild(document.createTextNode("Goes to: "));
    var chain = Array.isArray(r.chain) ? r.chain : [];
    if (typeof r.target === "string") {
      goes.appendChild(kitPlace(r.target));
      goes.appendChild(document.createTextNode(" (" + (KIT_SOURCE_WORDS[str(r.targetSource)] || "chest") + ")"));
      if (r.fallback === true && chain.length) goes.appendChild(kitEl("div", "kit-fallback", kitFallbackText(r, chain)));
    } else if (!chain.length) {
      goes.appendChild(document.createTextNode("nowhere: sorted like any other shulker"));
    } else {
      // No entry is usable (all full or missing): name every chain entry and its state instead of a target.
      goes.appendChild(document.createTextNode("no usable chest: " + chain.map(function (e) {
        return kitSourceName(str(e.source), r) + " " + str(e.status);
      }).join(", ")));
    }
    return goes;
  }

  function kitRow(r, d, can_) {
    var row = kitEl("div", "kit-row");
    var unnamed = typeof r.key !== "string";
    var top = kitEl("div", "kit-row-top");
    var title = kitEl("span", "kit-name", unnamed ? "(unnamed)" : str(r.name) || str(r.key));
    if (streamerOn() && !unnamed) hide(title);
    else if (!unnamed) title.title = str(r.key);
    top.appendChild(title);
    var cnt = num(r.count, 0);
    // A template row merges complete and incomplete copies: the counts and the missing items come from the template's stock.
    var stock = r.template && kb.data && kb.data.stock && kb.data.stock[r.template] ? kb.data.stock[r.template] : null;
    var incomplete = stock ? num(stock.incomplete, 0) : 0;
    top.appendChild(kitEl("span", "kit-count-badge", "×" + cnt + (incomplete > 0 ? " (" + incomplete + " incomplete)" : "")));
    var st = d.report ? KIT_STATUS[r.status] || [str(r.status), ""] : ["Waiting for the bot's report", ""];
    top.appendChild(kitEl("span", "status-chip kit-pill" + (st[1] ? " " + st[1] : ""), st[0]));
    var src = KIT_TYPE_SOURCE[str(r.typeSource)];
    if (src) top.appendChild(kitEl("span", "status-chip kit-pill", src));
    row.appendChild(top);
    var whyObj = r.why;
    if (stock && whyObj && typeof whyObj === "object") {
      // The row's own reason is the first box's: say what the stock says instead.
      whyObj = Object.assign({}, whyObj, num(stock.complete, 0) === 0 && incomplete > 0
        ? { code: "template_incomplete", missing: Array.isArray(stock.missing) ? stock.missing : [] } : { code: "template" });
    }
    var why = kitReasonNode(whyObj, str(r.templateName));
    if (why) row.appendChild(why);
    if (stock && incomplete > 0 && num(stock.complete, 0) > 0) {
      var miss = (Array.isArray(stock.missing) ? stock.missing : []).filter(function (m) { return m && num(m.count, 0) > 0; })
        .map(function (m) { return kbItemLabel(str(m.key) || str(m.match)) + " \u00d7" + num(m.count, 0); });
      row.appendChild(kitEl("div", "kit-why", incomplete + " incomplete" + (miss.length ? " (short " + miss.join(", ") + ")" : "") +
        ": kept and topped up, but not delivered until complete."));
    }
    if (!why && r.status === "absent") row.appendChild(kitEl("div", "hint", "None of this name in the stash right now. The setting is kept."));
    else if (!why && r.type == null) row.appendChild(kitEl("div", "hint", "No type known: the bot is too old for kit types, or this is only a setting."));
    row.appendChild(kitWhereNode(r));
    row.appendChild(kitGoesNode(r));
    var ctl = kitEl("div", "kit-row-ctl");
    if (unnamed) {
      ctl.appendChild(kitEl("span", "hint", "An unnamed kit can't have its own chest or a type by hand."));
    } else {
      var l1 = kitEl("label", "kits-sc-field", "Own chest");
      var sel = document.createElement("select");
      sel.className = "mc-select kit-assign";
      sel.setAttribute("aria-label", "Own chest for " + (streamerOn() ? "this kit" : str(r.name) || str(r.key)));
      kitOptions(sel, typeof r.rule === "string" ? r.rule : "", "No own chest");
      sel.disabled = !can_ || !d.report;
      sel.addEventListener("change", function () {
        sel.blur();
        kitAssign(str(r.key), str(r.name) || str(r.key), sel.value, typeof r.rule === "string" ? r.rule : "");
      });
      l1.appendChild(sel);
      ctl.appendChild(l1);
      var l2 = kitEl("label", "kits-sc-field", "Type");
      l2.appendChild(kitTypeSelect(r, can_));
      ctl.appendChild(l2);
    }
    row.appendChild(ctl);
    return row;
  }

  /** The Type picker of a named kit or box: Auto, each category, Not a kit. Writes treatAs. */
  function kitTypeSelect(r, can_) {
    var sel = document.createElement("select");
    sel.className = "mc-select kit-assign";
    sel.setAttribute("aria-label", "Type for " + (streamerOn() ? "this kit" : str(r.name) || str(r.key)));
    kitTypeOptions(sel, typeof r.treatAs === "string" ? r.treatAs : "", "Auto", true);
    sel.disabled = !can_;
    sel.addEventListener("change", function () {
      var v = sel.value;
      sel.blur();
      kitsWrite("/api/v1/kits/treatAs", { key: str(r.key), type: v || null });
    });
    return sel;
  }

  function kitGroupNode(label, chestId, status, list, d, can_) {
    var g = kitEl("div", "kits-group");
    var h = kitEl("div", "kits-group-head");
    var n = 0;
    list.forEach(function (r) { n += num(r.count, 0); });
    h.appendChild(kitEl("span", "kits-group-name", label + " · " + n + (n === 1 ? " kit" : " kits")));
    if (chestId !== undefined) {
      var ch = kitEl("span", "kits-group-chest");
      if (chestId) {
        ch.appendChild(document.createTextNode("Chest: "));
        ch.appendChild(kitPlace(chestId));
        if (status === "full" || status === "missing") ch.appendChild(kitEl("span", "status-chip kit-pill " + (status === "full" ? "warn" : "off"), status === "full" ? "Full" : "Missing"));
      } else {
        ch.textContent = "No chest set";
      }
      h.appendChild(ch);
    }
    g.appendChild(h);
    list.forEach(function (r) { g.appendChild(kitRow(r, d, can_)); });
    return g;
  }

  function kitOtherRow(o, d, can_) {
    var row = kitEl("div", "kit-row");
    var top = kitEl("div", "kit-row-top");
    var title = kitEl("span", "kit-name", str(o.name) || str(o.key));
    if (streamerOn()) hide(title);
    else title.title = str(o.key);
    top.appendChild(title);
    top.appendChild(kitEl("span", "kit-count-badge", "×" + num(o.count, 0)));
    if (o.status === "absent") top.appendChild(kitEl("span", "status-chip kit-pill", "None in the stash"));
    row.appendChild(top);
    var why = kitReasonNode(o.why, "");
    if (why) row.appendChild(why);
    else if (o.status === "absent") row.appendChild(kitEl("div", "hint", "Set by hand to “Not a kit”; no box of this name is in the stash right now."));
    row.appendChild(kitWhereNode(o));
    var code = o.why && typeof o.why === "object" ? str(o.why.code) : "";
    // Multi-item boxes (and an override that was set by hand) get the picker; one-item boxes show only the reason.
    if (KIT_TYPE_WHY_CODES[code] || (o.status === "absent" && !code)) {
      var ctl = kitEl("div", "kit-row-ctl");
      var lab = kitEl("label", "kits-sc-field", "Treat as");
      lab.appendChild(kitTypeSelect(o, can_));
      ctl.appendChild(lab);
      row.appendChild(ctl);
    }
    return row;
  }

  function kitsRender() {
    var root = $("view-kits");
    if (!root) return;
    var d = kits.data;
    var sig = JSON.stringify([d, kits.error, kits.writing, kitsCan(), streamerOn(), !!currentStash(), kb.data && kb.data.categories, kb.data && kb.data.stock]);
    if (sig === kits.sig) return;
    var a = document.activeElement;
    if (a && root.contains(a) && a.tagName === "SELECT" && !kits.writing) return; // not under an open dropdown; the next poll draws it
    kits.sig = sig;
    var banner = $("kits-banner");
    var chestBox = $("kits-chests");
    var groups = $("kits-groups");
    var othersBox = $("kits-others-box");
    var others = $("kits-others");
    var empty = $("kits-empty");
    var count = $("kits-count");
    var adv = $("kits-adv");
    if (!currentStash()) {
      banner.replaceChildren();
      kitNote(banner, "No stash selected.");
      banner.hidden = false;
    } else if (!d) {
      banner.replaceChildren();
      kitNote(banner, kits.error || "Loading…", kits.error ? "off" : "");
      banner.hidden = false;
    }
    if (!d) {
      chestBox.replaceChildren();
      groups.replaceChildren();
      others.replaceChildren();
      othersBox.hidden = true;
      empty.hidden = true;
      count.textContent = "";
      adv.hidden = true;
      kitsScoringSync(null);
      return;
    }
    kitsBanner(banner, d);
    kitsScoringSync(d);
    var bot = d.bot && typeof d.bot === "object" ? d.bot : {};
    adv.hidden = !(bot.supports === true && bot.types !== true); // the v1 scoring matters only while an older bot reports
    var can_ = kitsCan() && !kits.writing;
    kitsRenderChests(chestBox, d, can_);

    var list = Array.isArray(d.rows) ? d.rows.slice(0, KIT_MAX_ROWS) : [];
    var frag = document.createDocumentFragment();
    var types = Array.isArray(d.types) ? d.types : [];
    var byType = {};
    list.forEach(function (r) {
      var k = typeof r.type === "string" ? r.type : "";
      (byType[k] = byType[k] || []).push(r);
    });
    types.forEach(function (t) {
      if (byType[t.id] && byType[t.id].length) {
        frag.appendChild(kitGroupNode(str(t.name) || t.id, typeof t.chest === "string" ? t.chest : "", str(t.chestStatus), byType[t.id], d, can_));
        delete byType[t.id];
      }
    });
    Object.keys(byType).forEach(function (k) {
      // Rows without a type (an older bot, or a setting for a kit that is not in the stash), or of a type the page does not list.
      frag.appendChild(kitGroupNode(k ? kitCatName(k) : "No type known", undefined, "", byType[k], d, can_));
    });
    groups.replaceChildren(frag);
    empty.hidden = list.length > 0;
    empty.textContent = d.report ? "No kits found. Shulker boxes of several kinds of item that serve one purpose count as kits." : "Waiting for the bot's report.";
    count.textContent = Array.isArray(d.rows) && d.rows.length > KIT_MAX_ROWS ? "Showing the first " + KIT_MAX_ROWS + " of " + d.rows.length + " kits." : "";

    var oth = Array.isArray(d.others) ? d.others.slice(0, KIT_MAX_ROWS) : [];
    var ofrag = document.createDocumentFragment();
    oth.forEach(function (o) { ofrag.appendChild(kitOtherRow(o, d, can_)); });
    others.replaceChildren(ofrag);
    othersBox.hidden = !oth.length;
  }

  var KIT_SCORING_FIELDS = ["threshold", "name", "mix", "full", "op", "opMin"];
  var KIT_SCORING_POINTS = ["name", "mix", "full", "op"]; // what the highest possible score adds up
  var KIT_SCORING_DEFAULT = { threshold: 4, name: 2, mix: 3, full: 1, op: 4, opMin: 3 };

  function kitScoringInput(field) {
    return $("kits-sc-" + field);
  }

  /** The editor's numbers if they are all valid whole numbers in range, else null. */
  function kitScoringRead() {
    var out = {};
    for (var i = 0; i < KIT_SCORING_FIELDS.length; i++) {
      var f = KIT_SCORING_FIELDS[i];
      var raw = kitScoringInput(f).value.trim();
      if (!/^[0-9]{1,4}$/.test(raw)) return null;
      var v = parseInt(raw, 10);
      if (f === "threshold" ? v < 1 || v > 40 : f === "opMin" ? v < 1 || v > 1728 : v > 10) return null;
      out[f] = v;
    }
    return out;
  }

  function kitScoringUpdate() {
    var v = kitScoringRead();
    var sum = 0;
    KIT_SCORING_POINTS.forEach(function (f) { sum += parseInt(kitScoringInput(f).value, 10) || 0; });
    var note = $("kits-sc-max");
    note.textContent = "Highest possible score: " + sum + (v ? "" : " (check the numbers: threshold 1 to 40, points 0 to 10, OP items needed 1 to 1728)");
    var sc = kits.data && kits.data.scoring;
    var same = !!v && !!sc && KIT_SCORING_FIELDS.every(function (f) { return v[f] === sc[f]; });
    $("kits-sc-save").disabled = !v || same || !kitsCan() || kits.writing || !kits.data;
    var atDefault = !!sc && KIT_SCORING_FIELDS.every(function (f) { return sc[f] === KIT_SCORING_DEFAULT[f]; });
    $("kits-sc-reset").disabled = atDefault || !kitsCan() || kits.writing || !kits.data;
  }

  /** Shows the server's scoring in the editor, unless the user is in the middle of editing it. */
  function kitsScoringSync(d) {
    KIT_SCORING_FIELDS.forEach(function (f) { kitScoringInput(f).disabled = !d || !kitsCan() || kits.writing; });
    if (d && d.scoring) {
      // Unsaved edits = the fields differ from the last numbers the server gave. Those are never overwritten.
      var server = KIT_SCORING_FIELDS.map(function (f) { return String(num(d.scoring[f], 0)); });
      var shown = KIT_SCORING_FIELDS.map(function (f) { return kitScoringInput(f).value.trim(); });
      var same = function (a, b) { return a.join(",") === b.join(","); };
      if (same(shown, server)) {
        kits.scoringSnap = server;
      } else if (!kits.scoringSnap || same(shown, kits.scoringSnap)) {
        KIT_SCORING_FIELDS.forEach(function (f, i) { kitScoringInput(f).value = server[i]; });
        kits.scoringSnap = server;
      }
    }
    kitScoringUpdate();
  }

  KIT_SCORING_FIELDS.forEach(function (f) {
    kitScoringInput(f).addEventListener("input", function () {
      kitScoringUpdate();
    });
  });

  $("kits-sc-save").addEventListener("click", function () {
    var v = kitScoringRead();
    if (!v) return;
    kitsWrite("/api/v1/kits/scoring", v);
  });

  $("kits-sc-reset").addEventListener("click", function () {
    if (!window.confirm("Put the kit scoring back to the defaults (threshold 4; name 2, mix 3, full 1, OP 4 for 3 or more OP items)?")) return;
    kitsWrite("/api/v1/kits/scoring", {
      threshold: KIT_SCORING_DEFAULT.threshold, name: KIT_SCORING_DEFAULT.name, mix: KIT_SCORING_DEFAULT.mix,
      full: KIT_SCORING_DEFAULT.full, op: KIT_SCORING_DEFAULT.op, opMin: KIT_SCORING_DEFAULT.opMin
    });
  });

  /** Why a kit write failed, in words. */
  function kitFailText(e, dflt) {
    if (e && e.status === 403 && e.body && e.body.permission === "streamer") return "Kit settings can't be changed in streamer mode (enforced by the owner).";
    if (e && e.status === 429) return "Too many changes. Wait " + num(e.body && e.body.retryAfter, 1) + " s.";
    if (e && e.network) return "Can't reach the server.";
    if (e && e.body && typeof e.body.error === "string") return safeServerText(e.body.error, "The server refused that (details are hidden in streamer mode).");
    return dflt;
  }

  /**
   * A write to the server; the answer is the same body as GET, so it is drawn straight away. The category routes answer
   * with the templates body instead (asTemplates): that one goes to the Kit Builder and the kits are read again.
   */
  function kitsWrite(path, body, asTemplates) {
    var s = currentStash();
    if (!s || kits.writing) return;
    kits.writing = true;
    var token = ++kits.token; // a poll that is still running can't overwrite what this write answers
    body.server = s.server;
    body.stash = s.stash;
    kitsRender();
    api(path, { method: "POST", body: body }).then(function (d) {
      if (token !== kits.token) return;
      if (asTemplates) kb.data = d && typeof d === "object" ? d : kb.data;
      else kits.data = d && typeof d === "object" ? d : kits.data;
      toast("Saved. The bot picks it up in a few seconds.");
    }, function (e) {
      toast(kitFailText(e, "Couldn't save that."));
    }).then(function () {
      kits.writing = false;
      kits.token++; // a poll that started while the write ran may have seen the old state
      kits.sig = "";
      kitsRender();
      kitsSync();
      if (state.tab === "kitbuilder") kbRender();
    });
  }

  function kitAssign(key, name, container, was) {
    if (container === was) return;
    if (!container) {
      if (!window.confirm((streamerOn() ? "Send this kit to its type chest again?" : "Send “" + name + "” to its type chest again?") + "\n\nIts own chest rule is removed.")) {
        kits.sig = "";
        kitsRender();
        return;
      }
      kitsWrite("/api/v1/kits/rule/delete", { key: key });
    } else {
      kitsWrite("/api/v1/kits/rule", { key: key, container: container });
    }
  }

  document.addEventListener("visibilitychange", function () {
    kitsSync();
    kbSync();
  });

  // ---------- kit builder ----------
  // The Kit Builder tab (KIT-CONTRACT v2, revisions 3 and 4): templates of 27 slots. The bot recognises boxes that match
  // one, sorts them to its chest, keeps copies in stock (restock) and builds them for kit lines in orders. The server
  // validates everything and fills the slots' match keys; this draws the list and the editor. The templates body (GET
  // /api/v1/kits/templates) is also served to a forced streamer, with every container id "hidden".

  var KB_POLL_MS = 5000;
  var KB_COLORS = [["white", "White"], ["orange", "Orange"], ["magenta", "Magenta"], ["light_blue", "Light blue"], ["yellow", "Yellow"],
    ["lime", "Lime"], ["pink", "Pink"], ["gray", "Gray"], ["light_gray", "Light gray"], ["cyan", "Cyan"], ["purple", "Purple"],
    ["blue", "Blue"], ["brown", "Brown"], ["green", "Green"], ["red", "Red"], ["black", "Black"]];
  var KB_UNRESOLVED = "This variant isn't in the stash any more: pick it again, or use any variant";
  var KB_MATCH_HINT = "Matches boxes with the same items, enchantments and contents; item names, damage and repair cost don't matter.";
  var KB_RESTOCK_WHY = {
    orders: "waiting for orders to finish",
    items_short: "waiting for items",
    bottle_reserve: "keeping the XP bottle reserve",
    no_shulker: "no empty shulker box left",
    chest_full: "its chest is full",
    chest_missing: "its chest is missing",
    cap: "hourly limit reached",
    naming: "the bot can't name kits",
    topping_up: "topping up"
  };

  function kbVisible() {
    // The Kits tab reads the stock of each template too (complete and incomplete copies), unless the user is a forced streamer.
    return (state.tab === "kitbuilder" || (state.tab === "kits" && !forcedStreamer())) && dash.page === "stash" && !document.hidden && !auth.blocked && !!currentStash();
  }

  function kbSync() {
    window.clearTimeout(kb.timer);
    kb.timer = 0;
    if (kbVisible()) kbPoll();
  }

  function kbReset() {
    kb.token++;
    kb.data = null;
    kb.error = "";
    kb.sig = "";
    kb.ed = null;
    kb.ui = null;
    kb.cartTried = null;
    kb.loading = false;
    kbRender();
    kbSync();
  }

  function kbCan() {
    return can("settings") && !forcedStreamer();
  }

  function kbPoll() {
    if (kb.loading || kb.writing) return;
    var s = currentStash();
    if (!s) return;
    kb.loading = true;
    var token = kb.token;
    api("/api/v1/kits/templates?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== kb.token) return;
      kb.data = d && typeof d === "object" && Array.isArray(d.templates) ? d : null;
      kb.error = kb.data ? "" : "The server sent something unexpected.";
      if (!kb.data) kb.data = null;
    }, function (e) {
      if (token !== kb.token) return;
      // A transient failure (network, server error) keeps the last good data and only shows the banner.
      if (e && (e.status === 403 || e.status === 404 || e.status === 503)) kb.data = null;
      if (e && e.status === 503) kb.error = "The server can't read its kit settings (kits.json is damaged), so kits are off. Fix or remove the file and restart the server.";
      else if (e && e.status === 404) kb.error = "That stash isn't available.";
      else if (e && e.status === 403) kb.error = "You can't see kits.";
      else kb.error = "Can't reach the server. Trying again.";
    }).then(function () {
      kb.loading = false;
      if (token !== kb.token) {
        kbSync();
        return;
      }
      kbRender();
      if (state.tab === "kits") kitsRender();
      if (state.tab === "orders" && !el.cartLines.contains(document.activeElement)) renderCart();
      window.clearTimeout(kb.timer);
      kb.timer = kbVisible() ? window.setTimeout(kbPoll, KB_POLL_MS) : 0;
    });
  }

  /** The cart shows kit names from the templates: read them once when the cart holds a kit line and nothing was loaded yet. */
  function kbWantCartNames() {
    if (kb.data || kb.loading || kb.cartTried === state.selected) return;
    if (!cart.lines.some(function (l) { return l.type === "kit"; })) return;
    kb.cartTried = state.selected;
    kbPoll();
  }

  function kbTemplate(id) {
    var list = kb.data && Array.isArray(kb.data.templates) ? kb.data.templates : [];
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function kbShulkerId(color) {
    return color && color !== "plain" ? "minecraft:" + color + "_shulker_box" : "minecraft:shulker_box";
  }

  /** The slots as shulker contents ({ slot, key, count }) for an icon or the delivery preview. */
  function kbContents(t) {
    var out = [];
    (Array.isArray(t.slots) ? t.slots : []).forEach(function (s, i) {
      if (s && typeof s === "object") out.push({ slot: i, key: str(s.key), count: num(s.count, 1) });
    });
    return out;
  }

  /** A cart line's stand-in for an item: the kit's shulker icon with its contents. */
  function kbLineItem(key) {
    var t = kbTemplate(key);
    if (!t) return null;
    return { key: key, id: kbShulkerId(t.color), name: str(t.name), englishName: str(t.name), count: MAX_SHULKERS, maxStack: 1, details: [],
      enchanted: false, damage: 0, maxDamage: 0, contents: kbContents(t) };
  }

  /** A kit's name for an order: the order's own snapshot first (the template may be deleted since), then the templates. */
  function kbOrderKitName(o, id, fallback) {
    if (o && o.kit && Array.isArray(o.kit.lines)) {
      for (var i = 0; i < o.kit.lines.length; i++) {
        var l = o.kit.lines[i];
        if (l && l.template && l.template.id === id && str(l.template.name)) return str(l.template.name);
      }
    }
    var t = kbTemplate(id);
    if (t) return str(t.name);
    return fallback && fallback !== "(hidden)" ? fallback : id;
  }

  function kbStockText(t) {
    var d = kb.data;
    var st = d && d.stock && d.stock[t.id] && typeof d.stock[t.id] === "object" ? d.stock[t.id] : null;
    if (!st) return "No report from the bot yet.";
    var complete = num(st.complete, 0);
    var incomplete = num(st.incomplete, 0);
    var text = complete + " complete";
    if (incomplete > 0) {
      text += ", " + incomplete + " incomplete";
      var miss = (Array.isArray(st.missing) ? st.missing : []).filter(function (m) { return m && num(m.count, 0) > 0; });
      if (miss.length) {
        text += ": short " + miss.slice(0, 4).map(function (m) { return kbItemLabel(str(m.key) || str(m.match)) + " ×" + grouped(num(m.count, 0)); }).join(", ") +
          (miss.length > 4 ? ", …" : "");
      }
    }
    var else_ = num(st.elsewhere, 0);
    var carried = num(st.carried, 0);
    if (else_ > 0) text += " (" + else_ + " elsewhere)";
    if (carried > 0) text += " (" + carried + " carried)";
    return text;
  }

  function kbRestockText(t) {
    var d = kb.data;
    var keep = num(t.keep, 0);
    if (keep <= 0) return "Restock: off";
    var cap = d && d.capability && typeof d.capability === "object" ? d.capability : {};
    var base = "Restock: keeps " + keep;
    if (cap.restock !== true) return base + " (this bot can't restock yet)";
    var rs = d.restock && typeof d.restock === "object" && d.restock.template === t.id ? d.restock : null;
    if (!rs) return base;
    var reason = str(rs.reason);
    var why = KB_RESTOCK_WHY[reason] || "";
    switch (str(rs.state)) {
      case "building": return base + ": building one now";
      case "waiting": return base + ": " + (why || "waiting for items");
      case "blocked": return base + ": blocked" + (why ? ", " + why : "");
      default: return base + (why ? ": " + why : "");
    }
  }

  function kbChestText(t) {
    var src = str(t.chestSource);
    var status = str(t.effectiveChestStatus);
    var words = { rule: "its own chest rule", template: "its own chest", typeChest: "the category's chest", category: "the category's chest", kitsChest: "the general kits chest" };
    var wrap = kitEl("span", "");
    wrap.appendChild(document.createTextNode("Kept in: "));
    if (typeof t.effectiveChest === "string" && t.effectiveChest) {
      wrap.appendChild(kitPlace(t.effectiveChest));
      wrap.appendChild(document.createTextNode(" (" + (words[src] || "chest") + ")"));
      if (status === "full") wrap.appendChild(kitEl("span", "status-chip kit-pill warn", "Full"));
      if (status === "missing") wrap.appendChild(kitEl("span", "status-chip kit-pill off", "Missing"));
    } else {
      wrap.appendChild(document.createTextNode("no chest set: sorted like any other shulker"));
    }
    // With the kits body loaded: the row's real target ("X is full, using Y").
    var row = null;
    if (kits.data && Array.isArray(kits.data.rows)) kits.data.rows.forEach(function (r) { if (r && r.template === t.id && !row) row = r; });
    if (row && Array.isArray(row.chain) && row.chain.length) {
      if (typeof row.target === "string" && row.fallback === true) wrap.appendChild(kitEl("div", "kit-fallback", kitFallbackText(row, row.chain)));
      else if (typeof row.target !== "string") wrap.appendChild(kitEl("div", "kit-fallback", "No usable chest: " + row.chain.map(function (e) {
        return kitSourceName(str(e.source), row) + " " + str(e.status);
      }).join(", ")));
    }
    return wrap;
  }

  /** A 9x3 preview of the kit's slots, with the item icons. */
  function kbPreview(t) {
    var g = kitEl("div", "kb-prev");
    var slots = Array.isArray(t.slots) ? t.slots : [];
    for (var i = 0; i < 27; i++) {
      var s = slots[i];
      var c = kitEl("span", "kb-cell");
      if (s && typeof s === "object") {
        var any = str(s.key).slice(-2) === "#*";
        c.appendChild(iconImg(str(s.key).split("#")[0], 24));
        if (num(s.count, 1) > 1) c.appendChild(kitEl("span", "kb-cell-n", compact(num(s.count, 1))));
        if (any) c.classList.add("any");
        c.title = kbItemLabel(s.key) + " ×" + num(s.count, 1) + (any ? " (any variant)" : "");
      }
      g.appendChild(c);
    }
    return g;
  }

  function kbBanner() {
    var box = $("kb-banner");
    box.replaceChildren();
    var d = kb.data;
    if (!currentStash()) {
      kitNote(box, "No stash selected.");
    } else if (!d) {
      kitNote(box, kb.error || "Loading…", kb.error ? "off" : "");
    } else {
      var cap = d.capability && typeof d.capability === "object" ? d.capability : {};
      if (cap.kitOrders !== true) {
        kitNote(box, "This bot can't build kits yet: the kit is saved and sorted, but can't be ordered or restocked.", "warn");
      } else if (cap.craft !== true) {
        kitNote(box, "Short items won't be crafted.");
      }
      if (kb.error) kitNote(box, kb.error, "off");
      if (forcedStreamer()) kitNote(box, "Kit settings can't be changed in streamer mode (enforced by the owner). You can still order kits.");
      else if (!can("settings")) kitNote(box, "You can look and order, but changing kits needs the “settings” permission.");
    }
    box.hidden = !box.firstChild;
  }

  function kbRender() {
    var main = $("kb-main");
    if (!main) return;
    kbBanner();
    if (kb.ed) {
      kbEdUpdate();
      return;
    }
    var d = kb.data;
    var sig = JSON.stringify([d, kb.error, kb.writing, kbCan(), can("order"), streamerOn(), !!currentStash(), kits.data && kits.data.containers]);
    if (sig === kb.sig) return;
    var a = document.activeElement;
    if (a && main.contains(a) && (a.tagName === "INPUT" || a.tagName === "SELECT") && !kb.writing) return;
    kb.sig = sig;
    if (!d) {
      main.replaceChildren(kitEl("div", "hint", kb.error || "Loading…"));
      return;
    }
    var frag = document.createDocumentFragment();
    frag.appendChild(kbToolbar(d));
    var cap = d.capability && typeof d.capability === "object" ? d.capability : {};
    var cats = kitAllCats();
    var by = {};
    d.templates.forEach(function (t) { (by[str(t.category)] = by[str(t.category)] || []).push(t); });
    var any = false;
    cats.forEach(function (c) {
      if (!by[c.id]) return;
      any = true;
      frag.appendChild(kbGroup(c.name, by[c.id], cap));
      delete by[c.id];
    });
    Object.keys(by).forEach(function (k) {
      any = true;
      frag.appendChild(kbGroup(kitCatName(k), by[k], cap));
    });
    if (!any) frag.appendChild(kitEl("div", "hint", "No kits yet. Start one from a preset, or from an empty shulker."));
    main.replaceChildren(frag);
  }

  function kbToolbar(d) {
    var bar = kitEl("div", "kb-toolbar");
    var lab = kitEl("label", "kits-sc-field", "New kit from");
    var sel = document.createElement("select");
    sel.className = "mc-select";
    sel.id = "kb-preset";
    sel.appendChild(kitOption("", "An empty kit"));
    var presets = Array.isArray(d.presets) ? d.presets : [];
    var groups = {};
    presets.forEach(function (p) {
      if (!p || typeof p !== "object") return;
      (groups[str(p.category)] = groups[str(p.category)] || []).push(p);
    });
    kitAllCats().forEach(function (c) {
      if (!groups[c.id]) return;
      var og = document.createElement("optgroup");
      og.label = c.name;
      groups[c.id].forEach(function (p) { og.appendChild(kitOption(str(p.id), str(p.name))); });
      sel.appendChild(og);
    });
    sel.disabled = !kbCan();
    lab.appendChild(sel);
    bar.appendChild(lab);
    var btn = kitEl("button", "mc-button", "New kit");
    btn.type = "button";
    btn.disabled = !kbCan() || d.templates.length >= num(d.limits && d.limits.maxTemplates, 50);
    btn.title = kbCan() ? "" : "Changing kits needs the settings permission";
    btn.addEventListener("click", function () {
      var id = sel.value;
      var preset = null;
      presets.forEach(function (p) { if (p && p.id === id) preset = p; });
      kbOpenEditor(preset ? { name: str(preset.name), category: str(preset.category), color: preset.color || null, keep: 0, chest: null, slots: preset.slots } : null, true);
    });
    bar.appendChild(btn);
    return bar;
  }

  function kbGroup(name, list, cap) {
    var g = kitEl("div", "kits-group");
    g.appendChild(kitEl("div", "kits-group-head", name + " · " + list.length + (list.length === 1 ? " kit" : " kits")));
    list.forEach(function (t) { g.appendChild(kbCard(t, cap)); });
    return g;
  }

  function kbCard(t, cap) {
    var card = kitEl("div", "kb-card");
    card.appendChild(kbPreview(t));
    var info = kitEl("div", "kb-info");
    var top = kitEl("div", "kit-row-top");
    var title = kitEl("span", "kit-name", str(t.name));
    if (streamerOn()) hide(title);
    top.appendChild(title);
    top.appendChild(kitEl("span", "status-chip kit-pill", t.color ? (t.color === "plain" ? "Plain shulker" : (KB_COLORS.filter(function (c) { return c[0] === t.color; })[0] || [0, t.color])[1] + " shulker") : "Any colour"));
    info.appendChild(top);
    info.appendChild(kitEl("div", "kb-stock", kbStockText(t)));
    info.appendChild(kitEl("div", "kb-restock", kbRestockText(t)));
    var chest = kitEl("div", "kb-chest");
    chest.appendChild(kbChestText(t));
    info.appendChild(chest);
    if (Array.isArray(t.slots) && t.slots.some(function (s) { return s && kbSlotBad(s); })) {
      info.appendChild(kitEl("div", "kits-note warn", "Some items aren't in the stash any more. Edit the kit to pick them again, or use any variant."));
    }
    card.appendChild(info);
    var act = kitEl("div", "kb-actions");
    var qty = document.createElement("input");
    qty.type = "number";
    qty.className = "mc-input qty-input";
    qty.min = "1";
    qty.max = String(MAX_SHULKERS);
    qty.value = String(kb.qty[t.id] || 1);
    qty.setAttribute("aria-label", "How many kits to order");
    qty.addEventListener("input", function () { kb.qty[t.id] = clampInt(qty.value, 1, MAX_SHULKERS); });
    var order = kitEl("button", "mc-button small", "Order");
    order.type = "button";
    var canOrder = can("order") && cap.kitOrders === true;
    qty.disabled = !canOrder;
    order.disabled = !canOrder;
    order.title = !can("order") ? "You don't have the order permission" : cap.kitOrders !== true ? "This bot can't build kits yet" : "Add this kit to the order cart";
    order.addEventListener("click", function () {
      var n = clampInt(qty.value, 1, MAX_SHULKERS);
      kb.qty[t.id] = n;
      if (addToCart("kit", t.id, n)) toast("Added " + n + " × Kit: " + (streamerOn() ? "a kit" : str(t.name)) + ". Place the order in the Orders tab.");
    });
    var edit = kitEl("button", "mc-button small", "Edit");
    edit.type = "button";
    edit.disabled = !kbCan();
    edit.addEventListener("click", function () { kbOpenEditor(t, false); });
    var dup = kitEl("button", "mc-button small", "Duplicate");
    dup.type = "button";
    dup.disabled = !kbCan() || (kb.data && kb.data.templates.length >= num(kb.data.limits && kb.data.limits.maxTemplates, 50));
    dup.addEventListener("click", function () {
      kbOpenEditor({ name: str(t.name).slice(0, 44) + " (2)", category: t.category, color: t.color, keep: 0, chest: t.chest === "hidden" ? null : t.chest, slots: t.slots }, true);
    });
    var del = kitEl("button", "mc-button small danger", "Delete");
    del.type = "button";
    del.disabled = !kbCan();
    del.addEventListener("click", function () {
      if (!window.confirm("Delete " + (streamerOn() ? "this kit" : "the kit “" + str(t.name) + "”") + "?\n\nBoxes that match it are then sorted by type like any other box. Open orders keep their own copy.")) return;
      kbWrite("/api/v1/kits/template/delete", { id: t.id }, "Deleted.");
    });
    var ordRow = kitEl("div", "kb-act-row");
    ordRow.appendChild(qty);
    ordRow.appendChild(order);
    act.appendChild(ordRow);
    var editRow = kitEl("div", "kb-act-row");
    editRow.appendChild(edit);
    editRow.appendChild(dup);
    editRow.appendChild(del);
    act.appendChild(editRow);
    card.appendChild(act);
    return card;
  }

  /** A write of the Kit Builder; the answer is the templates body. then(d) runs after a success. */
  function kbWrite(path, body, doneText, then) {
    var s = currentStash();
    if (!s) return;
    if (kb.writing) {
      toast("Another change is still saving");
      return;
    }
    kb.writing = true;
    var token = ++kb.token;
    body.server = s.server;
    body.stash = s.stash;
    api(path, { method: "POST", body: body }).then(function (d) {
      if (token === kb.token && d && typeof d === "object" && Array.isArray(d.templates)) kb.data = d;
      toast(doneText || "Saved. The bot picks it up in a few seconds.");
      kits.sig = "";
      if (then) then(d);
    }, function (e) {
      var msg = kitFailText(e, "Couldn't save that.");
      toast(msg);
      if (kb.ed) {
        kb.ed.error = msg;
      }
    }).then(function () {
      kb.writing = false;
      kb.token++;
      kb.sig = "";
      if (kb.ed) kb.ed.saving = false;
      kbRender();
      kbSync();
      kitsSync();
    });
  }

  // ----- the item list the editor draws from -----

  var kbIndexMatch = { src: null, map: new Map() };

  /** The match key of a slot: the "#*" key or a plain key is its own; an exact variant takes the index's. */
  function kbMatchOf(s) {
    var key = s.key;
    if (key.indexOf("#") < 0 || key.slice(-2) === "#*") return key;
    var idx = state.index;
    if (idx && kbIndexMatch.src !== idx) {
      kbIndexMatch.src = idx;
      kbIndexMatch.map = new Map();
      (Array.isArray(idx.items) ? idx.items : []).forEach(function (it) {
        if (it && typeof it === "object" && typeof it.key === "string" && typeof it.match === "string") kbIndexMatch.map.set(it.key, it.match);
      });
    }
    return kbIndexMatch.map.get(key) || s.match || key;
  }

  /** True for an exact variant slot the stash index doesn't know (any more). */
  function kbSlotBad(s) {
    var key = str(s.key);
    if (key.indexOf("#") < 0 || key.slice(-2) === "#*") return false;
    if (state.items.length) return !state.byKey.has(key);
    return s.resolved === false || s.unres === true;
  }

  function kbMaxStack(key) {
    var i = key.indexOf("#");
    var base = i < 0 ? key : key.slice(0, i);
    var e = state.byKey.get(key);
    if (e) return e.item.maxStack;
    var info = kbIdInfo(base);
    return info ? info.maxStack : 64; // an item the stash has never held: the server checks the real stack size
  }

  // ----- the editor -----

  function kbNorm(name) {
    return String(name).replace(/§./g, "").toLowerCase().replace(/\s+/g, " ").trim();
  }

  /** Starts an editor on a template (or a preset / copy for isNew), or an empty kit. */
  function kbOpenEditor(t, isNew) {
    var slots = [];
    for (var i = 0; i < 27; i++) {
      var s = t && Array.isArray(t.slots) ? t.slots[i] : null;
      slots.push(s && typeof s === "object" && typeof s.key === "string" ? {
        key: s.key, count: Math.max(1, Math.floor(num(s.count, 1))), match: typeof s.match === "string" ? s.match : "",
        exact: s.key.slice(-2) === "#*" ? "" : s.key, resolved: s.resolved, unres: s.resolved === false
      } : null);
    }
    var first = 0;
    for (var j = 0; j < 27; j++) if (slots[j]) { first = j; break; }
    kb.ed = {
      id: isNew || !t ? null : t.id,
      name: t ? str(t.name) : "",
      category: t ? str(t.category) || "pvp" : "pvp",
      chest: t && typeof t.chest === "string" && t.chest !== "hidden" ? t.chest : "",
      color: t && t.color ? str(t.color) : "",
      keep: t ? Math.max(0, Math.floor(num(t.keep, 0))) : 0,
      slots: slots, sel: t ? first : 0, search: "", mode: "stash", error: "", saving: false, orig: ""
    };
    kb.ed.orig = JSON.stringify(kbEdBody(kb.ed));
    kbBuildEditor();
    kbEdUpdate();
  }

  /** The template as it is sent. Only a variant slot carries its match key (an import); the server fills the rest from the index. */
  function kbEdBody(ed) {
    var t = {
      name: ed.name.trim(), category: ed.category, chest: ed.chest || null, color: ed.color || null, keep: ed.keep,
      slots: ed.slots.map(function (s) {
        if (!s) return null;
        var o = { key: s.key, count: s.count };
        if (s.match && s.key.indexOf("#") > 0 && s.key.slice(-2) !== "#*") o.match = s.match;
        return o;
      })
    };
    if (ed.id) t.id = ed.id;
    return t;
  }

  function kbEdDirty() {
    return !!kb.ed && JSON.stringify(kbEdBody(kb.ed)) !== kb.ed.orig;
  }

  function kbEdClose() {
    kb.ed = null;
    kb.ui = null;
    kb.sig = "";
    kbRender();
  }

  function kbField(label, control, cls) {
    var l = kitEl("label", "kits-sc-field" + (cls ? " " + cls : ""), label);
    l.appendChild(control);
    return l;
  }

  function kbBuildEditor() {
    var ed = kb.ed;
    var main = $("kb-main");
    var ui = kb.ui = { slots: [], pickBtns: [] };
    main.replaceChildren();
    var title = kitEl("div", "section-title");
    title.appendChild(document.createTextNode(ed.id ? "Edit kit: " : "New kit"));
    if (ed.id) title.appendChild(hiddenSpan(ed.name));
    main.appendChild(title);
    ui.error = kitEl("div", "kits-note off");
    ui.error.hidden = true;
    main.appendChild(ui.error);

    // fields
    var fields = kitEl("div", "kits-sc-row kb-fields");
    ui.name = document.createElement("input");
    ui.name.type = "text";
    ui.name.className = "mc-input kb-name";
    ui.name.maxLength = 50;
    ui.name.autocomplete = "off";
    ui.name.spellcheck = false;
    ui.name.setAttribute("data-sensitive", "");
    ui.name.value = ed.name;
    ui.name.addEventListener("input", function () { ed.name = ui.name.value; kbEdUpdate(); });
    fields.appendChild(kbField("Name", ui.name, "kb-f-name"));

    ui.cat = document.createElement("select");
    ui.cat.className = "mc-select";
    fields.appendChild(kbField("Kind of kit", ui.cat));
    ui.cat.addEventListener("change", function () {
      if (ui.cat.value === "__new__") {
        kbNewCategory();
        return;
      }
      ed.category = ui.cat.value;
      kbEdUpdate();
    });

    ui.chest = document.createElement("select");
    ui.chest.className = "mc-select";
    fields.appendChild(kbField("Kept in", ui.chest));
    ui.chest.addEventListener("change", function () { ed.chest = ui.chest.value; kbEdUpdate(); });

    ui.color = document.createElement("select");
    ui.color.className = "mc-select";
    ui.color.appendChild(kitOption("", "Any colour"));
    ui.color.appendChild(kitOption("plain", "Plain (undyed)"));
    KB_COLORS.forEach(function (c) { ui.color.appendChild(kitOption(c[0], c[1])); });
    ui.color.value = ed.color;
    fields.appendChild(kbField("Shulker colour", ui.color));
    ui.color.addEventListener("change", function () { ed.color = ui.color.value; kbEdUpdate(); });

    var rs = kitEl("div", "kb-restock-field");
    ui.restock = document.createElement("select");
    ui.restock.className = "mc-select";
    ui.restock.appendChild(kitOption("off", "Off"));
    ui.restock.appendChild(kitOption("keep", "Keep"));
    ui.restock.value = ed.keep > 0 ? "keep" : "off";
    ui.keep = document.createElement("input");
    ui.keep.type = "number";
    ui.keep.className = "mc-input qty-input";
    ui.keep.min = "1";
    ui.keep.max = "27";
    ui.keep.value = String(Math.max(1, ed.keep || 3));
    ui.keep.setAttribute("aria-label", "How many complete kits to keep");
    rs.appendChild(ui.restock);
    rs.appendChild(ui.keep);
    fields.appendChild(kbField("Restock", rs));
    var syncKeep = function () {
      ed.keep = ui.restock.value === "keep" ? clampInt(ui.keep.value, 1, 27) : 0;
      ui.keep.hidden = ui.restock.value !== "keep";
      kbEdUpdate();
    };
    ui.restock.addEventListener("change", syncKeep);
    ui.keep.addEventListener("input", syncKeep);
    ui.keep.hidden = ed.keep <= 0;
    main.appendChild(fields);
    ui.restockNote = kitEl("div", "hint");
    main.appendChild(ui.restockNote);

    // the grid and the picker
    var cols = kitEl("div", "kb-cols");
    var left = kitEl("div", "kb-left");
    left.appendChild(kitEl("div", "hint", "The shulker box: click a slot, then pick an item. Right-click a slot to empty it."));
    ui.grid = kitEl("div", "kb-grid");
    ui.grid.setAttribute("role", "group");
    ui.grid.setAttribute("aria-label", "Shulker box slots");
    for (var i = 0; i < 27; i++) {
      (function (index) {
        var b = kitEl("button", "slot kb-slot");
        b.type = "button";
        b.appendChild(kitEl("span", "count"));
        b.appendChild(kitEl("span", "kb-any", "*"));
        b.addEventListener("click", function () { ed.sel = index; kbEdUpdate(); });
        b.addEventListener("contextmenu", function (ev) {
          ev.preventDefault();
          if (ed.saving) return;
          ed.slots[index] = null;
          ed.sel = index;
          kbEdUpdate();
        });
        ui.slots.push(b);
        ui.grid.appendChild(b);
      })(i);
    }
    left.appendChild(ui.grid);
    ui.hint = kitEl("div", "hint", KB_MATCH_HINT);
    left.appendChild(ui.hint);
    cols.appendChild(left);

    var right = kitEl("div", "kb-right");
    ui.selBox = kitEl("div", "kb-sel");
    ui.selName = kitEl("div", "kb-sel-name");
    ui.selBox.appendChild(ui.selName);
    var selRow = kitEl("div", "kits-sc-row");
    ui.count = document.createElement("input");
    ui.count.type = "number";
    ui.count.className = "mc-input qty-input";
    ui.count.min = "1";
    ui.count.addEventListener("input", function () {
      var s = ed.slots[ed.sel];
      if (!s) return;
      var max = kbMaxStack(s.key);
      var v = Math.floor(Number(ui.count.value));
      s.count = isFinite(v) && v >= 1 ? Math.min(v, 1000) : 1;
      s.over = s.count > max;
      kbEdUpdate(true);
    });
    selRow.appendChild(kbField("Count", ui.count));
    ui.any = document.createElement("input");
    ui.any.type = "checkbox";
    ui.any.addEventListener("change", function () {
      var s = ed.slots[ed.sel];
      if (!s) return;
      var base = s.key.split("#")[0];
      if (ui.any.checked) {
        if (s.key.slice(-2) !== "#*") s.exact = s.key;
        s.key = base + "#*";
        s.match = "";
      } else {
        s.key = s.exact || base;
        s.match = "";
      }
      s.unres = false;
      s.resolved = undefined;
      kbEdUpdate();
    });
    var anyLab = kitEl("label", "user-check kb-any-label");
    anyLab.appendChild(ui.any);
    anyLab.appendChild(document.createTextNode(" Any variant"));
    anyLab.title = "Any enchantments, potion type or other components count for this slot";
    selRow.appendChild(anyLab);
    ui.clear = kitEl("button", "mc-button small", "Empty this slot");
    ui.clear.type = "button";
    ui.clear.addEventListener("click", function () { ed.slots[ed.sel] = null; kbEdUpdate(); });
    selRow.appendChild(ui.clear);
    ui.selBox.appendChild(selRow);
    right.appendChild(ui.selBox);

    var modes = kitEl("div", "seg");
    modes.setAttribute("role", "group");
    modes.setAttribute("aria-label", "Item source");
    ui.modeStash = kitEl("button", "mc-button small seg-btn", "Items in the stash");
    ui.modeAny = kitEl("button", "mc-button small seg-btn", "Any item");
    ui.modeStash.type = "button";
    ui.modeAny.type = "button";
    ui.modeStash.addEventListener("click", function () { ed.mode = "stash"; kbEdUpdate(); });
    ui.modeAny.addEventListener("click", function () { ed.mode = "any"; kbEdUpdate(); });
    modes.appendChild(ui.modeStash);
    modes.appendChild(ui.modeAny);
    right.appendChild(modes);
    ui.search = document.createElement("input");
    ui.search.type = "search";
    ui.search.className = "mc-input";
    ui.search.placeholder = "Search the stash's items";
    ui.search.autocomplete = "off";
    ui.search.spellcheck = false;
    ui.search.addEventListener("input", function () { ed.search = ui.search.value; kbDrawPick(); });
    right.appendChild(ui.search);
    ui.anyBox = kitEl("div", "kits-chest-row");
    ui.anyId = document.createElement("input");
    ui.anyId.type = "text";
    ui.anyId.className = "mc-input";
    ui.anyId.placeholder = "Item id, e.g. minecraft:diamond";
    ui.anyId.autocomplete = "off";
    ui.anyId.spellcheck = false;
    ui.anyId.maxLength = 160;
    var useAny = kitEl("button", "mc-button small", "Use");
    useAny.type = "button";
    var useId = function () {
      var raw = ui.anyId.value.trim().toLowerCase();
      var id = raw.indexOf(":") < 0 ? "minecraft:" + raw : raw;
      if (!/^[a-z0-9_.-]{1,64}:[a-z0-9_.\/-]{1,128}$/.test(id) || isShulkerBoxId(id)) {
        toast("That isn't an item id. A shulker box can't go in a kit.");
        return;
      }
      kbPlace({ key: id, count: 1, match: "", exact: id });
      ui.anyId.value = "";
    };
    useAny.addEventListener("click", useId);
    ui.anyId.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter") {
        ev.preventDefault();
        useId();
      }
    });
    ui.anyBox.appendChild(ui.anyId);
    ui.anyBox.appendChild(useAny);
    right.appendChild(ui.anyBox);
    ui.anyHint = kitEl("div", "hint", "For an item the stash doesn't hold. The server checks the stack size when you save.");
    right.appendChild(ui.anyHint);
    ui.pick = kitEl("div", "item-grid picker-grid kb-pick");
    right.appendChild(ui.pick);
    ui.pickNote = kitEl("div", "hint");
    right.appendChild(ui.pickNote);
    cols.appendChild(right);
    main.appendChild(cols);

    // checks and buttons
    ui.checks = kitEl("div", "kits-banner kb-checks");
    main.appendChild(ui.checks);
    var btns = kitEl("div", "kits-chest-row");
    ui.save = kitEl("button", "mc-button primary", "Save");
    ui.save.type = "button";
    ui.save.addEventListener("click", kbSave);
    ui.cancel = kitEl("button", "mc-button", "Cancel");
    ui.cancel.type = "button";
    ui.cancel.addEventListener("click", function () {
      if (kbEdDirty() && !window.confirm("Throw away your changes to this kit?")) return;
      kbEdClose();
    });
    btns.appendChild(ui.save);
    btns.appendChild(ui.cancel);
    main.appendChild(btns);
    kbFillCategory();
    kbFillChest();
  }

  function kbFillCategory() {
    var ed = kb.ed;
    var ui = kb.ui;
    ui.cat.replaceChildren();
    kitAllCats().forEach(function (c) { ui.cat.appendChild(kitOption(c.id, c.name)); });
    ui.cat.appendChild(kitOption("__new__", "+ New category…"));
    if (!Array.prototype.some.call(ui.cat.options, function (o) { return o.value === ed.category; })) ui.cat.appendChild(kitOption(ed.category, kitCatName(ed.category)));
    ui.cat.value = ed.category;
  }

  /** The "Kept in" picker: the category's chest by default, else a container the bot reported as able to take kits. */
  function kbFillChest() {
    var ed = kb.ed;
    var ui = kb.ui;
    if (document.activeElement === ui.chest) return; // not under an open dropdown
    var cat = kitCatName(ed.category);
    ui.chest.replaceChildren();
    ui.chest.appendChild(kitOption("", cat + " chest (default)"));
    var list = kits.data && Array.isArray(kits.data.containers) ? kits.data.containers : [];
    var seen = false;
    list.forEach(function (c, i) {
      if (c.pickable !== true && c.id !== ed.chest) return;
      ui.chest.appendChild(kitOption(c.id, kitOptionText(c, i)));
      if (c.id === ed.chest) seen = true;
    });
    if (ed.chest && !seen) ui.chest.appendChild(kitOption(ed.chest, (streamerOn() ? "Chest" : kitCoords(ed.chest)) + " · not reported"));
    ui.chest.value = ed.chest;
    ui.chest.disabled = !kbCan() || !kits.data;
    ui.chest.title = kits.data ? "" : "The chests are not available here";
  }

  function kbNewCategory() {
    var ed = kb.ed;
    var name = window.prompt("Name for the new kind of kit (up to 32 characters):", "");
    if (name == null || !name.trim()) {
      kbFillCategory();
      return;
    }
    kbWrite("/api/v1/kits/category", { name: name.trim() }, "Category added.", function (d) {
      if (d && typeof d.id === "string" && kb.ed === ed) {
        ed.category = d.id;
        kbFillCategory();
        kbFillChest();
        kbEdUpdate();
      }
    });
    // A failed or pending write puts the picker back on the current category.
    kbFillCategory();
  }

  /** Puts an item into the selected slot (or the first empty one after it) and moves on. */
  function kbPlace(slot) {
    var ed = kb.ed;
    if (!ed || ed.saving) return;
    ed.slots[ed.sel] = slot;
    var next = -1;
    for (var k = 1; k <= 27; k++) {
      var j = (ed.sel + k) % 27;
      if (!ed.slots[j]) {
        next = j;
        break;
      }
    }
    if (next >= 0) ed.sel = next;
    kbEdUpdate();
  }

  var KB_PICK_MAX = 240;

  function kbDrawPick() {
    var ed = kb.ed;
    var ui = kb.ui;
    if (!ed || !ui) return;
    ui.search.hidden = ed.mode !== "stash";
    ui.anyBox.hidden = ed.mode !== "any";
    ui.anyHint.hidden = ed.mode !== "any";
    ui.pick.hidden = ed.mode !== "stash";
    ui.pickNote.hidden = ed.mode !== "stash";
    ui.modeStash.setAttribute("aria-pressed", ed.mode === "stash" ? "true" : "false");
    ui.modeAny.setAttribute("aria-pressed", ed.mode === "any" ? "true" : "false");
    if (ed.mode !== "stash") return;
    var pickSig = ed.search + "|" + state.items.length + "|" + (state.items.length ? state.items[0].item.key : "");
    if (ui.pickSig === pickSig && state.items === ui.pickSrc) return;
    ui.pickSig = pickSig;
    ui.pickSrc = state.items;
    var query = parseQuery(ed.search);
    var shown = [];
    var total = 0;
    for (var i = 0; i < state.items.length; i++) {
      var e = state.items[i];
      if (isShulkerBoxId(e.item.id) || !matches(e, query)) continue;
      total++;
      if (shown.length < KB_PICK_MAX) shown.push(e);
    }
    var frag = document.createDocumentFragment();
    shown.forEach(function (e) {
      var b = kitEl("button", "slot");
      b.type = "button";
      b.appendChild(iconImg(e.item.id, 32));
      var cn = kitEl("span", "count", e.item.count > 1 ? compact(e.item.count) : "");
      b.appendChild(cn);
      var det = e.item.details.length ? " (" + e.item.details.slice(0, 3).join(", ") + ")" : "";
      b.title = e.item.name + det + " – " + grouped(e.item.count) + " in the stash";
      b.setAttribute("aria-label", b.title);
      b.addEventListener("click", function () { kbPlace({ key: e.item.key, count: 1, match: "", exact: e.item.key }); });
      frag.appendChild(b);
    });
    ui.pick.replaceChildren(frag);
    ui.pickNote.textContent = !state.items.length ? "No stash index yet: use “Any item” to type an item id."
      : total > shown.length ? "Showing the first " + shown.length + " of " + total + " items. Search to narrow it down."
      : total === 0 ? "No item matches." : "";
  }

  function kbDrawSlots() {
    var ed = kb.ed;
    var ui = kb.ui;
    ed.slots.forEach(function (s, i) {
      var btn = ui.slots[i];
      var base = s ? s.key.split("#")[0] : "";
      if (btn.dataset.base !== base) {
        btn.dataset.base = base;
        var old = btn.querySelector("img");
        if (old) old.remove();
        if (base) btn.insertBefore(iconImg(base, 32), btn.firstChild);
      }
      var bad = !!s && kbSlotBad(s);
      var any = !!s && s.key.slice(-2) === "#*";
      btn.querySelector(".count").textContent = s && s.count > 1 ? compact(s.count) : "";
      btn.classList.toggle("selected", i === ed.sel);
      btn.classList.toggle("any", any);
      btn.classList.toggle("bad", bad || (!!s && s.over === true));
      btn.title = s ? kbItemLabel(s.key) + " ×" + s.count + (any ? " (any variant)" : "") + (bad ? ". " + KB_UNRESOLVED : "") : "Empty slot " + (i + 1);
      btn.setAttribute("aria-label", "Slot " + (i + 1) + ": " + (s ? btn.title : "empty"));
      btn.setAttribute("aria-pressed", i === ed.sel ? "true" : "false");
    });
  }

  function kbDrawSel(keepCount) {
    var ed = kb.ed;
    var ui = kb.ui;
    var s = ed.slots[ed.sel];
    ui.selName.textContent = s ? "Slot " + (ed.sel + 1) + ": " + kbItemLabel(s.key) + (s.key.slice(-2) === "#*" ? " (any variant)" : "") : "Slot " + (ed.sel + 1) + " is empty. Pick an item below.";
    ui.count.disabled = !s;
    ui.any.disabled = !s;
    ui.clear.disabled = !s;
    if (s) {
      var max = kbMaxStack(s.key);
      ui.count.max = String(max);
      if (!keepCount && document.activeElement !== ui.count) ui.count.value = String(s.count);
      ui.any.checked = s.key.slice(-2) === "#*";
    } else {
      ui.count.value = "";
      ui.any.checked = false;
    }
  }

  /** The live checks of the editor. canSave is true when none fails. */
  function kbEdChecks() {
    var ed = kb.ed;
    var res = { notes: [], canSave: true };
    var bases = {};
    var kinds = 0;
    var bad = [];
    var over = [];
    var sig = {};
    ed.slots.forEach(function (s, i) {
      if (!s) return;
      var base = s.key.split("#")[0];
      if (!bases[base]) {
        bases[base] = true;
        kinds++;
      }
      if (kbSlotBad(s)) bad.push(i + 1);
      var max = kbMaxStack(s.key);
      if (s.count > max) over.push(i + 1);
      var m = kbMatchOf(s);
      sig[m] = (sig[m] || 0) + s.count;
    });
    var name = ed.name.trim();
    if (!name) {
      res.notes.push(["off", "Give the kit a name."]);
      res.canSave = false;
    } else {
      var clash = ((kb.data && kb.data.templates) || []).filter(function (t) { return t.id !== ed.id && kbNorm(t.name) === kbNorm(name); })[0];
      if (clash) {
        res.notes.push(["off", "Another kit already has this name."]);
        res.canSave = false;
      }
    }
    if (kinds < 2) {
      res.notes.push(["off", "A kit needs at least two kinds of item" + (kinds === 1 ? " (it has one)." : ".")]);
      res.canSave = false;
    } else {
      res.notes.push(["ok", kinds + " kinds of item."]);
    }
    if (kinds >= 2) {
      var mine = Object.keys(sig).sort().map(function (k) { return k + "=" + sig[k]; }).join("|");
      var same = ((kb.data && kb.data.templates) || []).filter(function (t) {
        if (t.id === ed.id) return false;
        var m = {};
        (t.slots || []).forEach(function (s) { if (s) m[s.match || s.key] = (m[s.match || s.key] || 0) + s.count; });
        return Object.keys(m).sort().map(function (k) { return k + "=" + m[k]; }).join("|") === mine;
      })[0];
      if (same) {
        res.notes.push(["off", "Same contents as another kit" + (streamerOn() ? "." : ": " + str(same.name) + ".") + " Change a slot so the two can be told apart."]);
        res.canSave = false;
      } else {
        res.notes.push(["ok", "The contents are unique."]);
      }
    }
    if (bad.length) {
      res.notes.push(["off", KB_UNRESOLVED + " (slot" + (bad.length === 1 ? " " : "s ") + bad.join(", ") + ")."]);
      res.canSave = false;
    }
    if (over.length) {
      res.notes.push(["off", "A count is above the item's stack size (slot" + (over.length === 1 ? " " : "s ") + over.join(", ") + ")."]);
      res.canSave = false;
    }
    return res;
  }

  /** Redraws the parts of the editor that follow its data. keepCount: the count box is being typed in. */
  function kbEdUpdate(keepCount) {
    var ed = kb.ed;
    var ui = kb.ui;
    if (!ed || !ui) return;
    ed.slots.forEach(function (s) { if (s && s.over && s.count <= kbMaxStack(s.key)) s.over = false; });
    kbDrawSlots();
    kbDrawSel(keepCount);
    kbDrawPick();
    kbFillChest();
    var cap = kb.data && kb.data.capability && typeof kb.data.capability === "object" ? kb.data.capability : {};
    ui.restockNote.textContent = ed.keep > 0
      ? "The bot keeps " + ed.keep + " complete " + (ed.keep === 1 ? "copy" : "copies") + " of this kit in its chest." +
        (cap.restock === true ? "" : " This bot can't restock yet: the setting is saved for when it can.")
      : "";
    ui.restockNote.hidden = !ui.restockNote.textContent;
    var res = kbEdChecks();
    ui.checks.replaceChildren();
    res.notes.forEach(function (n) { kitNote(ui.checks, n[1], n[0]); });
    if (ed.error) {
      ui.error.textContent = ed.error;
      if (streamerOn()) ui.error.setAttribute("data-sensitive", "");
      else ui.error.removeAttribute("data-sensitive");
      ui.error.hidden = false;
    } else {
      ui.error.hidden = true;
    }
    var editable = kbCan() && !ed.saving;
    ui.slots.forEach(function (b) { b.disabled = !!ed.saving; });
    if (ed.saving) {
      ui.count.disabled = true;
      ui.any.disabled = true;
      ui.clear.disabled = true;
      ui.chest.disabled = true;
      ui.modeStash.disabled = true;
      ui.modeAny.disabled = true;
    } else {
      ui.modeStash.disabled = false;
      ui.modeAny.disabled = false;
    }
    Array.prototype.forEach.call(ui.pick.querySelectorAll("button"), function (b) { b.disabled = !!ed.saving; });
    [ui.name, ui.cat, ui.color, ui.restock, ui.keep, ui.search, ui.anyId].forEach(function (c) { c.disabled = !editable; });
    ui.save.disabled = !editable || !res.canSave || !kbEdDirty();
    ui.cancel.disabled = ed.saving;
  }

  function kbSave() {
    var ed = kb.ed;
    if (!ed || ed.saving) return;
    var res = kbEdChecks();
    if (!res.canSave || !kbCan()) return;
    ed.saving = true;
    ed.error = "";
    kbEdUpdate();
    kbWrite("/api/v1/kits/template", { template: kbEdBody(ed) }, "Saved. The bot picks it up in a few seconds.", function () {
      kb.ed = null;
      kb.ui = null;
    });
  }

  // ---------- dashboard: pages, bot picker ----------
  // Stash and Orders are the inventory view with its creative tabs; Bots, Settings and Console are dashboard pages
  // for one proxy bot, chosen in the header's bot picker (remembered per browser).

  var BOT_KEY = "stashManager.bot";
  var PAGE_KEY = "stashManager.page";
  var CONSOLE_CONFIRM = ["shutdown", "update", "disconnect", "auth", "jvmargs", "plugins", "releasechannel", "discordmanage"];
  var dash = {
    page: "stash",
    bots: [],
    botId: null,
    botsTimer: 0,
    // settings
    s: null,          // last GET settings reply
    sEtag: null,
    sBot: null,       // bot the form was built for
    sSchemaHash: null,
    fields: new Map(), // path -> field
    edits: new Map(),  // path -> new value
    jobs: new Map(),   // job id -> path
    sTimer: 0,
    jobTimer: 0,
    // console
    cBot: null,
    cRev: 0,
    entries: new Map(), // seq -> { data, el }
    cTimer: 0,
    history: [],
    histPos: -1
  };

  try {
    dash.botId = window.localStorage.getItem(BOT_KEY);
  } catch (e) {
    dash.botId = null;
  }

  function currentBot() {
    for (var i = 0; i < dash.bots.length; i++) if (dash.bots[i].botId === dash.botId) return dash.bots[i];
    return null;
  }

  function botName(b) {
    if (!str(b.player) && forcedStreamer()) {
      // Forced streamer mode: no player name and only an opaque id from the server; "Bot 1" reads better.
      var i = dash.bots.indexOf(b);
      return "Bot " + (i >= 0 ? i + 1 : 1);
    }
    return str(b.player) || str(b.botId);
  }

  function showPage(page) {
    if (!pageAllowed(page)) page = "stash";
    var inventory = page === "stash" || page === "orders";
    dash.page = page;
    el.inventory.hidden = !inventory;
    el.tabsWrap.hidden = !inventory;
    el.pageBots.hidden = page !== "bots";
    el.pageSettings.hidden = page !== "settings";
    el.pageConsole.hidden = page !== "console";
    el.pageProxy.hidden = page !== "proxy";
    el.pageActivity.hidden = page !== "activity";
    el.pagePlanner.hidden = page !== "planner";
    if (vel) vel.page.hidden = page !== "viewer"; // (the viewer's code below may not have run yet)
    var navs = el.dashNav.querySelectorAll(".nav-btn");
    for (var i = 0; i < navs.length; i++) navs[i].classList.toggle("selected", navs[i].dataset.page === page);
    hideTooltip();
    if (page === "orders") selectTab("orders");
    else if (page === "stash" && state.tab === "orders") selectTab("all");
    if (page === "bots") renderDashBots();
    window.clearTimeout(dash.cTimer);
    settingsForm.stop();
    proxyForm.stop();
    if (page === "settings") {
      settingsForm.load(true);
      if (me.owner) loadUsers();
    }
    if (page === "proxy") {
      renderProxyScopes();
      proxyForm.load(true);
    }
    if (page === "console") loadConsole();
    window.clearTimeout(act.timer);
    if (page === "activity") loadActivity();
    if (page === "planner") plannerOpen();
    else plannerClose();
    if (page === "viewer") viewerOpen();
    else viewerClose();
    kitsSync();
    kbSync();
  }

  el.dashNav.addEventListener("click", function (e) {
    var b = e.target.closest ? e.target.closest(".nav-btn") : null;
    if (b) {
      // Remembered per browser: the page to open next time.
      try {
        window.localStorage.setItem(PAGE_KEY, b.dataset.page);
      } catch (err) {
        // not remembered
      }
      showPage(b.dataset.page);
    }
  });

  function pollBots() {
    window.clearTimeout(dash.botsTimer);
    dash.botsTimer = window.setTimeout(pollBots, 10000);
    if (auth.blocked) return;
    // Without "activity" the server refuses the bot list: no bots for this user.
    var request = can("activity") ? api("/api/v1/bots") : Promise.resolve([]);
    request.then(function (list) {
      dash.bots = Array.isArray(list) ? list.filter(function (b) { return b && typeof b === "object" && typeof b.botId === "string"; }) : [];
      if (!currentBot() && dash.bots.length) dash.botId = dash.bots[0].botId;
      var first = !dash.botsLoaded;
      dash.botsLoaded = true;
      if (first && !state.unreachable) maybeAutoOpenLink();
      renderBotSelect();
      if (dash.page === "bots") renderDashBots();
      updateDashTitles();
      updateProxyNav();
      updateActivityNav(first);
      if (dash.page === "proxy") {
        var before = proxyScope;
        renderProxyScopes();
        if (proxyScope !== before) proxyForm.load(true);
      }
    }, function () {});
  }

  function renderBotSelect() {
    var frag = document.createDocumentFragment();
    if (!dash.bots.length) {
      var none = document.createElement("option");
      none.value = "";
      none.textContent = "(no bots)";
      frag.appendChild(none);
    }
    dash.bots.forEach(function (b) {
      var o = document.createElement("option");
      o.value = b.botId;
      o.textContent = (b.online ? "● " : "○ ") + botName(b);
      if (str(b.summary) && !streamerOn()) o.title = str(b.summary);
      frag.appendChild(o);
    });
    el.botSelect.replaceChildren(frag);
    el.botSelect.value = dash.botId || "";
    var cb = currentBot();
    el.botSelect.title = cb && str(cb.summary) && !streamerOn() ? "Now: " + str(cb.summary) : "";
    el.botSelect.disabled = dash.bots.length <= 1;
  }

  function selectBot(id) {
    if (!id || id === dash.botId) return;
    dash.botId = id;
    try {
      window.localStorage.setItem(BOT_KEY, id);
    } catch (e) {
      // not remembered
    }
    el.botSelect.value = id;
    updateDashTitles();
    updateProxyNav();
    updateActivityNav(false);
    if (dash.page === "activity") loadActivity();
    if (dash.page === "settings") settingsForm.load(true);
    if (dash.page === "proxy") {
      renderProxyScopes();
      proxyForm.load(true);
    }
    if (dash.page === "console") loadConsole();
    if (dash.page === "bots") renderDashBots();
    if (dash.page === "viewer") viewerBotChanged();
  }

  el.botSelect.addEventListener("change", function () { selectBot(el.botSelect.value); });

  function updateDashTitles() {
    var b = currentBot();
    el.settingsTitle.textContent = "Settings" + (b ? " · " + botName(b) : "");
    el.consoleTitle.textContent = "Console" + (b ? " · " + botName(b) : "");
    el.proxyTitle.textContent = "Proxy" + (b ? " · " + botName(b) : "");
    el.activityTitle.textContent = "Activity" + (b ? " · " + botName(b) : "");
    $("viewer-title").textContent = "Live" + (b ? " · " + botName(b) : "");
  }

  function offlineText(b) {
    return b ? botName(b) + " is offline (last seen " + ago(num(b.lastSeen, null)) + "). " : "No bot selected. ";
  }

  // ---------- dashboard: Bots page ----------

  function renderDashBots() {
    var frag = document.createDocumentFragment();
    if (!dash.bots.length) {
      frag.appendChild(line("note", "No dashboard bots yet. In the proxy, turn the Qwerte Stash plugin's dashboard on (stash manager dashboard on)."));
    }
    dash.bots.forEach(function (b) {
      var card = document.createElement("div");
      card.className = "dash-bot" + (b.botId === dash.botId ? " selected" : "");
      var head = document.createElement("div");
      head.className = "dash-bot-head";
      var dot = document.createElement("span");
      setDot(dot, !!b.online);
      dot.classList.add("only-desktop");
      head.appendChild(dot);
      var name = document.createElement("span");
      name.className = "dash-bot-name";
      name.textContent = botName(b);
      head.appendChild(name);
      var chip = document.createElement("span");
      chip.className = "status-chip " + (b.online ? "ok" : "off");
      chip.textContent = b.online ? "Online" : "Offline";
      head.appendChild(chip);
      head.appendChild(line("note", "last seen " + ago(num(b.lastSeen, null))));
      card.appendChild(head);
      if (str(b.summary)) {
        var doing = line("dash-bot-doing", "Now: ");
        doing.appendChild(hiddenSpan(str(b.summary)));
        card.appendChild(doing);
      }
      var plugin = b.plugin && typeof b.plugin === "object" ? b.plugin : {};
      var proxy = b.proxy && typeof b.proxy === "object" ? b.proxy : {};
      card.appendChild(line("note dash-bot-meta", "Plugin " + (str(plugin.id) || "?") + " " + str(plugin.version) + " · proxy " + (str(proxy.version) || "?") +
        (str(proxy.mc) ? " (MC " + str(proxy.mc) + ")" : "") + " · id " + str(b.botId)));
      var stashes = Array.isArray(b.stashes) ? b.stashes : [];
      var st = document.createElement("div");
      st.className = "dash-bot-stashes";
      st.appendChild(document.createTextNode(stashes.length ? "Stashes: " : "No stash linked to this player."));
      stashes.forEach(function (s) {
        var link = document.createElement("button");
        link.type = "button";
        link.className = "crumb";
        link.textContent = str(s.stash) + " @ ";
        link.appendChild(hiddenSpan(str(s.server)));
        link.addEventListener("click", function () {
          selectStash(stashKey(s));
          showPage("stash");
        });
        st.appendChild(link);
        st.appendChild(document.createTextNode(" "));
      });
      card.appendChild(st);
      var actions = document.createElement("div");
      actions.className = "dash-bot-actions";
      var caps = Array.isArray(b.caps) ? b.caps : [];
      [["activity", "Activity"], ["settings", "Settings"], ["proxy", "Proxy"], ["console", "Console"]].forEach(function (p) {
        if (caps.indexOf(p[0]) < 0 || !pageAllowed(p[0])) return;
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "mc-button small";
        btn.textContent = p[1];
        btn.addEventListener("click", function () {
          selectBot(b.botId);
          showPage(p[0]);
        });
        actions.appendChild(btn);
      });
      card.appendChild(actions);
      frag.appendChild(card);
    });
    el.dashBots.replaceChildren(frag);
    bots.body = el.stashBots;
    renderBots();
  }

  // ---------- dashboard: settings forms ----------
  // One engine for the Settings page (scope "plugin") and the Proxy page (scope "proxy" or "plugin:<id>"). The proxy
  // tree can have thousands of settings, so the Proxy page only renders the selected section (or the search hits).
  // Edits are kept by path, so they survive switching sections; Save sends them all in one batch.

  function jsonEq(a, b) {
    return JSON.stringify(a === undefined ? null : a) === JSON.stringify(b === undefined ? null : b);
  }

  function fmtValue(v) {
    if (v === null || v === undefined) return "(none)";
    if (Array.isArray(v)) return v.length ? v.join(", ") : "(empty)";
    if (v === true) return "ON";
    if (v === false) return "OFF";
    return String(v);
  }

  function lastSegment(path) {
    var i = path.lastIndexOf(".");
    return i < 0 ? path : path.slice(i + 1);
  }

  /**
   * opts: { page, scope() -> scope, sectioned, sudo(scope) -> bool, els: { title, status, save, revert, offline, body,
   * nav?, search?, scopeSelect? }, titleText }
   */
  function makeForm(opts) {
    var F = {
      s: null, etag: null, bot: null, scope: null, schemaHash: null,
      fields: new Map(),   // rendered fields only: path -> field
      edits: new Map(),    // path -> new value (undefined = invalid)
      jobs: new Map(),     // job id -> { path, sent }
      notes: new Map(),    // path -> { kind, text } shown next to the field (kept while not rendered)
      busy: new Set(),     // paths with a job in flight
      timer: 0, jobTimer: 0,
      leaves: [], byPath: new Map(), sections: [], section: null, query: ""
    };
    var E = opts.els;

    function active() {
      return dash.page === opts.page;
    }

    /** The user's permissions allow reading but not saving (the server refuses a save anyway). */
    function readOnly() {
      return !!(opts.readOnly && opts.readOnly());
    }

    function serverValue(path) {
      var v = F.s && F.s.values ? F.s.values[path] : undefined;
      return v === undefined ? null : v;
    }

    function online() {
      var b = currentBot();
      return F.s ? !!F.s.online : !!(b && b.online);
    }

    function reset() {
      F.s = null;
      F.etag = null;
      F.schemaHash = null;
      F.edits.clear();
      F.fields.clear();
      F.notes.clear();
      F.busy.clear();
      F.jobs.clear();
      F.leaves = [];
      F.byPath = new Map();
      F.sections = [];
      F.section = null;
      E.body.replaceChildren();
      if (E.nav) E.nav.replaceChildren();
    }

    function load(force) {
      window.clearTimeout(F.timer);
      if (!active()) return;
      var b = currentBot();
      var scope = opts.scope();
      if (F.bot !== dash.botId || F.scope !== scope) {
        reset();
        F.bot = dash.botId;
        F.scope = scope;
      }
      if (force) F.etag = null;
      var denied = opts.denied ? opts.denied() : "";
      if (denied) {
        // Not polled: a permission change reloads the page (applyPerms).
        E.body.replaceChildren(line("note", denied));
        if (E.nav) E.nav.replaceChildren();
        E.offline.hidden = true;
        updateBar();
        return;
      }
      if (b && opts.cap && !hasCap(b, opts.cap)) {
        E.body.replaceChildren(capHint(opts.cap));
        if (E.nav) E.nav.replaceChildren();
        E.offline.hidden = true;
        updateBar();
        F.timer = window.setTimeout(function () { load(false); }, 5000);
        return;
      }
      if (!b || !scope) {
        E.body.replaceChildren(line("note", !b ? "No dashboard bot yet." : "This bot has no settings here."));
        E.offline.hidden = true;
        updateBar();
        F.timer = window.setTimeout(function () { load(false); }, 5000);
        return;
      }
      var botId = b.botId;
      var headers = {};
      if (F.etag) headers["If-None-Match"] = F.etag;
      fetch("/api/v1/bots/" + encodeURIComponent(botId) + "/settings?scope=" + encodeURIComponent(scope), {
        credentials: "same-origin", cache: "no-store", headers: headers
      }).then(function (res) {
        if (res.status === 401) {
          loginRequired();
          return null;
        }
        if (res.status === 304) return null;
        if (res.status === 403) {
          return res.json().then(function (body) { return body; }, function () { return null; }).then(function (body) {
            if (body && body.mustChange === true) {
              mustChangeRequired();
            } else if (body && typeof body.permission === "string") {
              // The user's permissions don't cover these settings (they may have just changed).
              E.body.replaceChildren(line("note", sentence(str(body.error)) || "Your account can't see these settings."));
              permsStale();
            } else {
              // Sudo mode needed to see these settings: offer the password prompt instead of polling.
              E.body.replaceChildren(unlockBox("The proxy settings are only shown after you confirm the website password.", function () { load(true); }));
            }
            if (E.nav) E.nav.replaceChildren();
            return { locked: true };
          });
        }
        return res.json().then(function (body) { return { status: res.status, body: body, etag: res.headers.get("ETag") }; });
      }).then(function (r) {
        if (r && r.locked) {
          F.locked = true;
          return;
        }
        F.locked = false;
        if (!r || botId !== dash.botId || scope !== opts.scope() || !active()) return;
        if (r.status === 200) {
          F.s = r.body;
          F.etag = r.etag;
          apply();
        } else if (r.status === 404) {
          F.s = null;
          E.body.replaceChildren(line("note", r.body && r.body.loading ? "Asking the bot for these settings..." :
            r.body && r.body.online === false ? "The bot is offline; its settings show here once it is back." : "This bot hasn't sent these settings yet."));
        }
      }, function () {}).then(function () {
        updateOffline();
        if (active() && !F.locked) F.timer = window.setTimeout(function () { load(false); }, F.s ? 3000 : 1000);
      });
    }

    function updateOffline() {
      var b = currentBot();
      var on = online();
      E.offline.hidden = on || !b;
      E.offline.textContent = offlineText(b) + "Settings are read-only until it is back.";
      var ro = readOnly();
      F.fields.forEach(function (f) { f.setDisabled(!on || f.locked || ro || F.busy.has(f.node.path)); });
      updateBar();
    }

    // ----- the tree -----

    function collect(nodes, trail, top, out) {
      nodes.forEach(function (n) {
        if (!n || typeof n !== "object" || typeof n.path !== "string") return;
        if (n.type === "group") {
          var label = str(n.label) || n.path;
          collect(Array.isArray(n.children) ? n.children : [], top === null ? trail : trail.concat([label]), top === null ? label : top, out);
          return;
        }
        var section = str(n.section) || top || "Other";
        out.push({
          node: n, section: section, trail: trail,
          hay: (n.path + "\n" + str(n.label) + "\n" + str(n.help) + "\n" + section).toLowerCase()
        });
      });
    }

    function apply() {
      var s = F.s;
      if (!s || !Array.isArray(s.schema)) return;
      if (F.schemaHash !== s.schemaHash) {
        F.schemaHash = s.schemaHash;
        F.leaves = [];
        collect(s.schema, [], null, F.leaves);
        F.byPath = new Map();
        F.leaves.forEach(function (l) { F.byPath.set(l.node.path, l); });
        var seen = new Map();
        F.sections = [];
        F.leaves.forEach(function (l) {
          if (!seen.has(l.section)) {
            seen.set(l.section, { name: l.section, count: 0 });
            F.sections.push(seen.get(l.section));
          }
          seen.get(l.section).count++;
        });
        F.edits.forEach(function (v, path) {
          var l = F.byPath.get(path);
          if (!l || l.node.readOnly === true || l.node.secret === true) F.edits.delete(path);
        });
        if (!F.section || !seen.has(F.section)) F.section = F.sections.length ? F.sections[0].name : null;
        applyPending(false);
        render();
      } else {
        F.fields.forEach(function (f, path) {
          if (F.edits.has(path) || F.busy.has(path) || f.row.contains(document.activeElement)) return;
          f.set(serverValue(path));
        });
      }
    }

    function render() {
      F.fields.clear();
      if (opts.sectioned) renderNav();
      var leaves;
      if (!opts.sectioned) {
        leaves = F.leaves;
      } else if (F.query) {
        leaves = F.leaves.filter(function (l) { return l.hay.indexOf(F.query) >= 0; }).slice(0, 300);
      } else {
        leaves = F.leaves.filter(function (l) { return l.section === F.section; });
      }
      var frag = document.createDocumentFragment();
      if (readOnly()) frag.appendChild(line("note read-only-note", "Read only: your account can't change these settings."));
      if (opts.sectioned && !leaves.length) frag.appendChild(line("note", F.query ? "Nothing matches." : "No settings."));
      // Module switches first, highlighted ("enabled" toggles at the top of a section's tree)
      if (opts.sectioned && !F.query) {
        var minDepth = Infinity;
        leaves.forEach(function (l) { minDepth = Math.min(minDepth, l.trail.length); });
        var toggles = leaves.filter(function (l) {
          var seg = lastSegment(l.node.path).toLowerCase();
          return l.node.type === "bool" && (seg === "enabled" || seg === "enable") && l.trail.length === minDepth;
        });
        if (toggles.length) {
          var box = document.createElement("div");
          box.className = "module-toggles";
          toggles.forEach(function (l) { box.appendChild(buildField(l, "module-enable")); });
          frag.appendChild(box);
          leaves = leaves.filter(function (l) { return toggles.indexOf(l) < 0; });
        }
      }
      var sec = null;
      var groupKey = null;
      var holder = null;
      leaves.forEach(function (l) {
        var key = (F.query || !opts.sectioned ? l.section + "\u0000" : "") + l.trail.join(" › ");
        if (!opts.sectioned && l.section !== sec) {
          sec = l.section;
          holder = document.createElement("section");
          holder.className = "inset settings-section";
          holder.appendChild(line("section-title", sec));
          frag.appendChild(holder);
          groupKey = null;
        }
        if (!holder) {
          holder = document.createElement("section");
          holder.className = "inset settings-section";
          frag.appendChild(holder);
        }
        if (key !== groupKey) {
          groupKey = key;
          var title = (F.query && opts.sectioned ? l.section + (l.trail.length ? " › " : "") : "") + l.trail.join(" › ");
          if (title) holder.appendChild(line("settings-group-title", title));
        }
        holder.appendChild(buildField(l, ""));
      });
      E.body.replaceChildren(frag);
      if (F.query && leaves.length === 300) E.body.appendChild(line("note", "Showing the first 300 matches. Type more to narrow it down."));
      updateOffline();
    }

    function renderNav() {
      if (!E.nav) return;
      var counts = new Map();
      if (F.query) F.leaves.forEach(function (l) { if (l.hay.indexOf(F.query) >= 0) counts.set(l.section, (counts.get(l.section) || 0) + 1); });
      var dirty = new Map();
      F.edits.forEach(function (v, path) {
        var l = F.byPath.get(path);
        if (l) dirty.set(l.section, (dirty.get(l.section) || 0) + 1);
      });
      var frag = document.createDocumentFragment();
      F.sections.forEach(function (sct) {
        if (F.query && !counts.has(sct.name)) return;
        var b = document.createElement("button");
        b.type = "button";
        b.className = "section-item" + (!F.query && sct.name === F.section ? " selected" : "");
        var name = document.createElement("span");
        name.className = "section-name";
        name.textContent = sct.name;
        b.appendChild(name);
        var n = document.createElement("span");
        n.className = "section-count";
        n.textContent = String(F.query ? counts.get(sct.name) : sct.count);
        b.appendChild(n);
        if (dirty.get(sct.name)) {
          var d = document.createElement("span");
          d.className = "section-dirty";
          d.textContent = "●" + dirty.get(sct.name);
          d.title = dirty.get(sct.name) + " unsaved";
          b.appendChild(d);
        }
        b.addEventListener("click", function () {
          F.section = sct.name;
          if (F.query) {
            F.query = "";
            if (E.search) E.search.value = "";
          }
          render();
          E.body.scrollTop = 0;
        });
        frag.appendChild(b);
      });
      E.nav.replaceChildren(frag);
    }

    function buildField(l, extraClass) {
      var node = l.node;
      var row = document.createElement("div");
      row.className = "setting" + (extraClass ? " " + extraClass : "");
      var labelBox = document.createElement("div");
      labelBox.className = "setting-label";
      labelBox.appendChild(line("setting-name", str(node.label) || node.path));
      if (str(node.help)) labelBox.appendChild(line("setting-help", str(node.help)));
      if (opts.sectioned) labelBox.appendChild(line("setting-path", node.path));
      row.appendChild(labelBox);
      var box = document.createElement("div");
      box.className = "setting-control";
      var status = document.createElement("div");
      status.className = "setting-status";
      var locked = node.readOnly === true || node.secret === true;
      var f = { node: node, row: row, status: status, locked: locked };
      if (node.secret === true) {
        var badge = document.createElement("span");
        badge.className = "secret-badge " + (node.set ? "set" : "unset");
        badge.textContent = node.set ? "secret · set" : "secret · not set";
        box.appendChild(badge);
        f.set = function () {};
        f.get = function () { return { ok: true, value: null }; };
        f.setDisabled = function () {};
      } else {
        makeControl(f, box, function () { edited(f); }, serverValue);
        f.set(F.edits.has(node.path) && F.edits.get(node.path) !== undefined ? F.edits.get(node.path) : serverValue(node.path));
        if (F.edits.has(node.path)) row.classList.add("dirty");
      }
      if (node.secret === true || sensitivePath(node.path)) hide(box);
      if (locked && str(node.reason) && node.secret !== true) box.appendChild(line("setting-reason", str(node.reason)));
      if (locked) row.classList.add("locked");
      row.appendChild(box);
      row.appendChild(status);
      var note = F.notes.get(node.path);
      if (F.busy.has(node.path)) note = { kind: "busy", text: "applying" };
      if (note) setNote(f, note.kind, note.text);
      F.fields.set(node.path, f);
      return row;
    }

    function setNote(f, kind, text) {
      f.status.className = "setting-status" + (kind ? " " + kind : "");
      f.status.textContent = text || "";
    }

    function note(path, kind, text) {
      if (text) F.notes.set(path, { kind: kind, text: text });
      else F.notes.delete(path);
      var f = F.fields.get(path);
      if (f) setNote(f, kind, text);
    }

    function edited(f) {
      var path = f.node.path;
      var got = f.get();
      if (!got.ok) {
        F.edits.set(path, undefined);
        f.row.classList.add("dirty", "invalid");
        note(path, "error", got.error);
      } else {
        f.row.classList.remove("invalid");
        if (jsonEq(got.value, serverValue(path))) {
          F.edits.delete(path);
          f.row.classList.remove("dirty");
        } else {
          F.edits.set(path, got.value);
          f.row.classList.add("dirty");
        }
        note(path, "", "");
      }
      updateBar();
      if (opts.sectioned) renderNav();
    }

    function updateBar() {
      var n = F.edits.size;
      var invalid = false;
      F.edits.forEach(function (v) { if (v === undefined) invalid = true; });
      var busy = F.jobs.size > 0;
      E.save.hidden = readOnly();
      E.revert.hidden = readOnly();
      E.save.disabled = !n || invalid || !online() || busy;
      E.revert.disabled = !n || busy;
      E.save.textContent = n ? "Save (" + n + ")" : "Save";
      E.status.textContent = busy ? "Applying..." : invalid ? "Fix the marked values first." : n ? n + " unsaved change" + (n === 1 ? "" : "s") : "";
    }

    E.revert.addEventListener("click", function () {
      F.edits.forEach(function (v, path) {
        note(path, "", "");
        var f = F.fields.get(path);
        if (!f) return;
        f.set(serverValue(path));
        f.row.classList.remove("dirty", "invalid");
      });
      F.edits.clear();
      updateBar();
      if (opts.sectioned) renderNav();
    });

    E.save.addEventListener("click", function () {
      var b = currentBot();
      if (!b || !F.edits.size) return;
      var changes = [];
      F.edits.forEach(function (v, path) { changes.push({ path: path, value: v, expect: serverValue(path) }); });
      var botId = b.botId;
      var scope = F.scope;
      E.save.disabled = true;
      E.status.textContent = "Saving...";
      var post = function () {
        return api("/api/v1/bots/" + encodeURIComponent(botId) + "/settings", { method: "POST", body: { scope: scope, changes: changes } });
      };
      (opts.sudo(scope) ? withSudo(post) : post()).then(function (r) {
        (r && Array.isArray(r.jobs) ? r.jobs : []).forEach(function (j) {
          F.jobs.set(j.id, { path: j.path, sent: F.edits.get(j.path) });
          F.busy.add(j.path);
          note(j.path, "busy", "applying");
          var f = F.fields.get(j.path);
          if (f) f.setDisabled(true);
        });
        updateBar();
        pollJobs(botId);
      }, function (e) {
        var body = e && e.body;
        if (body && body.error === "cancelled") {
          updateBar();
          return;
        }
        if (e && e.status === 409) {
          E.status.textContent = body && body.error === "bot offline" ? "The bot is offline: nothing was changed." : (body && body.error) || "Refused.";
          load(true);
        } else if (body && body.path) {
          note(body.path, "error", body.error);
          var l = F.byPath.get(body.path);
          if (opts.sectioned && l && !F.fields.has(body.path)) {
            F.section = l.section;
            F.query = "";
            if (E.search) E.search.value = "";
            render();
          }
          E.status.textContent = "Not saved: fix the marked value.";
        } else {
          E.status.textContent = "Not saved: " + ((body && body.error) || "HTTP " + (e && e.status));
        }
        updateBar();
      });
    });

    function pollJobs(botId) {
      window.clearTimeout(F.jobTimer);
      if (!F.jobs.size) return;
      var ids = Array.from(F.jobs.keys());
      api("/api/v1/bots/" + encodeURIComponent(botId) + "/jobs?ids=" + encodeURIComponent(ids.join(","))).then(function (list) {
        var conflict = false;
        (Array.isArray(list) ? list : []).forEach(function (j) {
          var job = F.jobs.get(j.id);
          if (!job || j.status === "queued" || j.status === "sent") return;
          F.jobs.delete(j.id);
          F.busy.delete(job.path);
          var f = F.fields.get(job.path);
          var value = j.value === undefined ? null : j.value;
          if (j.status === "done" || (j.status === "error" && j.error === "conflict")) {
            if (F.s && F.s.values) F.s.values[job.path] = value;
          }
          if (j.status === "done") {
            if (jsonEq(F.edits.get(job.path), job.sent)) {
              F.edits.delete(job.path);
              if (f) {
                f.row.classList.remove("dirty");
                f.set(value);
              }
            }
            note(job.path, "ok", "applied ✓ " + fmtValue(value));
          } else if (j.status === "error" && j.error === "conflict") {
            conflict = true;
            F.edits.delete(job.path);
            if (f) {
              f.row.classList.remove("dirty");
              f.set(value);
            }
            note(job.path, "error", "changed elsewhere meanwhile; reloaded (now " + fmtValue(value) + ")");
          } else if (j.status === "error") {
            note(job.path, "error", str(j.error) || "failed");
          } else {
            note(job.path, "error", j.status === "expired" ? "the bot didn't pick it up" : "no answer from the bot");
          }
          if (f) f.setDisabled(!online() || f.locked || readOnly());
        });
        updateBar();
        if (opts.sectioned) renderNav();
        if (conflict) load(true);
        if (F.jobs.size) F.jobTimer = window.setTimeout(function () { pollJobs(botId); }, 700);
      }, function () {
        F.jobTimer = window.setTimeout(function () { pollJobs(botId); }, 2000);
      });
    }

    if (E.search) {
      var searchTimer = 0;
      E.search.addEventListener("input", function () {
        window.clearTimeout(searchTimer);
        searchTimer = window.setTimeout(function () {
          F.query = E.search.value.trim().toLowerCase();
          render();
        }, 150);
      });
    }

    /** Open a section by name (case-insensitive) now or once the schema is in. */
    function jump(name) {
      F.pending = String(name || "").toLowerCase();
      F.query = "";
      if (E.search) E.search.value = "";
      if (F.sections.length) applyPending(true);
    }

    function applyPending(rerender) {
      if (!F.pending) return;
      for (var i = 0; i < F.sections.length; i++) {
        if (F.sections[i].name.toLowerCase() === F.pending) {
          F.section = F.sections[i].name;
          break;
        }
      }
      F.pending = null;
      if (rerender) render();
    }

    return { load: load, state: F, jump: jump, stop: function () { window.clearTimeout(F.timer); } };
  }

  /**
   * Builds the control for a node into box and gives f set(v), get() -> {ok, value, error}, setDisabled(bool).
   * onEdit runs on every change; serverValue(path) is the value the server has.
   */
  /** Settings whose value is a server address, port, URL or IP: blurred in streamer mode. Matched on whole
   *  words of the last path segment ("port", "serverPort", "managerUrl"), never inside one ("autoReport"). */
  var SENSITIVE_SECTION = /(^|\.)(server\.bind|client\.server|connectionProxy)(\.|$)/i;
  var SENSITIVE_WORD = /^(address|addresses|host|hostname|ip|ips|port|url|urls|uri|domain|endpoint)$/i;
  var SENSITIVE_SUFFIX = /[a-z0-9](Address|Addresses|Host|Hostname|Ip|IP|Ips|IPs|Port|Url|URL|Urls|Uri|URI|Domain|Endpoint)$/;

  function sensitivePath(path) {
    path = String(path || "");
    var last = path.slice(path.lastIndexOf(".") + 1);
    return SENSITIVE_SECTION.test(path) || SENSITIVE_WORD.test(last) || SENSITIVE_SUFFIX.test(last);
  }

  function makeControl(f, box, onEdit, serverValue) {
    var node = f.node;
    var type = node.type;
    var nullable = node.nullable === true;
    if (type === "bool") {
      var t = document.createElement("button");
      t.type = "button";
      t.className = "mc-button toggle";
      t.setAttribute("role", "switch");
      t.setAttribute("aria-label", str(node.label) || node.path);
      var tText = document.createElement("span");
      tText.className = "toggle-text";
      t.appendChild(tText);
      var cur = false;
      var isNull = false;
      var show = function () {
        tText.textContent = isNull ? "(none)" : cur ? "ON" : "OFF";
        t.classList.toggle("on", !isNull && cur);
        t.classList.toggle("is-null", isNull);
        t.setAttribute("aria-checked", !isNull && cur ? "true" : "false");
      };
      t.addEventListener("click", function () {
        cur = isNull ? true : !cur;
        isNull = false;
        show();
        onEdit();
      });
      box.appendChild(t);
      f.set = function (v) { isNull = v === null || v === undefined; cur = v === true; show(); };
      f.get = function () { return { ok: true, value: isNull ? null : cur }; };
      f.setDisabled = function (d) { t.disabled = d; };
      return;
    }
    if (type === "enum") {
      var sel = document.createElement("select");
      sel.className = "mc-select";
      if (nullable) {
        var none = document.createElement("option");
        none.value = "\u0000";
        none.textContent = "(none)";
        sel.appendChild(none);
      }
      (Array.isArray(node.options) ? node.options : []).forEach(function (o) {
        var opt = document.createElement("option");
        opt.value = String(o);
        opt.textContent = String(o);
        sel.appendChild(opt);
      });
      sel.addEventListener("change", onEdit);
      box.appendChild(sel);
      f.set = function (v) { sel.value = v === null || v === undefined ? "\u0000" : String(v); };
      f.get = function () { return { ok: true, value: sel.value === "\u0000" ? null : sel.value }; };
      f.setDisabled = function (d) { sel.disabled = d; };
      return;
    }
    if (type === "stringList") {
      chipEditor(f, box, onEdit, serverValue);
      return;
    }
    // Numbers and strings: a text field, plus "unset" for nullable ones.
    var input = document.createElement("input");
    input.className = "mc-input setting-input";
    var numeric = type === "int" || type === "long" || type === "double";
    if (numeric) {
      input.type = type === "long" ? "text" : "number";
      input.inputMode = type === "double" ? "decimal" : "numeric";
      if (type !== "long") {
        if (typeof node.min === "number") input.min = String(node.min);
        if (typeof node.max === "number") input.max = String(node.max);
        input.step = type === "double" ? "any" : "1";
      }
      var range = [];
      if (typeof node.min === "number") range.push("min " + node.min);
      if (typeof node.max === "number" && Math.abs(node.max) < 1e15) range.push("max " + node.max);
      if (range.length) input.title = range.join(", ");
    } else {
      input.type = "text";
      input.maxLength = 1000;
    }
    input.spellcheck = false;
    input.addEventListener("input", onEdit);
    box.appendChild(input);
    var unset = null;
    if (nullable) {
      var lab = document.createElement("label");
      lab.className = "unset";
      unset = document.createElement("input");
      unset.type = "checkbox";
      lab.appendChild(unset);
      lab.appendChild(document.createTextNode(" unset"));
      unset.addEventListener("change", function () {
        input.disabled = unset.checked || f.disabled;
        onEdit();
      });
      box.appendChild(lab);
    }
    f.set = function (v) {
      var isNull = v === null || v === undefined;
      if (unset) unset.checked = isNull;
      input.value = isNull ? "" : String(v);
      input.disabled = !!f.disabled || (unset ? unset.checked : false);
    };
    f.get = function () {
      if (unset && unset.checked) return { ok: true, value: null };
      var text = input.value.trim();
      if (!numeric) return { ok: true, value: input.value };
      if (text === "") return nullable ? { ok: true, value: null } : { ok: false, error: "enter a number" };
      var n;
      if (type === "double") {
        n = Number(text);
        if (!isFinite(n)) return { ok: false, error: "not a number" };
      } else {
        if (!/^-?\d+$/.test(text)) return { ok: false, error: "a whole number" };
        n = Number(text);
        if (!Number.isSafeInteger(n)) return { ok: false, error: "too large for this page" };
      }
      if (typeof node.min === "number" && n < node.min) return { ok: false, error: "at least " + node.min };
      if (typeof node.max === "number" && n > node.max) return { ok: false, error: "at most " + node.max };
      return { ok: true, value: n };
    };
    f.setDisabled = function (d) {
      f.disabled = d;
      input.disabled = d || (unset ? unset.checked : false);
      if (unset) unset.disabled = d;
    };
  }

  /** A list of strings as chips; Enter or comma adds the typed text, × removes one, Backspace on empty the last. */
  function chipEditor(f, box, onEdit, serverValue) {
    var wrap = document.createElement("div");
    wrap.className = "chips";
    var input = document.createElement("input");
    input.className = "chip-input";
    input.type = "text";
    input.maxLength = 1000;
    input.placeholder = "add...";
    input.spellcheck = false;
    var items = [];
    var isNull = false;
    var render = function () {
      var frag = document.createDocumentFragment();
      items.forEach(function (text, i) {
        var chip = document.createElement("span");
        chip.className = "chip";
        chip.appendChild(document.createTextNode(text));
        var x = document.createElement("button");
        x.type = "button";
        x.className = "chip-x";
        x.textContent = "×";
        x.setAttribute("aria-label", "Remove " + text);
        x.disabled = !!f.disabled;
        x.addEventListener("click", function () {
          items.splice(i, 1);
          render();
          onEdit();
        });
        chip.appendChild(x);
        frag.appendChild(chip);
      });
      frag.appendChild(input);
      wrap.replaceChildren(frag);
    };
    var add = function () {
      var t = input.value.replace(/,+$/, "").trim();
      input.value = "";
      if (!t || items.length >= 500) return;
      items.push(t);
      isNull = false;
      render();
      onEdit();
      input.focus();
    };
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === ",") {
        e.preventDefault();
        add();
      } else if (e.key === "Backspace" && !input.value && items.length) {
        items.pop();
        render();
        onEdit();
      }
    });
    input.addEventListener("blur", function () { if (input.value.trim()) add(); });
    wrap.addEventListener("click", function (e) { if (e.target === wrap) input.focus(); });
    box.appendChild(wrap);
    f.set = function (v) {
      isNull = !Array.isArray(v);
      items = Array.isArray(v) ? v.filter(function (x) { return typeof x === "string"; }) : [];
      render();
    };
    f.get = function () { return { ok: true, value: isNull && !items.length ? serverValue(f.node.path) : items.slice() }; };
    f.setDisabled = function (d) {
      f.disabled = d;
      input.disabled = d;
      render();
    };
  }

  var settingsForm = makeForm({
    page: "settings",
    sectioned: false,
    scope: function () { return "plugin"; },
    sudo: function () { return false; },
    denied: function () {
      if (forcedStreamer()) return "Streamer mode is enforced by the owner: the bot's settings are not shown (they can hold coordinates).";
      return can("activity") ? "" : "Your account can't see the bot's settings; ask the owner.";
    },
    readOnly: function () { return !can("settings"); },
    els: { status: el.settingsStatus, save: el.settingsSave, revert: el.settingsRevert, offline: el.settingsOffline, body: el.settingsBody }
  });

  /** The Proxy page's scopes for the current bot: "proxy" first, then "plugin:<id>". */
  function proxyScopes() {
    var b = currentBot();
    var scopes = b && Array.isArray(b.scopes) ? b.scopes.filter(function (s) { return s === "proxy" || /^plugin:/.test(s); }) : [];
    scopes.sort(function (a, c) { return a === "proxy" ? -1 : c === "proxy" ? 1 : a < c ? -1 : a > c ? 1 : 0; });
    return scopes;
  }

  var proxyScope = "proxy";

  function renderProxyScopes() {
    var scopes = proxyScopes();
    if (scopes.indexOf(proxyScope) < 0) proxyScope = scopes.length ? scopes[0] : "proxy";
    var frag = document.createDocumentFragment();
    (scopes.length ? scopes : ["proxy"]).forEach(function (s) {
      var o = document.createElement("option");
      o.value = s;
      o.textContent = s === "proxy" ? "AquariusProxy" : "Plugin: " + s.slice(7);
      frag.appendChild(o);
    });
    el.proxyScope.replaceChildren(frag);
    el.proxyScope.value = proxyScope;
    el.proxyScope.disabled = scopes.length <= 1;
  }

  el.proxyScope.addEventListener("change", function () {
    proxyScope = el.proxyScope.value;
    proxyForm.load(true);
  });

  var proxyForm = makeForm({
    page: "proxy",
    sectioned: true,
    cap: "proxy",
    scope: function () { return proxyScope; },
    sudo: function (scope) { return scope !== "plugin"; },
    denied: function () { return permMode("proxy") === "none" ? "Your account can't see the proxy settings; ask the owner." : ""; },
    readOnly: function () { return permMode("proxy") !== "change"; },
    els: {
      status: el.proxyStatus, save: el.proxySave, revert: el.proxyRevert, offline: el.proxyOffline, body: el.proxyBody,
      nav: el.proxyNav, search: el.proxySearch
    }
  });

  function hasCap(b, cap) {
    return !!b && Array.isArray(b.caps) && b.caps.indexOf(cap) >= 0;
  }

  /** The Activity nav button for bots that report activity; it is the landing tab when none was chosen. */
  function updateActivityNav(firstLoad) {
    var show = hasCap(currentBot(), "activity") && can("activity");
    el.navActivity.hidden = !show;
    if (!show && dash.page === "activity") showPage("bots");
    if (firstLoad) {
      var saved = null;
      try {
        saved = window.localStorage.getItem(PAGE_KEY);
      } catch (e) {
        saved = null;
      }
      if (dash.page !== "stash") return;
      if (saved && saved !== "stash" && pageAllowed(saved) && (saved !== "activity" || show)) showPage(saved);
      else if (!saved && show) showPage("activity");
    }
  }

  /** The Proxy nav button only for bots that offer the proxy scope. */
  function updateProxyNav() {
    var show = !!currentBot() && pageAllowed("proxy");
    el.navProxy.hidden = !show;
    if (!show && dash.page === "proxy") showPage("bots");
  }

  /** "Disabled on the proxy" hint for a bot without the console / proxy cap. */
  function capHint(cap) {
    var box = document.createElement("div");
    box.className = "cap-hint";
    box.appendChild(line("section-title", cap === "console" ? "Console disabled on the proxy" : "Proxy settings disabled on the proxy"));
    var p = document.createElement("div");
    p.className = "note";
    p.appendChild(document.createTextNode("Disabled on the proxy. Run "));
    var code = document.createElement("code");
    code.textContent = "stash manager dashboard " + (cap === "console" ? "console" : "proxy") + " on";
    p.appendChild(code);
    p.appendChild(document.createTextNode(" in the proxy's terminal to allow it."));
    box.appendChild(p);
    return box;
  }

  /** A locked panel with an Unlock button that asks for the password (sudo mode), then calls done. */
  function unlockBox(text, done) {
    var box = document.createElement("div");
    box.className = "cap-hint";
    box.appendChild(line("note", text));
    var b = document.createElement("button");
    b.type = "button";
    b.className = "mc-button";
    b.textContent = "Confirm password";
    b.addEventListener("click", function () {
      askPassword().then(done, function () {});
    });
    box.appendChild(b);
    return box;
  }

  // ---------- admin (sudo) indicator ----------
  // While the password was typed in the last 15 minutes, the header says so, with a button to end it now.

  var sudo = { until: 0, timer: 0 };

  /** until: when admin mode ends (local ms), 0 = off. */
  function sudoState(until) {
    sudo.until = until || 0;
    renderSudo();
  }

  function renderSudo() {
    window.clearTimeout(sudo.timer);
    var left = sudo.until - Date.now();
    el.sudoBox.hidden = left <= 0 || auth.blocked;
    // Mobile chip: "Admin · N min — Lock" while unlocked, "Unlock admin" otherwise.
    var chip = $("admin-chip");
    chip.hidden = !!auth.blocked;
    chip.classList.toggle("on", left > 0);
    chip.textContent = left > 0 ? "Admin \u00b7 " + Math.ceil(left / 60000) + " min \u2014 Lock" : "Unlock admin";
    chip.title = left > 0 ? "Admin mode is on. Tap to lock it now." : "Type the password to unlock admin mode for 15 minutes.";
    if (left <= 0) return;
    var min = Math.ceil(left / 60000);
    el.sudoText.textContent = "Admin unlocked for " + min + " min";
    sudo.timer = window.setTimeout(renderSudo, Math.min(30000, left % 60000 || 60000));
  }

  /** Also picks up changes to the logged-in user (name, permissions, mustChange) made by the owner meanwhile. */
  function refreshSudo() {
    api("/api/v1/auth/state").then(function (st) {
      // elevatedUntil is server time; turn it into local time
      sudoState(st && st.elevatedUntil ? num(st.elevatedUntil, 0) - state.clockSkew : 0);
      if (!st || !st.loggedIn || auth.blocked) return;
      var oldPerms = JSON.stringify(me.perms);
      if (!setUser(st.user)) return;
      if (me.mustChange) mustChangeRequired();
      else applyPerms(JSON.stringify(me.perms) !== oldPerms);
    }, function () {});
  }

  el.sudoLock.addEventListener("click", function () {
    api("/api/v1/auth/lock", { method: "POST", body: {} }).then(function () {
      sudoState(0);
      toast("Admin locked. Console and proxy settings ask for the password again.");
      if (link.data && link.data.apiKey) {
        delete link.data.apiKey;
        link.data.apiKeySet = true;
        link.showKey = false;
        renderKey();
      }
    }, function () {});
  });

  // ---------- live viewer ----------
  // The bot's surroundings, live (PROTOCOL.md, "Additions: live viewer"): Server-Sent Events from
  // /api/v1/bots/{id}/viewer/stream, drawn on a canvas as 16x16 map tiles with the vanilla map palette. Markers and
  // labels are drawn on the canvas (fillText), never as HTML; the HUD and chat use textContent only. The stream is
  // open only while this page is shown: it closes 30 s after the tab is hidden, and when the page is left.

  var V_ZOOM_MIN = 0.5;
  var V_ZOOM_MAX = 16;
  var V_ZOOM_DEFAULT = 4;
  var V_CHAT_KEEP = 200;
  var V_LAT_KEEP = 200;
  var V_TILES_KEEP = 1500; // tiles held here; past it the farthest from the bot go
  var V_HIDDEN_CLOSE_MS = 30000;
  var V_DEBUG_KEY = "stashManager.viewerDebug";
  var V_INV_KEY = "stashManager.viewerInventory"; // "1" open, "0" collapsed; unset: open from 768px, collapsed below
  // Mob types (lowercase, no namespace) drawn red.
  var V_HOSTILE = Object.create(null);
  ["zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged", "wither_skeleton", "creeper", "spider",
    "cave_spider", "enderman", "endermite", "silverfish", "witch", "slime", "magma_cube", "blaze", "ghast", "phantom",
    "pillager", "vindicator", "evoker", "illusioner", "ravager", "vex", "guardian", "elder_guardian", "shulker", "hoglin",
    "zoglin", "piglin_brute", "warden", "breeze", "creaking", "wither", "ender_dragon"].forEach(function (m) { V_HOSTILE[m] = true; });

  var vw = {
    open: false, es: null, vs: "", botId: null, mc: DEFAULT_MC, palette: null, paletteLoading: false,
    tiles: new Map(), self: null, ents: [], hud: null, world: null, state: "", session: "",
    chat: [], chatKeys: new Set(), unread: 0, chatOpen: false,
    zoom: V_ZOOM_DEFAULT, cx: 0, cz: 0, follow: true, dirty: false, raf: 0, dpr: 1, w: 0, h: 0,
    pointers: new Map(), pinch: null, lastTap: 0, lastTouch: 0,
    offset: 0, offsetRtt: Infinity, pending: [], lat: { e2e: [], bs: [], sb: [], render: [] }, events: 0,
    debug: false, hiddenTimer: 0, retryTimer: 0, colors: null, slots: [], gear: [], inv: []
  };
  try {
    vw.debug = window.localStorage.getItem(V_DEBUG_KEY) === "1";
  } catch (e) {
    vw.debug = false;
  }

  var vel = {
    page: $("page-viewer"),
    nav: $("nav-viewer"),
    title: $("viewer-title"),
    wrap: $("viewer-wrap"),
    canvas: $("viewer-canvas"),
    note: $("v-note"),
    status: $("v-status"),
    hpFill: $("v-hp"),
    hpText: $("v-hp-text"),
    foodFill: $("v-food"),
    foodText: $("v-food-text"),
    xp: $("v-xp"),
    armor: $("v-armor"),
    coords: $("v-coords"),
    centre: $("v-centre"),
    zoomIn: $("v-zoom-in"),
    zoomOut: $("v-zoom-out"),
    chatBtn: $("v-chat-btn"),
    unread: $("v-unread"),
    debugBtn: $("v-debug-btn"),
    areaBtn: $("v-area-btn"),
    areaBox: $("v-area"),
    areaText: $("v-area-text"),
    areaSave: $("v-area-save"),
    areaCancel: $("v-area-cancel"),
    debugBox: $("v-debug"),
    hotbar: $("v-hotbar"),
    gearBtn: $("v-gear-btn"),
    gearBox: $("v-gear"),
    invBtn: $("v-inv-btn"),
    invBox: $("v-inv-box"),
    invGrid: $("v-inv"),
    invNote: $("v-inv-note"),
    chat: $("v-chat"),
    chatLines: $("v-chat-lines"),
    chatClose: $("v-chat-close")
  };
  var vctx = vel.canvas.getContext("2d", { alpha: false });

  function vRandomId() {
    var a = new Uint8Array(8);
    window.crypto.getRandomValues(a);
    return Array.prototype.map.call(a, function (b) { return (b < 16 ? "0" : "") + b.toString(16); }).join("");
  }

  /** Theme colours for the canvas, read from the tokens (again when the theme changes). */
  function vReadColors() {
    var cs = window.getComputedStyle(document.documentElement);
    var get = function (n, d) { return (cs.getPropertyValue(n) || "").trim() || d; };
    vw.colors = {
      bg: get("--surface-2", "#222"), text: get("--text", "#fff"), surface: get("--surface", "#000"), accent: get("--accent", "#36c"),
      onAccent: get("--on-accent", "#fff"), hostile: get("--hp", "#d32f2f"), passive: get("--ok-bg", "#2e7d32"),
      item: get("--warn-bg", "#c9a227"), other: get("--muted", "#888"), border: get("--border", "#000"), font: get("--font", "sans-serif")
    };
  }

  function vLoadPalette() {
    if (vw.palette || vw.paletteLoading) return;
    vw.paletteLoading = true;
    fetch("/map-palette.json", { credentials: "same-origin" }).then(function (r) { return r.json(); }).then(function (p) {
      var rgb = p && Array.isArray(p.rgb) && p.rgb.length === 256 ? p.rgb : null;
      if (!rgb) throw new Error("bad palette");
      var out = new Uint32Array(256); // little-endian RGBA as one word, for ImageData
      for (var i = 0; i < 256; i++) {
        var v = typeof rgb[i] === "number" ? rgb[i] : -1;
        out[i] = v < 0 || i < 4 ? 0 : (0xff000000 | ((v & 0xff) << 16) | (v & 0xff00) | ((v >> 16) & 0xff)) >>> 0;
      }
      vw.palette = out;
      // Tiles that came before the palette: draw them now.
      vw.tiles.forEach(function (t) { if (!t.canvas) vPaint(t); });
      vDirty();
    }).catch(function () {
      vw.paletteLoading = false;
    });
  }

  function vDecode(b64, n) {
    try {
      var s = window.atob(b64);
      if (s.length !== n) return null;
      var out = new Uint8Array(n);
      for (var i = 0; i < n; i++) out[i] = s.charCodeAt(i);
      return out;
    } catch (e) {
      return null;
    }
  }

  /** A tile's 16x16 offscreen canvas, from its packed map bytes. */
  function vPaint(t) {
    if (!vw.palette || !t.bytes) return;
    var c = t.canvas || document.createElement("canvas");
    c.width = 16;
    c.height = 16;
    var g = c.getContext("2d");
    var img = g.createImageData(16, 16);
    var px = new Uint32Array(img.data.buffer);
    for (var i = 0; i < 256; i++) px[i] = vw.palette[t.bytes[i]];
    g.putImageData(img, 0, 0);
    t.canvas = c;
  }

  function vPutTile(o) {
    if (!o || typeof o.x !== "number" || typeof o.z !== "number") return;
    var bytes = vDecode(str(o.c), 256);
    if (!bytes) return;
    var key = o.x + "," + o.z;
    var t = vw.tiles.get(key) || { x: o.x, z: o.z, canvas: null };
    t.bytes = bytes;
    vPaint(t);
    vw.tiles.set(key, t);
  }

  /** Keeps at most V_TILES_KEEP tiles, dropping the farthest from the bot (or the view centre). */
  function vCapTiles() {
    if (vw.tiles.size <= V_TILES_KEEP) return;
    var x = vw.self ? vw.self.x : vw.cx;
    var z = vw.self ? vw.self.z : vw.cz;
    var all = Array.from(vw.tiles.entries()).map(function (e) {
      var dx = e[1].x * 16 + 8 - x;
      var dz = e[1].z * 16 + 8 - z;
      return [e[0], dx * dx + dz * dz];
    });
    all.sort(function (a, b) { return b[1] - a[1]; });
    for (var i = 0; vw.tiles.size > V_TILES_KEEP; i++) vw.tiles.delete(all[i][0]);
  }

  function vDirty() {
    vw.dirty = true;
    if (!vw.raf && vw.open) vw.raf = window.requestAnimationFrame(vDraw);
  }

  function vResize() {
    var r = vel.wrap.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var w = Math.max(1, Math.round(r.width));
    var h = Math.max(1, Math.round(r.height));
    if (w === vw.w && h === vw.h && dpr === vw.dpr) return;
    vw.w = w;
    vw.h = h;
    vw.dpr = dpr;
    vel.canvas.width = Math.round(w * dpr);
    vel.canvas.height = Math.round(h * dpr);
    vDirty();
  }

  if (typeof ResizeObserver === "function") new ResizeObserver(function () { if (vw.open) vResize(); }).observe(vel.wrap);
  window.addEventListener("resize", function () { if (vw.open) vResize(); });

  function vLabel(text, x, y, s) {
    var c = vw.colors;
    vctx.font = Math.round(11 * s) + "px " + c.font;
    var w = vctx.measureText(text).width;
    var pad = 3 * s;
    vctx.fillStyle = c.surface;
    vctx.globalAlpha = 0.85;
    vctx.fillRect(x - w / 2 - pad, y - 13 * s, w + pad * 2, 15 * s);
    vctx.globalAlpha = 1;
    vctx.fillStyle = c.text;
    vctx.textAlign = "center";
    vctx.textBaseline = "alphabetic";
    vctx.fillText(text, x, y - 2 * s);
  }

  function vDraw() {
    vw.raf = 0;
    if (!vw.open || !vw.dirty) return;
    vw.dirty = false;
    if (!vw.colors) vReadColors();
    var c = vw.colors;
    var dpr = vw.dpr;
    var W = vel.canvas.width;
    var H = vel.canvas.height;
    if (vw.follow && vw.self) {
      vw.cx = vw.self.x;
      vw.cz = vw.self.z;
    }
    var s = vw.zoom * dpr; // device px per block
    var zl = String(Math.round(vw.zoom * 100) / 100);
    if (vel.canvas.dataset.zoom !== zl) vel.canvas.dataset.zoom = zl; // for tests and the curious
    vctx.setTransform(1, 0, 0, 1, 0, 0);
    vctx.imageSmoothingEnabled = false;
    vctx.fillStyle = c.bg;
    vctx.fillRect(0, 0, W, H);
    var toX = function (bx) { return (bx - vw.cx) * s + W / 2; };
    var toY = function (bz) { return (bz - vw.cz) * s + H / 2; };
    vw.tiles.forEach(function (t) {
      if (!t.canvas) return;
      var x0 = Math.floor(toX(t.x * 16));
      var y0 = Math.floor(toY(t.z * 16));
      var x1 = Math.floor(toX(t.x * 16 + 16));
      var y1 = Math.floor(toY(t.z * 16 + 16));
      if (x1 < 0 || y1 < 0 || x0 > W || y0 > H) return;
      vctx.drawImage(t.canvas, x0, y0, x1 - x0, y1 - y0);
    });
    areaMapDraw(vctx, toX, toY, dpr, c);
    var names = !streamerOn();
    var r = Math.max(2.5, Math.min(6, vw.zoom * 0.9)) * dpr;
    for (var i = vw.ents.length - 1; i >= 0; i--) {
      var e = vw.ents[i];
      var ex = toX(e.x);
      var ey = toY(e.z);
      if (ex < -40 || ey < -40 || ex > W + 40 || ey > H + 40) continue;
      if (e.k === "player") {
        vctx.beginPath();
        vctx.arc(ex, ey, r * 1.3, 0, Math.PI * 2);
        vctx.fillStyle = c.accent;
        vctx.fill();
        vctx.lineWidth = 1.5 * dpr;
        vctx.strokeStyle = c.onAccent;
        vctx.stroke();
        if (names && str(e.n)) vLabel(str(e.n), ex, ey - r * 1.6, dpr);
      } else if (e.k === "mob") {
        vctx.beginPath();
        vctx.arc(ex, ey, r * 0.8, 0, Math.PI * 2);
        vctx.fillStyle = V_HOSTILE[str(e.t)] ? c.hostile : c.passive;
        vctx.fill();
        vctx.lineWidth = 1 * dpr;
        vctx.strokeStyle = c.border;
        vctx.stroke();
      } else if (e.k === "item") {
        var q = Math.max(2, r * 0.7);
        vctx.fillStyle = c.item;
        vctx.fillRect(ex - q / 2, ey - q / 2, q, q);
        vctx.lineWidth = 1 * dpr;
        vctx.strokeStyle = c.border;
        vctx.strokeRect(ex - q / 2, ey - q / 2, q, q);
      } else {
        vctx.fillStyle = c.other;
        vctx.fillRect(ex - 1.5 * dpr, ey - 1.5 * dpr, 3 * dpr, 3 * dpr);
      }
    }
    if (vw.self) {
      // The bot: an arrow along its heading. Minecraft yaw 0 = +z (south, down), 90 = -x (west, left).
      var bx = toX(vw.self.x);
      var by = toY(vw.self.z);
      var a = num(vw.self.yaw, 0) * Math.PI / 180;
      var dx = -Math.sin(a);
      var dy = Math.cos(a);
      var len = 11 * dpr;
      vctx.beginPath();
      vctx.moveTo(bx + dx * len, by + dy * len);
      vctx.lineTo(bx - dx * len * 0.6 + dy * len * 0.6, by - dy * len * 0.6 - dx * len * 0.6);
      vctx.lineTo(bx - dx * len * 0.25, by - dy * len * 0.25);
      vctx.lineTo(bx - dx * len * 0.6 - dy * len * 0.6, by - dy * len * 0.6 + dx * len * 0.6);
      vctx.closePath();
      vctx.fillStyle = c.onAccent;
      vctx.strokeStyle = c.accent;
      vctx.lineWidth = 3 * dpr;
      vctx.stroke();
      vctx.fill();
    }
    vLatencyAfterDraw(window.performance.now()); // after drawing (the rAF timestamp is the frame's start, maybe before recv)
  }

  // ---------- latency (debug overlay) ----------
  // bot->server = rt - t; server->browser = recv + offset - st; render = draw - recv; end to end = draw + offset - t.

  function vPush(list, v) {
    list.push(v);
    if (list.length > V_LAT_KEEP) list.shift();
  }

  function vNote(d) {
    vw.events++;
    if (!d || typeof d.t !== "number") return;
    var recv = Date.now();
    if (typeof d.rt === "number") vPush(vw.lat.bs, d.rt - d.t);
    if (typeof d.st === "number") vPush(vw.lat.sb, recv + vw.offset - d.st);
    vw.pending.push({ t: d.t, recv: recv, perf: window.performance.now() });
  }

  function vLatencyAfterDraw(perfNow) {
    if (!vw.pending.length) return;
    var wall = Date.now();
    vw.pending.forEach(function (p) {
      vPush(vw.lat.render, perfNow - p.perf);
      vPush(vw.lat.e2e, wall + vw.offset - p.t);
    });
    vw.pending = [];
    if (vw.debug) vRenderDebug();
  }

  function vPct(list, p) {
    if (!list.length) return "-";
    var sorted = list.slice().sort(function (a, b) { return a - b; });
    return String(Math.round(sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))]));
  }

  function vRenderDebug() {
    vel.debugBox.hidden = !vw.debug;
    vel.debugBtn.setAttribute("aria-pressed", vw.debug ? "true" : "false");
    if (!vw.debug) return;
    var rows = [
      ["end to end", vw.lat.e2e], ["bot → server", vw.lat.bs], ["server → browser", vw.lat.sb], ["render", vw.lat.render]
    ];
    var frag = document.createDocumentFragment();
    var head = document.createElement("div");
    head.className = "v-debug-head";
    head.textContent = "Latency ms (p50 / p95, last " + vw.lat.e2e.length + ")";
    frag.appendChild(head);
    rows.forEach(function (r) {
      var row = document.createElement("div");
      row.className = "v-debug-row";
      row.dataset.metric = r[0];
      row.dataset.p50 = vPct(r[1], 0.5);
      row.dataset.p95 = vPct(r[1], 0.95);
      row.textContent = r[0] + ": " + row.dataset.p50 + " / " + row.dataset.p95;
      frag.appendChild(row);
    });
    var foot = document.createElement("div");
    foot.className = "v-debug-row";
    foot.textContent = "events " + vw.events + " · tiles " + vw.tiles.size + " · clock offset " + Math.round(vw.offset) +
      " ms (rtt " + (isFinite(vw.offsetRtt) ? Math.round(vw.offsetRtt) : "-") + ")";
    frag.appendChild(foot);
    vel.debugBox.replaceChildren(frag);
  }

  /** The server clock offset from 3 pings, keeping the one with the lowest round trip. */
  function vSyncClock(botId) {
    var n = 0;
    var ping = function () {
      if (!vw.open || vw.botId !== botId || n >= 3) return;
      n++;
      var t0 = Date.now();
      api("/api/v1/bots/" + encodeURIComponent(botId) + "/viewer").then(function (d) {
        var t1 = Date.now();
        if (d && typeof d.now === "number" && t1 - t0 < vw.offsetRtt) {
          vw.offsetRtt = t1 - t0;
          vw.offset = d.now - (t0 + t1) / 2;
        }
        ping();
      }, function () {});
    };
    ping();
  }

  // ---------- HUD ----------

  function vItem(o) {
    return o && typeof o === "object" && typeof o.id === "string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(o.id) ? o : null;
  }

  function vMakeSlot(cls) {
    var slot = document.createElement("div");
    slot.className = "v-slot " + cls;
    var img = document.createElement("img");
    img.alt = "";
    img.width = 28;
    img.height = 28;
    img.draggable = false;
    img.hidden = true;
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
    });
    var count = document.createElement("span");
    count.className = "v-count";
    slot.appendChild(img);
    slot.appendChild(count);
    return { slot: slot, img: img, count: count, id: null };
  }

  function vSetSlot(s, item) {
    var it = vItem(item);
    var id = it ? it.id : null;
    if (id !== s.id) {
      s.id = id;
      s.img.hidden = !id;
      if (id) s.img.src = iconUrl(id, vw.mc);
      else s.img.removeAttribute("src");
      s.slot.title = id || "";
    }
    var n = it ? Math.floor(num(it.c, 1)) : 0;
    var text = n > 1 ? String(n) : "";
    if (s.count.textContent !== text) s.count.textContent = text;
    // The same durability bar as the stash grid (d/md come only for damageable items, from newer plugins).
    setDurability(s.slot, it ? { damage: Math.floor(num(it.d, 0)), maxDamage: Math.floor(num(it.md, 0)) } : null);
  }

  (function buildSlots() {
    for (var i = 0; i < 9; i++) {
      var s = vMakeSlot("hot");
      vw.slots.push(s);
      vel.hotbar.appendChild(s.slot);
    }
    ["Helmet", "Chestplate", "Leggings", "Boots", "Offhand"].forEach(function (name) {
      var s = vMakeSlot("gear");
      s.slot.setAttribute("aria-label", name);
      vw.gear.push(s);
      vel.gearBox.appendChild(s.slot);
    });
    for (var j = 0; j < 27; j++) { // main inventory, slots 9-35: three rows of nine
      var t = vMakeSlot("inv");
      vw.inv.push(t);
      vel.invGrid.appendChild(t.slot);
    }
  })();

  /** Opens or collapses the inventory panel (remember: store the choice). */
  function vSetInvOpen(open, remember) {
    vel.invBox.hidden = !open;
    vel.invBtn.setAttribute("aria-expanded", open ? "true" : "false");
    if (!remember) return;
    try {
      window.localStorage.setItem(V_INV_KEY, open ? "1" : "0");
    } catch (e) {
      // not remembered
    }
  }

  (function initInv() {
    var saved = null;
    try {
      saved = window.localStorage.getItem(V_INV_KEY);
    } catch (e) {
      saved = null;
    }
    var open = saved === "1" || (saved !== "0" && !window.matchMedia("(max-width: 767px)").matches);
    vSetInvOpen(open, false);
  })();

  function vBar(fill, text, value, max) {
    var v = Math.max(0, Math.min(max, num(value, 0)));
    fill.style.width = (v / max * 100) + "%";
    text.textContent = typeof value === "number" ? String(Math.round(value * 2) / 2) : "-";
  }

  function vRenderHud() {
    var h = vw.hud && typeof vw.hud === "object" ? vw.hud : {};
    vBar(vel.hpFill, vel.hpText, h.hp, 20);
    vBar(vel.foodFill, vel.foodText, h.food, 20);
    vel.xp.textContent = typeof h.xp === "number" ? "XP " + h.xp : "XP -";
    vel.armor.hidden = typeof h.armor !== "number";
    vel.armor.textContent = typeof h.armor === "number" ? "Armour " + h.armor : "";
    var hot = Array.isArray(h.hotbar) ? h.hotbar : [];
    var held = typeof h.held === "number" ? h.held : -1;
    vw.slots.forEach(function (s, i) {
      vSetSlot(s, hot[i]);
      s.slot.classList.toggle("held", i === held);
    });
    var armor = Array.isArray(h.armorSlots) ? h.armorSlots : [];
    for (var i = 0; i < 4; i++) vSetSlot(vw.gear[i], armor[i]);
    vSetSlot(vw.gear[4], h.offhand);
    // Older plugins send no inventory: say so instead of showing 27 empty slots (no hud yet: empty slots).
    var inv = Array.isArray(h.inv) && h.inv.length === 27 ? h.inv : null;
    var missing = !inv && vw.hud && typeof vw.hud === "object";
    vel.invGrid.hidden = !!missing;
    vel.invNote.hidden = !missing;
    for (var k = 0; k < 27; k++) vSetSlot(vw.inv[k], inv ? inv[k] : null);
  }

  function vRenderCoords() {
    var s = vw.self;
    var dim = vw.world && str(vw.world.dim) ? str(vw.world.dim).replace(/^minecraft:/, "") : "";
    var rel = vw.relative ? "relative " : "";
    if (dim === "hidden") dim = "";
    vel.coords.textContent = s ? rel + Math.round(s.x) + ", " + Math.round(num(s.y, 0)) + ", " + Math.round(s.z) + (dim ? " · " + dim : "") : "-";
    vel.coords.title = vw.relative ? "Relative coordinates: streamer mode is enforced by the owner" : "";
  }

  var V_STATES = { waiting: "Waiting for the bot", live: "Live", stale: "Stale", offline: "Bot offline", connecting: "Connecting", closed: "Closed" };

  function vSetState(st) {
    vw.state = st;
    vel.status.textContent = V_STATES[st] || st;
    vel.status.className = "v-status v-" + st;
  }

  function vShowNote(text) {
    vel.note.hidden = !text;
    vel.note.textContent = text || "";
  }

  // ---------- chat ----------

  function vChatKey(l) {
    return num(l.t, 0) + "\u0000" + str(l.k) + "\u0000" + str(l.s) + "\u0000" + str(l.m);
  }

  function vAddChat(lines, fromSnapshot) {
    var added = 0;
    (Array.isArray(lines) ? lines : []).forEach(function (l) {
      if (!l || typeof l !== "object" || typeof l.m !== "string") return;
      var key = vChatKey(l);
      if (vw.chatKeys.has(key)) return;
      vw.chatKeys.add(key);
      vw.chat.push(l);
      added++;
    });
    while (vw.chat.length > V_CHAT_KEEP) vw.chatKeys.delete(vChatKey(vw.chat.shift()));
    if (!added) return;
    if (!vw.chatOpen && !fromSnapshot && vChatIsDrawer()) {
      vw.unread += added;
      vRenderUnread();
    }
    vRenderChat();
  }

  function vChatIsDrawer() {
    return window.matchMedia("(max-width: 899px)").matches;
  }

  function vRenderUnread() {
    vel.unread.hidden = !vw.unread;
    vel.unread.textContent = vw.unread > 99 ? "99+" : String(vw.unread);
  }

  function vRenderChat() {
    var stick = vel.chatLines.scrollHeight - vel.chatLines.scrollTop - vel.chatLines.clientHeight < 40;
    var hideNames = streamerOn();
    var frag = document.createDocumentFragment();
    if (!vw.chat.length) {
      var empty = document.createElement("div");
      empty.className = "note";
      empty.textContent = "No chat yet.";
      frag.appendChild(empty);
    }
    vw.chat.forEach(function (l) {
      var row = document.createElement("div");
      var k = str(l.k);
      row.className = "v-line v-" + (k === "whisper" || k === "system" || k === "death" ? k : "public");
      var time = document.createElement("span");
      time.className = "v-time";
      var d = new Date(num(l.t, 0));
      time.textContent = (d.getHours() < 10 ? "0" : "") + d.getHours() + ":" + (d.getMinutes() < 10 ? "0" : "") + d.getMinutes();
      row.appendChild(time);
      var who = str(l.s);
      if (who || k === "whisper") {
        var sender = document.createElement("span");
        sender.className = "v-sender";
        var name = function (n) { return hideNames ? "Player" : n || "?"; };
        sender.textContent = k === "whisper" ? name(who) + " → " + name(str(l.to)) : name(who);
        sender.setAttribute("data-sensitive", ""); // blurred in streamer mode (and shown as "Player")
        row.appendChild(sender);
      }
      var msg = document.createElement("span");
      msg.className = "v-msg";
      msg.textContent = str(l.m);
      msg.setAttribute("data-sensitive", ""); // every kind of line: public, whisper, system and death
      row.appendChild(msg);
      frag.appendChild(row);
    });
    vel.chatLines.replaceChildren(frag);
    if (stick) vel.chatLines.scrollTop = vel.chatLines.scrollHeight;
  }

  function vSetChatOpen(open) {
    vw.chatOpen = open;
    vel.chat.classList.toggle("open", open);
    vel.chatBtn.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) {
      vw.unread = 0;
      vRenderUnread();
      vel.chatLines.scrollTop = vel.chatLines.scrollHeight;
    }
  }

  vel.chatBtn.addEventListener("click", function () { vSetChatOpen(!vw.chatOpen); });
  vel.chatClose.addEventListener("click", function () { vSetChatOpen(false); });

  // ---------- the stream ----------

  function vParse(ev) {
    try {
      var d = JSON.parse(ev.data);
      return d && typeof d === "object" ? d : null;
    } catch (e) {
      return null;
    }
  }

  function vEnts(list) {
    return (Array.isArray(list) ? list : []).filter(function (e) {
      return e && typeof e === "object" && typeof e.x === "number" && typeof e.z === "number";
    });
  }

  function vSelf(s) {
    return s && typeof s === "object" && typeof s.x === "number" && typeof s.z === "number" ? s : null;
  }

  function vClose() {
    vw.pending = [];
    window.clearTimeout(vw.retryTimer);
    vw.retryTimer = 0;
    if (vw.es) {
      vw.es.close();
      vw.es = null;
    }
  }

  function vConnect(soon) {
    vClose();
    if (!vw.open || auth.blocked) return;
    if (soon) {
      vw.retryTimer = window.setTimeout(function () { vConnect(false); }, soon);
      return;
    }
    var b = currentBot();
    if (!b) {
      vSetState("closed");
      vShowNote("No dashboard bot yet.");
      return;
    }
    if (vw.botId !== b.botId) vReset(b.botId);
    var botId = b.botId;
    vSetState("connecting");
    var es = new EventSource("/api/v1/bots/" + encodeURIComponent(botId) + "/viewer/stream?vs=" + vw.vs);
    vw.es = es;
    var mine = function (fn) {
      return function (ev) {
        if (vw.es !== es) return;
        fn(vParse(ev) || {});
      };
    };
    es.addEventListener("hello", mine(function (d) {
      vw.relative = d.relative === true; // forced streamer mode: the server rebased every coordinate
      vw.mc = /^[0-9][0-9.]{0,31}$/.test(str(d.mc)) ? str(d.mc) : DEFAULT_MC;
      vShowNote("");
    }));
    es.addEventListener("snapshot", mine(function (d) {
      vw.tiles.clear();
      (Array.isArray(d.tiles) ? d.tiles : []).forEach(vPutTile);
      vCapTiles();
      vw.session = str(d.session);
      vw.world = d.world && typeof d.world === "object" ? d.world : null;
      vw.self = vSelf(d.self);
      vw.ents = vEnts(d.ents);
      vw.hud = d.hud && typeof d.hud === "object" ? d.hud : null;
      vAddChat(d.chat, true);
      vRenderHud();
      vRenderCoords();
      vNote(d);
      vDirty();
    }));
    es.addEventListener("tiles", mine(function (d) {
      (Array.isArray(d.tiles) ? d.tiles : []).forEach(vPutTile);
      (Array.isArray(d.drop) ? d.drop : []).forEach(function (p) {
        if (Array.isArray(p) && p.length === 2) vw.tiles.delete(p[0] + "," + p[1]);
      });
      vCapTiles();
      vNote(d);
      vDirty();
    }));
    es.addEventListener("ents", mine(function (d) {
      var s = vSelf(d.self);
      if (s) vw.self = s;
      vw.ents = vEnts(d.ents);
      vRenderCoords();
      vNote(d);
      vDirty();
    }));
    es.addEventListener("hud", mine(function (d) {
      vw.hud = d.hud && typeof d.hud === "object" ? d.hud : null;
      vRenderHud();
      vNote(d);
      vDirty();
    }));
    es.addEventListener("chat", mine(function (d) { vAddChat(d.lines, false); }));
    es.addEventListener("status", mine(function (d) {
      var st = str(d.state);
      vSetState(st);
      vShowNote(st === "waiting" ? "Waiting for the bot's first frame. It needs the Qwerte Stash plugin with the live viewer." :
        st === "offline" ? botName(b) + " is offline." : "");
    }));
    es.addEventListener("bye", mine(function (d) {
      var reason = str(d.reason);
      vClose();
      if (reason === "revoked") {
        vSetState("closed");
        vShowNote("The viewer was closed: your login or permissions changed.");
        permsStale();
        return;
      }
      vConnect(reason === "capacity" ? 2000 : 0); // rotate: straight back
    }));
    es.onerror = function () {
      if (vw.es !== es) return;
      if (es.readyState === 2) {
        // Refused (403, 404, 429) or the server went away: ask why, then try again later.
        vw.es = null;
        vSetState("closed");
        api("/api/v1/bots/" + encodeURIComponent(botId) + "/viewer").then(function (s) {
          if (!vw.open) return;
          var full = s && s.yours >= s.maxPerUser ? "You already have " + s.yours + " viewers open (at most " + s.maxPerUser + "); close one."
            : s && s.streams >= s.maxStreams ? "This bot already has " + s.streams + " viewers open (at most " + s.maxStreams + ")."
            : s && s.total >= s.maxTotal ? "The server already has " + s.total + " viewer streams open (at most " + s.maxTotal + ")."
            : "The connection to the server dropped.";
          vShowNote(full + " Trying again in a few seconds.");
          vConnect(5000);
        }, function (err) {
          if (!vw.open) return;
          var st = err && err.status;
          if (st === 403) vShowNote("Your account can't use the live viewer; ask the owner.");
          else if (st === 404) vShowNote("This bot isn't known to the server (any more).");
          else vShowNote("Can't reach the server. Trying again in a few seconds.");
          if (st !== 403) vConnect(5000);
        });
      } else {
        vSetState("connecting");
      }
    };
  }

  function vReset(botId) {
    vw.botId = botId;
    vw.tiles.clear();
    vw.self = null;
    vw.ents = [];
    vw.hud = null;
    vw.world = null;
    vw.chat = [];
    vw.chatKeys.clear();
    vw.unread = 0;
    vw.follow = true;
    vw.pending = [];
    vRenderUnread();
    vRenderChat();
    vRenderHud();
    vRenderCoords();
    vDirty();
  }

  function viewerOpen() {
    if (!vel) return;
    var b = currentBot();
    vel.title.textContent = "Live" + (b ? " · " + botName(b) : "");
    if (vw.open && vw.botId === (b && b.botId)) return;
    vw.open = true;
    if (!vw.vs) vw.vs = vRandomId(); // one id per page visit, kept across reconnects
    vReadColors();
    vLoadPalette();
    vResize();
    vRenderDebug();
    vRenderChat();
    vw.offsetRtt = Infinity;
    vConnect(0);
    if (b) vSyncClock(b.botId); // once per visit, not on every rotate
  }

  function viewerClose() {
    if (!vw || !vw.open) return;
    vw.open = false;
    vw.vs = "";
    window.clearTimeout(vw.hiddenTimer);
    vClose();
    if (vw.raf) window.cancelAnimationFrame(vw.raf);
    vw.raf = 0;
  }

  /** The bot changed while the viewer is open: start over with the new one. */
  function viewerBotChanged() {
    if (!vw || !vw.open) return;
    vw.open = false;
    vClose();
    viewerOpen();
  }

  document.addEventListener("visibilitychange", function () {
    if (dash.page !== "viewer") return;
    window.clearTimeout(vw.hiddenTimer);
    if (document.hidden) {
      vw.hiddenTimer = window.setTimeout(function () {
        if (document.hidden) viewerClose();
      }, V_HIDDEN_CLOSE_MS);
    } else if (!vw.open) {
      viewerOpen();
    }
  });
  window.addEventListener("pagehide", function () { viewerClose(); });

  // ---------- pan, pinch and zoom ----------

  function vZoomAt(factor, px, py) {
    var z = Math.max(V_ZOOM_MIN, Math.min(V_ZOOM_MAX, vw.zoom * factor));
    if (z === vw.zoom) return;
    if (typeof px === "number" && !vw.follow) {
      // Keep the block under (px, py) where it is.
      var bx = vw.cx + (px - vw.w / 2) / vw.zoom;
      var bz = vw.cz + (py - vw.h / 2) / vw.zoom;
      vw.cx = bx - (px - vw.w / 2) / z;
      vw.cz = bz - (py - vw.h / 2) / z;
    }
    vw.zoom = z;
    vDirty();
  }

  function vLocal(e) {
    var r = vel.canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  function vSetFollow(on) {
    vw.follow = on;
    vel.centre.setAttribute("aria-pressed", on ? "true" : "false");
    vDirty();
  }

  vel.canvas.addEventListener("pointerdown", function (e) {
    if (areaMapActive() && e.isPrimary && vw.pointers.size === 0) {
      areaMapDown(e);
      return;
    }
    vel.canvas.setPointerCapture(e.pointerId);
    vw.pointers.set(e.pointerId, vLocal(e));
    if (vw.pointers.size === 2) {
      var p = Array.from(vw.pointers.values());
      vw.pinch = { d: Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y) || 1, zoom: vw.zoom };
    }
    if (e.pointerType !== "mouse") vw.lastTouch = Date.now();
    if (e.pointerType !== "mouse" && vw.pointers.size === 1) {
      var now = Date.now();
      if (now - vw.lastTap < 300) vZoomAt(2, vLocal(e).x, vLocal(e).y);
      vw.lastTap = now;
    }
  });
  vel.canvas.addEventListener("pointermove", function (e) {
    if (areaDrag.start) {
      areaMapMove(e);
      return;
    }
    var prev = vw.pointers.get(e.pointerId);
    if (!prev) return;
    var cur = vLocal(e);
    vw.pointers.set(e.pointerId, cur);
    if (vw.pointers.size === 1) {
      var dx = cur.x - prev.x;
      var dy = cur.y - prev.y;
      if (!dx && !dy) return;
      if (vw.follow) vSetFollow(false);
      vw.cx -= dx / vw.zoom;
      vw.cz -= dy / vw.zoom;
      vDirty();
    } else if (vw.pointers.size === 2 && vw.pinch) {
      var p = Array.from(vw.pointers.values());
      var d = Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y) || 1;
      var mid = { x: (p[0].x + p[1].x) / 2, y: (p[0].y + p[1].y) / 2 };
      vZoomAt(vw.pinch.zoom * d / vw.pinch.d / vw.zoom, mid.x, mid.y);
    }
  });
  var vUp = function (e) {
    if (areaDrag.start) {
      if (e.type === "pointerup") areaMapUp(e);
      else {
        areaDrag.start = null;
        areaDrag.now = null;
        vDirty();
      }
      return;
    }
    vw.pointers.delete(e.pointerId);
    if (vw.pointers.size < 2) vw.pinch = null;
  };
  vel.canvas.addEventListener("pointerup", vUp);
  vel.canvas.addEventListener("pointercancel", vUp);
  vel.canvas.addEventListener("wheel", function (e) {
    e.preventDefault();
    var p = vLocal(e);
    vZoomAt(Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0015)), p.x, p.y);
  }, { passive: false });
  vel.canvas.addEventListener("dblclick", function (e) {
    if (Date.now() - vw.lastTouch < 800) return; // a touch double-tap already zoomed
    var p = vLocal(e);
    vZoomAt(e.shiftKey ? 0.5 : 2, p.x, p.y);
  });
  vel.centre.addEventListener("click", function () { vSetFollow(true); });
  vel.areaBtn.addEventListener("click", function () {
    areaSet.mapOn = !areaSet.mapOn;
    areaMapRender();
  });
  vel.areaSave.addEventListener("click", areaSave);
  vel.areaCancel.addEventListener("click", areaCancel);
  vel.zoomIn.addEventListener("click", function () { vZoomAt(1.5); });
  vel.zoomOut.addEventListener("click", function () { vZoomAt(1 / 1.5); });
  vel.gearBtn.addEventListener("click", function () {
    var open = vel.gearBox.hidden;
    vel.gearBox.hidden = !open;
    vel.gearBtn.setAttribute("aria-expanded", open ? "true" : "false");
  });
  vel.invBtn.addEventListener("click", function () { vSetInvOpen(vel.invBox.hidden, true); });
  vel.debugBtn.addEventListener("click", function () {
    vw.debug = !vw.debug;
    try {
      window.localStorage.setItem(V_DEBUG_KEY, vw.debug ? "1" : "0");
    } catch (e) {
      // not remembered
    }
    vRenderDebug();
  });
  // A theme change: read the canvas colours again.
  new MutationObserver(function () {
    vw.colors = null;
    vDirty();
  }).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

  /** Streamer mode was toggled: names and labels on the canvas and in chat. */
  function viewerStreamer() {
    if (!vw || !vw.open) return;
    vRenderChat();
    vDirty();
  }

  /** The Live nav button: for users with the viewer permission. */
  function updateViewerNav() {
    if (!vel) return;
    var show = pageAllowed("viewer");
    vel.nav.hidden = !show;
    if (!show && dash.page === "viewer") showPage("bots");
  }

  // The page may have been chosen before this code ran (the saved page on startup).
  updateViewerNav();
  if (dash.page === "viewer") {
    vel.page.hidden = false;
    viewerOpen();
  }

  // ---------- streamer mode toggle ----------

  /** relabel: also redraw the pickers whose text depends on the mode (not on startup: no data yet). */
  function renderStreamer(relabel) {
    if (forcedStreamer()) {
      document.documentElement.classList.add("streamer");
    } else if (el.streamerBtn.classList.contains("locked")) {
      // No longer forced: back to this browser's own choice.
      var mine = false;
      try {
        mine = window.localStorage.getItem(STREAMER_KEY) === "1";
      } catch (e) {
        mine = false;
      }
      document.documentElement.classList.toggle("streamer", mine);
    }
    var on = streamerOn();
    var locked = forcedStreamer();
    el.streamerBtn.setAttribute("aria-pressed", on ? "true" : "false");
    el.streamerBtn.setAttribute("aria-disabled", locked ? "true" : "false");
    el.streamerBtn.classList.toggle("locked", locked);
    el.streamerBtn.title = locked ? "Streamer mode is enforced by the owner" : "Blur coordinates, keys, addresses, console and events (this browser only)";
    el.streamerState.textContent = locked ? ": on (enforced)" : on ? ": on" : ": off";
    el.linkKeyToggle.disabled = on;
    el.linkKeyCopy.disabled = on;
    Array.prototype.forEach.call(el.linkUrls.querySelectorAll(".streamer-block"), function (b) { b.disabled = on; });
    if (on && link.showKey) {
      link.showKey = false;
      renderKey();
    }
    if (on) {
      var sel = window.getSelection && window.getSelection();
      if (sel) sel.removeAllRanges(); // a selection made before turning it on would still copy
    }
    if (!relabel) return;
    updateSelect();
    renderBotSelect();
    if (planner.open) plannerFillStashes();
    viewerStreamer();
  }

  el.streamerBtn.addEventListener("click", function () {
    if (forcedStreamer()) {
      toast("Streamer mode is enforced by the owner.");
      renderStreamer(true);
      return;
    }
    var on = !streamerOn();
    document.documentElement.classList.toggle("streamer", on);
    try {
      window.localStorage.setItem(STREAMER_KEY, on ? "1" : "0");
    } catch (e) {
      // storage blocked: it still applies until the page is reloaded
    }
    renderStreamer(true);
  });

  // ---------- appearance: theme picker (this browser only) ----------
  // The choice lives in localStorage ("sm-theme") through theme-init.js, which applies it before the CSS loads.
  // It is not a bot setting: not part of the Settings form, never sent to the server.

  var THEME_CHOICES = [
    { id: "classic", name: "Classic", desc: "Blocky bevels, like now" },
    { id: "modern", name: "Modern", desc: "Soft rounded cards" },
    { id: "light", name: "Light", desc: "Flat and calm" },
    { id: "dark", name: "Dark", desc: "Easy on the eyes" },
    { id: "midnight", name: "Midnight", desc: "OLED black, stream-safe" },
    { id: "mac", name: "Mac", desc: "Smoky glass, peach pills" },
    { id: "contrast", name: "High contrast", desc: "Maximum legibility" },
    { id: "system", name: "Match system", desc: "" }
  ];
  var THEME_NAMES = {};
  THEME_CHOICES.forEach(function (c) { THEME_NAMES[c.id] = c.name; });
  var themeCards = [];

  /** A swatch circle in a theme's own colours: the data-theme attribute scopes that theme's tokens to it. */
  function themeSwatch(theme, token) {
    var s = document.createElement("span");
    s.className = "swatch sw-" + token;
    s.setAttribute("data-theme", theme);
    return s;
  }

  /** Spans, not divs: a button holds phrasing content only. */
  function themeSpan(cls, text) {
    var s = document.createElement("span");
    s.className = cls;
    s.textContent = text;
    return s;
  }

  function buildThemeCards() {
    var grid = $("theme-grid");
    if (!grid) return;
    var themes = window.SM_THEMES;
    if (!themes) {
      grid.appendChild(line("note", "Themes are unavailable: theme-init.js did not load."));
      return;
    }
    THEME_CHOICES.forEach(function (c) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "theme-card";
      b.dataset.choice = c.id;
      var sw = document.createElement("span");
      sw.className = "swatches";
      sw.setAttribute("aria-hidden", "true");
      if (c.id === "system") {
        sw.appendChild(themeSwatch("classic", "surface"));
        sw.appendChild(themeSwatch("dark", "surface"));
        sw.appendChild(themeSwatch("dark", "accent"));
      } else {
        sw.appendChild(themeSwatch(c.id, "bg"));
        sw.appendChild(themeSwatch(c.id, "surface"));
        sw.appendChild(themeSwatch(c.id, "accent"));
      }
      b.appendChild(sw);
      var text = document.createElement("span");
      text.className = "theme-text";
      text.appendChild(themeSpan("theme-name", c.name));
      var desc = themeSpan("theme-desc", c.desc);
      text.appendChild(desc);
      b.appendChild(text);
      b.addEventListener("click", function () { themes.set(c.id); });
      grid.appendChild(b);
      themeCards.push({ id: c.id, button: b, desc: desc });
    });
    themes.onChange(renderThemeCards);
    renderThemeCards(themes.saved());
  }

  function renderThemeCards(pref) {
    themeCards.forEach(function (card) {
      card.button.setAttribute("aria-pressed", card.id === pref ? "true" : "false");
      if (card.id === "system") {
        card.desc.textContent = "Follows this device: " + THEME_NAMES[window.SM_THEMES.resolve("system")] + " now";
      }
    });
  }

  buildThemeCards();

  // Header palette button: open Settings the way its nav button does, then show the Appearance section.
  $("theme-btn").addEventListener("click", function () {
    var nav = el.dashNav.querySelector('.nav-btn[data-page="settings"]');
    if (nav) nav.click();
    var section = $("appearance");
    if (section) section.scrollIntoView({ block: "start" });
    var pressed = document.querySelector('#theme-grid .theme-card[aria-pressed="true"]') || document.querySelector("#theme-grid .theme-card");
    if (pressed) pressed.focus({ preventScroll: true });
  });

  // ---------- mobile layout: bottom tabs, More sheet, account menu, admin chip ----------
  // Extra triggers only: every one of them calls the same control or function as the desktop layout.

  var mobile = {
    nav: $("bottom-nav"),
    more: $("bnav-more"),
    sheet: $("more-sheet"),
    rescan: $("more-rescan"),
    account: $("account-btn"),
    accountMenu: $("account-menu")
  };
  var MORE_PAGES = ["planner", "viewer", "settings", "proxy", "console"];

  /** Opens a page exactly as its desktop nav button does (that button stays the single source of truth). */
  function openNavPage(page) {
    var nav = el.dashNav.querySelector('.nav-btn[data-page="' + page + '"]');
    if (nav) nav.click();
    window.scrollTo(0, 0);
  }

  /** Mirrors the desktop nav (selected page, hidden pages) onto the bottom tabs and the More sheet. */
  function syncMobileNav() {
    var sel = el.dashNav.querySelector(".nav-btn.selected");
    var page = sel ? sel.dataset.page : "";
    var mirror = function (btn) {
      var top = el.dashNav.querySelector('.nav-btn[data-page="' + btn.dataset.page + '"]');
      btn.hidden = !top || top.hidden;
      if (btn.dataset.page === page) btn.setAttribute("aria-current", "page");
      else btn.removeAttribute("aria-current");
    };
    Array.prototype.forEach.call(mobile.nav.querySelectorAll(".bnav-btn[data-page]"), mirror);
    Array.prototype.forEach.call(mobile.sheet.querySelectorAll(".sheet-item[data-page]"), mirror);
    if (MORE_PAGES.indexOf(page) >= 0) mobile.more.setAttribute("aria-current", "page");
    else mobile.more.removeAttribute("aria-current");
  }
  new MutationObserver(syncMobileNav).observe(el.dashNav, { subtree: true, attributes: true, attributeFilter: ["class", "hidden"] });
  syncMobileNav();

  mobile.nav.addEventListener("click", function (e) {
    var b = e.target.closest ? e.target.closest(".bnav-btn[data-page]") : null;
    if (b) openNavPage(b.dataset.page);
  });

  function sheetFocusables() {
    return Array.prototype.filter.call(mobile.sheet.querySelectorAll("button"), function (b) { return !b.hidden && !b.disabled; });
  }

  function openSheet() {
    mobile.rescan.hidden = el.rescanBox.hidden;
    mobile.rescan.disabled = el.rescanBtn.disabled;
    mobile.sheet.hidden = false;
    mobile.more.setAttribute("aria-expanded", "true");
    var f = sheetFocusables();
    var cur = mobile.sheet.querySelector(".sheet-item[aria-current]");
    (cur || f[1] || f[0]).focus();
  }

  function closeSheet(refocus) {
    if (mobile.sheet.hidden) return;
    mobile.sheet.hidden = true;
    mobile.more.setAttribute("aria-expanded", "false");
    if (refocus) mobile.more.focus();
  }

  mobile.more.addEventListener("click", function () {
    if (mobile.sheet.hidden) openSheet();
    else closeSheet(true);
  });
  $("more-close").addEventListener("click", function () { closeSheet(true); });
  mobile.sheet.addEventListener("click", function (e) {
    if (e.target === mobile.sheet) {
      closeSheet(true); // backdrop
      return;
    }
    var item = e.target.closest ? e.target.closest(".sheet-item") : null;
    if (!item) return;
    closeSheet(false);
    if (item === mobile.rescan) el.rescanBtn.click();
    else if (item.id === "more-users") openUsersSection();
    else openNavPage(item.dataset.page);
  });
  mobile.sheet.addEventListener("keydown", function (e) {
    if (e.key !== "Tab") return;
    var f = sheetFocusables();
    if (!f.length) return;
    var first = f[0];
    var last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  });

  function setAccountMenu(open, refocus) {
    mobile.accountMenu.hidden = !open;
    mobile.account.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) mobile.accountMenu.querySelector("button:not([hidden])").focus();
    else if (refocus) mobile.account.focus();
  }

  mobile.account.addEventListener("click", function () { setAccountMenu(mobile.accountMenu.hidden, false); });
  $("account-link").addEventListener("click", function () {
    setAccountMenu(false, false);
    el.linkOpen.click();
  });
  $("account-logout").addEventListener("click", function () {
    setAccountMenu(false, false);
    el.logoutBtn.click();
  });
  document.addEventListener("click", function (e) {
    if (!mobile.accountMenu.hidden && !(e.target.closest && e.target.closest(".account"))) setAccountMenu(false, false);
  });
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    if (!mobile.sheet.hidden) closeSheet(true);
    if (!mobile.accountMenu.hidden) setAccountMenu(false, true);
  });

  $("admin-chip").addEventListener("click", function () {
    if (sudo.until > Date.now()) el.sudoLock.click(); // the existing "Lock now"
    else askPassword().then(function () {}, function () {}); // the existing password prompt
  });

  // A selection that only starts outside a hidden value would still copy it: refuse that copy.
  document.addEventListener("copy", function (e) {
    if (!streamerOn()) return;
    var sel = window.getSelection && window.getSelection();
    if (!sel || !sel.rangeCount) return;
    var marked = document.querySelectorAll("[data-sensitive]");
    for (var i = 0; i < marked.length; i++) {
      if (sel.containsNode(marked[i], true)) {
        e.preventDefault();
        toast("Streamer mode is on: hidden values can't be copied.");
        return;
      }
    }
  });

  renderStreamer(false);

  // ---------- permissions: what this user sees ----------
  // Hides the tabs and buttons the logged-in user can't use (the server refuses them anyway). The owner sees
  // everything, as before sub-users. pagesChanged: the permissions changed while logged in, so the open page is
  // loaded again (it may now be read-only, or gone).

  function applyPerms(pagesChanged) {
    var name = me.name;
    el.userName.hidden = !name;
    el.userName.textContent = name;
    el.userName.title = name ? "Logged in as " + name + (me.owner ? " (owner)" : "") : "";
    el.accountName.hidden = !name;
    el.accountName.textContent = name ? "Logged in as " + name : "";
    var view = can("view");
    var activity = can("activity");
    var stashPicker = document.querySelector(".picker-stash");
    if (stashPicker) stashPicker.hidden = !view;
    var botPicker = document.querySelector(".picker-bot");
    if (botPicker) botPicker.hidden = !activity;
    el.botsOpen.hidden = !activity;
    el.navConsole.hidden = !pageAllowed("console");
    el.navPlanner.hidden = !pageAllowed("planner");
    renderStreamer(false); // forced streamer mode: on and locked
    updateViewerNav();
    plannerPerms();
    updateProxyNav();
    updateActivityNav(false);
    el.linkOpen.hidden = !can("link");
    $("account-link").hidden = !can("link");
    if (!can("link") && link.open) closeLink();
    var order = can("order");
    el.orderPicker.hidden = !order;
    el.cartDrop.hidden = !order;
    document.documentElement.classList.toggle("no-order", !order);
    renderConsoleAllow();
    el.accountSection.hidden = me.owner;
    el.accountWho.textContent = name;
    el.usersSection.hidden = !me.owner;
    $("more-users").hidden = !me.owner;
    updateRescan();
    renderOrderList();
    renderTabs();
    if (!view) showEmpty();
    if (!pageAllowed(dash.page)) showPage("stash");
    else if (pagesChanged && auth.started) showPage(dash.page);
  }

  /** Console "list" mode: the allowed command prefixes, under the input. */
  function renderConsoleAllow() {
    var listMode = permMode("console") === "list";
    el.consoleAllow.hidden = !listMode;
    if (!listMode) {
      el.consoleAllow.replaceChildren();
      return;
    }
    var allow = Array.isArray(me.perms.consoleAllow) ? me.perms.consoleAllow.filter(function (c) { return typeof c === "string" && c; }) : [];
    var frag = document.createDocumentFragment();
    if (!allow.length) {
      frag.appendChild(document.createTextNode("Your account can't run any console command yet; ask the owner."));
    } else {
      frag.appendChild(document.createTextNode("Your account can run only these commands (and their arguments): "));
      allow.forEach(function (c, i) {
        if (i) frag.appendChild(document.createTextNode(", "));
        var code = document.createElement("code");
        code.textContent = c;
        frag.appendChild(code);
      });
    }
    el.consoleAllow.replaceChildren(frag);
  }

  // ---------- settings > users (owner only) ----------
  // Sub-user management. Every write needs Admin unlocked: withSudo asks for the password on 403 "confirm password"
  // and runs the write again. The forms are inline (not windows), so the password prompt doesn't close them.
  // Usernames, audit details and the like come from other people: textContent only.

  var USER_NAME_RE = /^[A-Za-z0-9_.-]{1,32}$/;
  var PERM_SWITCHES = [
    ["view", "View stashes", "Stash list, stash index, item icons and the order list."],
    ["order", "Place orders", "Place orders; cancel, retry or force-cancel their own orders."],
    ["ordersAll", "Manage all orders", "Cancel, retry or force-cancel anyone's orders; Clear history."],
    ["activity", "Bots and activity", "Bots tab, Activity, job status and the plugin settings (read only)."],
    ["settings", "Change plugin settings", "Save the Qwerte Stash plugin settings."],
    ["rescan", "Rescan stashes", "Ask the bot to re-read every container of a stash."],
    ["link", "Link addon", "The Link addon panel (linked bots in scope) and releasing a stash. The API key itself stays owner-only."],
    ["planner", "Build planner", "Upload a schematic and see its material list against a stash (the file itself is not kept)."],
    ["plannerOrder", "Order from the build planner", "Order a build's materials from the planner, in parts of up to 54 shulkers, and cancel or retry their own orders. Doesn't need Place orders."],
    ["viewer", "Live viewer", "Watch a bot live: the map around it, its position, nearby players, mobs and items, its HUD and its chat. Also turns on Bots and activity."],
    ["streamer", "Force streamer mode", "A restriction: the server never sends this user coordinates, player names, server addresses, chat or the bots' free text, and the live viewer shows relative coordinates. Settings, proxy settings and the console are refused. The user can't turn it off."]
  ];
  var PROXY_MODES = [["none", "No access"], ["read", "Read only"], ["change", "Change (needs Admin unlocked)"]];
  var CONSOLE_MODES = [["none", "No access"], ["list", "Only listed commands"], ["full", "Every command"]];
  var PRESET_ORDER = ["viewer", "orderer", "operator"];
  var PRESET_NAMES = { viewer: "Viewer", orderer: "Orderer", operator: "Operator" };
  var IMPLIED_HINT = "Placing or managing orders, or the build planner, also turns on View stashes; ordering from the build planner also turns on Build planner. Changing plugin settings, the live viewer, or any proxy or console " +
    "access, also turns on Bots and activity. Proxy changes and console commands still need Admin unlocked (the user's own password).";
  var SUDO_USERS_TEXT = "Managing users needs Admin unlocked. Type your password again; it is not asked for again for 15 minutes.";
  var users = { data: null, error: "", loading: false, form: null, busy: false, seq: 0 };

  /** The permission object in a fixed shape (and key order, so two can be compared with jsonEq). */
  function normPerms(p) {
    p = p && typeof p === "object" ? p : {};
    var out = {};
    PERM_SWITCHES.forEach(function (s) { out[s[0]] = p[s[0]] === true; });
    out.proxy = p.proxy === "read" || p.proxy === "change" ? p.proxy : "none";
    out.console = p.console === "list" || p.console === "full" ? p.console : "none";
    out.consoleAllow = Array.isArray(p.consoleAllow) ? p.consoleAllow.filter(function (c) { return typeof c === "string"; }) : [];
    out.stashes = Array.isArray(p.stashes)
      ? p.stashes.filter(function (s) { return s && typeof s === "object"; }).map(function (s) { return { server: str(s.server), stash: str(s.stash) }; })
      : null;
    out.bots = Array.isArray(p.bots) ? p.bots.filter(function (b) { return typeof b === "string"; }) : null;
    return out;
  }

  /** The server's implied-permission rule, applied the same way here for the labels and the switches. */
  function withImplied(p) {
    if (p.plannerOrder) p.planner = true;
    if (p.order || p.ordersAll || p.planner) p.view = true;
    if (p.settings || p.viewer || p.proxy !== "none" || p.console !== "none") p.activity = true;
    return p;
  }

  /** Only the parts a preset sets (not the scopes or the console list). */
  function presetShape(p) {
    var n = withImplied(normPerms(p));
    delete n.stashes;
    delete n.bots;
    delete n.consoleAllow;
    return n;
  }

  function presetList() {
    var presets = users.data && users.data.presets && typeof users.data.presets === "object" ? users.data.presets : {};
    var names = PRESET_ORDER.filter(function (n) { return presets[n] && typeof presets[n] === "object"; });
    Object.keys(presets).forEach(function (n) {
      if (names.indexOf(n) < 0 && presets[n] && typeof presets[n] === "object") names.push(n);
    });
    return names.map(function (n) { return { id: n, name: PRESET_NAMES[n] || n, perms: presets[n] }; });
  }

  /** "Viewer", "Orderer", ... or "Custom". */
  function presetLabel(perms) {
    var shape = presetShape(perms);
    var list = presetList();
    for (var i = 0; i < list.length; i++) {
      if (jsonEq(presetShape(list[i].perms), shape)) return list[i].name;
    }
    return "Custom";
  }

  function scopeText(perms) {
    var p = normPerms(perms);
    var parts = [];
    if (p.stashes) parts.push(plural(String(p.stashes.length), "stash").replace(/stashs$/, "stashes"));
    if (p.bots) parts.push(plural(String(p.bots.length), "bot"));
    return parts.length ? "only " + parts.join(", ") : "";
  }

  function failText(e, what) {
    var b = e && e.body;
    if (b && b.error === "cancelled") return "";
    if (e && e.status === 429) return tooMany(b);
    if (b && b.error) return sentence(str(b.error));
    return what + (e && e.network ? ": server unreachable." : e && e.status ? " (HTTP " + e.status + ")." : ".");
  }

  function userWrite(path, body) {
    return withSudo(function () { return api(path, { method: "POST", body: body || {} }); }, SUDO_USERS_TEXT);
  }

  function loadUsers() {
    if (!me.owner || users.loading) return;
    users.loading = true;
    api("/api/v1/users").then(function (d) {
      users.data = d && typeof d === "object" ? d : { users: [] };
      users.error = "";
    }, function (e) {
      if (e && e.status === 403 && e.body && e.body.error === "confirm password") {
        users.data = null;
        users.error = "locked";
      } else {
        users.error = failText(e, "Could not load the users");
      }
    }).then(function () {
      users.loading = false;
      renderUsers();
    });
  }

  function openUsersSection() {
    openNavPage("settings");
    var section = el.usersSection;
    if (section && !section.hidden) section.scrollIntoView({ block: "start" });
  }

  /** A labelled text or password input in the auth-field style. */
  function textField(labelText, type, opts) {
    opts = opts || {};
    var label = document.createElement("label");
    label.className = "auth-field";
    var span = document.createElement("span");
    span.className = "field-label";
    span.textContent = labelText;
    var input = document.createElement("input");
    input.type = type;
    input.className = "mc-input";
    input.maxLength = opts.maxLength || 1024;
    input.autocomplete = opts.autocomplete || "off";
    input.spellcheck = false;
    if (opts.value) input.value = opts.value;
    if (type === "text") input.setAttribute("autocapitalize", "off");
    label.appendChild(span);
    label.appendChild(input);
    return { node: label, input: input };
  }

  /** A checkbox with its text, like the Activity page's "enabled only". */
  function checkField(text, checked) {
    var label = document.createElement("label");
    label.className = "unset user-check";
    var input = document.createElement("input");
    input.type = "checkbox";
    input.checked = !!checked;
    label.appendChild(input);
    label.appendChild(document.createTextNode(" " + text));
    return { node: label, input: input };
  }

  function selectField(options, value, ariaLabel) {
    var sel = document.createElement("select");
    sel.className = "mc-select";
    sel.setAttribute("aria-label", ariaLabel);
    options.forEach(function (o) {
      var opt = document.createElement("option");
      opt.value = o[0];
      opt.textContent = o[1];
      sel.appendChild(opt);
    });
    sel.value = value;
    return sel;
  }

  /** A row in the settings-form style: name and help on the left, the control, then a status column. */
  function permRow(name, help) {
    var row = document.createElement("div");
    row.className = "setting";
    var labelBox = document.createElement("div");
    labelBox.className = "setting-label";
    labelBox.appendChild(line("setting-name", name));
    if (help) labelBox.appendChild(line("setting-help", help));
    row.appendChild(labelBox);
    var control = document.createElement("div");
    control.className = "setting-control";
    row.appendChild(control);
    var status = document.createElement("div");
    status.className = "setting-status";
    row.appendChild(status);
    return { row: row, control: control, status: status };
  }

  /** The same ON / OFF switch as a bool setting. */
  function switchControl(label, on, onChange) {
    var t = document.createElement("button");
    t.type = "button";
    t.className = "mc-button toggle";
    t.setAttribute("role", "switch");
    t.setAttribute("aria-label", label);
    var text = document.createElement("span");
    text.className = "toggle-text";
    t.appendChild(text);
    var cur = !!on;
    var show = function () {
      text.textContent = cur ? "ON" : "OFF";
      t.classList.toggle("on", cur);
      t.setAttribute("aria-checked", cur ? "true" : "false");
    };
    t.addEventListener("click", function () {
      cur = !cur;
      show();
      onChange();
    });
    show();
    return { node: t, get: function () { return cur; }, set: function (v) { cur = !!v; show(); } };
  }

  /**
   * "All <things>" or a checklist. items: [{ key, value, label: Node }]; selected: null (all) or values, matched
   * with keyOf. Selected values that aren't in items (a stash or bot not seen now) stay listed and ticked.
   */
  function scopePicker(allText, someText, items, selected, keyOf, unknownLabel) {
    var box = document.createElement("div");
    box.className = "scope-picker";
    var group = "scope-" + (++users.seq);
    var mk = function (text, checked) {
      var label = document.createElement("label");
      label.className = "unset user-check";
      var r = document.createElement("input");
      r.type = "radio";
      r.name = group;
      r.checked = checked;
      label.appendChild(r);
      label.appendChild(document.createTextNode(" " + text));
      box.appendChild(label);
      return r;
    };
    var all = mk(allText, selected === null);
    var some = mk(someText, selected !== null);
    var list = document.createElement("div");
    list.className = "scope-list";
    var picked = {};
    (selected || []).forEach(function (v) { picked[keyOf(v)] = v; });
    var rows = [];
    var seen = {};
    items.forEach(function (it) {
      seen[it.key] = true;
      rows.push({ value: it.value, label: it.label, checked: picked.hasOwnProperty(it.key) });
    });
    Object.keys(picked).forEach(function (k) {
      if (!seen[k]) rows.push({ value: picked[k], label: unknownLabel(picked[k]), checked: true });
    });
    var boxes = [];
    rows.forEach(function (r) {
      var label = document.createElement("label");
      label.className = "unset user-check";
      var c = document.createElement("input");
      c.type = "checkbox";
      c.checked = r.checked;
      label.appendChild(c);
      label.appendChild(document.createTextNode(" "));
      label.appendChild(r.label);
      list.appendChild(label);
      boxes.push({ input: c, value: r.value });
    });
    if (!rows.length) list.appendChild(line("note", "None known yet."));
    box.appendChild(list);
    var sync = function () { list.hidden = !some.checked; };
    all.addEventListener("change", sync);
    some.addEventListener("change", sync);
    sync();
    return {
      node: box,
      get: function () {
        if (all.checked) return null;
        return boxes.filter(function (b) { return b.input.checked; }).map(function (b) { return b.value; });
      }
    };
  }

  function stashScopeLabel(s) {
    var span = document.createElement("span");
    span.appendChild(document.createTextNode(str(s.stash) + " @ "));
    span.appendChild(hiddenSpan(str(s.server)));
    return span;
  }

  /** Switches for the yes/no permissions, selects for proxy and console, the console list, and the scopes. */
  function permsEditor(initial) {
    var p = normPerms(initial);
    var box = document.createElement("div");
    box.className = "perm-editor";
    var sw = {};
    var refresh = function () {
      var forcePlanner = sw.plannerOrder.get();
      var forceView = sw.order.get() || sw.ordersAll.get() || sw.planner.get() || forcePlanner;
      var forceActivity = sw.settings.get() || sw.viewer.get() || proxySel.value !== "none" || consoleSel.value !== "none";
      [["planner", forcePlanner], ["view", forceView], ["activity", forceActivity]].forEach(function (f) {
        var s = sw[f[0]];
        if (f[1]) s.set(true);
        s.node.disabled = f[1];
        s.status.textContent = f[1] ? "on (implied)" : "";
      });
      allowRow.row.hidden = consoleSel.value !== "list";
    };
    PERM_SWITCHES.forEach(function (s) {
      var r = permRow(s[1], s[2]);
      var c = switchControl(s[1], p[s[0]], function () { refresh(); });
      r.control.appendChild(c.node);
      c.status = r.status;
      sw[s[0]] = c;
      box.appendChild(r.row);
    });
    var proxyRow = permRow("Proxy settings", "AquariusProxy and other plugins' settings.");
    var proxySel = selectField(PROXY_MODES, p.proxy, "Proxy settings");
    proxySel.addEventListener("change", function () { refresh(); });
    proxyRow.control.appendChild(proxySel);
    box.appendChild(proxyRow.row);
    var consoleRow = permRow("Console", "The console output includes other users' commands.");
    var consoleSel = selectField(CONSOLE_MODES, p.console, "Console");
    consoleSel.addEventListener("change", function () { refresh(); });
    consoleRow.control.appendChild(consoleSel);
    box.appendChild(consoleRow.row);
    var allowRow = permRow("Allowed commands", "One command prefix per line, e.g. stash orders. Arguments after it are allowed; ; & | and ` are refused.");
    var allow = document.createElement("textarea");
    allow.className = "mc-input allow-input";
    allow.rows = 4;
    allow.spellcheck = false;
    allow.setAttribute("aria-label", "Allowed commands, one per line");
    allow.value = p.consoleAllow.join("\n");
    allowRow.control.appendChild(allow);
    box.appendChild(allowRow.row);

    var stashItems = state.stashes.map(function (s) {
      return { key: stashKey(s), value: { server: str(s.server), stash: str(s.stash) }, label: stashScopeLabel(s) };
    });
    var stashRow = permRow("Stashes", "The stashes this user may see and use.");
    var stashPick = scopePicker("All stashes", "Only these stashes:", stashItems, p.stashes, stashKey, function (v) {
      var l = stashScopeLabel(v);
      l.appendChild(document.createTextNode(" (not seen now)"));
      return l;
    });
    stashRow.control.appendChild(stashPick.node);
    box.appendChild(stashRow.row);
    var botItems = dash.bots.map(function (b) {
      var span = document.createElement("span");
      span.textContent = botName(b) + (botName(b) !== b.botId ? " (" + b.botId + ")" : "");
      return { key: b.botId, value: b.botId, label: span };
    });
    var botRow = permRow("Bots", "The dashboard bots this user may see and use.");
    var botPick = scopePicker("All bots", "Only these bots:", botItems, p.bots, function (v) { return v; }, function (v) {
      var span = document.createElement("span");
      span.textContent = v + " (not seen now)";
      return span;
    });
    botRow.control.appendChild(botPick.node);
    box.appendChild(botRow.row);
    box.appendChild(line("note", IMPLIED_HINT));
    refresh();

    return {
      node: box,
      get: function () {
        var out = {};
        PERM_SWITCHES.forEach(function (s) { out[s[0]] = sw[s[0]].get(); });
        out.proxy = proxySel.value;
        out.console = consoleSel.value;
        var seen = {};
        out.consoleAllow = allow.value.split("\n").map(function (l) { return l.trim().replace(/\s+/g, " "); }).filter(function (l) {
          var k = l.toLowerCase();
          if (!l || seen[k]) return false;
          seen[k] = true;
          return true;
        });
        out.stashes = stashPick.get();
        out.bots = botPick.get();
        return withImplied(out);
      }
    };
  }

  function formButtons(form, saveText, onCancel) {
    var row = document.createElement("div");
    row.className = "user-form-actions";
    var save = document.createElement("button");
    save.type = "submit";
    save.className = "mc-button primary";
    save.textContent = saveText;
    var cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "mc-button";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", onCancel);
    row.appendChild(save);
    row.appendChild(cancel);
    form.appendChild(row);
    var msg = line("note warn", "");
    msg.setAttribute("role", "status");
    form.appendChild(msg);
    return { save: save, msg: msg };
  }

  function newForm(title) {
    var form = document.createElement("form");
    form.className = "user-form";
    form.noValidate = true;
    if (title) form.appendChild(line("settings-group-title", title));
    return form;
  }

  function closeUserForm() {
    users.form = null;
    renderUsers();
  }

  /** Runs a user-management write from a form: busy state, message on failure, reload on success. */
  function submitUserWrite(btn, msg, path, body, okText, failWhat) {
    btn.disabled = true;
    msg.textContent = "Saving...";
    userWrite(path, body).then(function () {
      users.form = null;
      toast(okText);
      loadUsers();
    }, function (e) {
      btn.disabled = false;
      var t = failText(e, failWhat);
      msg.textContent = t;
      if (t && !msg.isConnected) toast(t);
    });
  }

  function checkNewPassword(pw, again) {
    if (pw.length < MIN_PASSWORD) return "The password needs at least " + MIN_PASSWORD + " characters.";
    if (pw !== again) return "The two passwords are different.";
    return "";
  }

  function checkUserName(name, selfId) {
    if (!USER_NAME_RE.test(name)) return "A username is 1 to 32 letters, digits, _ . or -.";
    var lower = name.toLowerCase();
    var d = users.data || {};
    if (selfId !== "owner" && d.owner && str(d.owner.name).toLowerCase() === lower) return "That is the owner's name.";
    var clash = (Array.isArray(d.users) ? d.users : []).some(function (u) { return u && u.id !== selfId && str(u.name).toLowerCase() === lower; });
    return clash ? "Another user already has that name." : "";
  }

  function addUserForm() {
    var form = newForm("Add user");
    var name = textField("Username (1-32 letters, digits, _ . -)", "text", { maxLength: 32 });
    var pw = textField("Temporary password (at least " + MIN_PASSWORD + " characters)", "password", { autocomplete: "new-password" });
    var again = textField("Temporary password again", "password", { autocomplete: "new-password" });
    form.appendChild(name.node);
    form.appendChild(pw.node);
    form.appendChild(again.node);
    var presets = presetList();
    var options = presets.map(function (p) { return [p.id, p.name]; }).concat([["custom", "Custom"]]);
    var presetLabelEl = document.createElement("label");
    presetLabelEl.className = "auth-field";
    presetLabelEl.appendChild(line("field-label", "Permissions"));
    var sel = selectField(options, presets.length ? presets[0].id : "custom", "Permissions");
    presetLabelEl.appendChild(sel);
    form.appendChild(presetLabelEl);
    var presetHint = line("note", "");
    form.appendChild(presetHint);
    var editorHost = document.createElement("div");
    form.appendChild(editorHost);
    var editor = null;
    var last = presets.length ? presets[0].perms : { view: true };
    var describe = function (perms) {
      var p = withImplied(normPerms(perms));
      var on = PERM_SWITCHES.filter(function (s) { return p[s[0]]; }).map(function (s) { return s[1]; });
      if (p.proxy !== "none") on.push("Proxy settings: " + p.proxy);
      if (p.console !== "none") on.push("Console: " + p.console);
      return on.length ? on.join(", ") + "." : "Nothing.";
    };
    var sync = function () {
      var preset = null;
      presets.forEach(function (p) { if (p.id === sel.value) preset = p; });
      if (preset) {
        last = preset.perms;
        editor = null;
        editorHost.replaceChildren();
        presetHint.hidden = false;
        presetHint.textContent = describe(preset.perms);
      } else {
        presetHint.hidden = true;
        if (!editor) {
          editor = permsEditor(last); // starts from the preset picked before
          editorHost.replaceChildren(editor.node);
        }
      }
    };
    sel.addEventListener("change", sync);
    sync();
    var must = checkField("Must change the password at first login", true);
    form.appendChild(must.node);
    var b = formButtons(form, "Add user", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var n = name.input.value.trim();
      var bad = checkUserName(n, null) || checkNewPassword(pw.input.value, again.input.value);
      if (bad) return void (b.msg.textContent = bad);
      var body = { name: n, password: pw.input.value, mustChange: must.input.checked };
      if (editor) body.perms = editor.get();
      else body.preset = sel.value;
      submitUserWrite(b.save, b.msg, "/api/v1/users", body, "Added " + n, "Could not add the user");
    });
    return { node: form, focus: name.input };
  }

  function editUserForm(u) {
    var form = newForm("Edit " + str(u.name));
    var name = textField("Username", "text", { maxLength: 32, value: str(u.name) });
    form.appendChild(name.node);
    var editor = permsEditor(u.perms);
    form.appendChild(editor.node);
    var b = formButtons(form, "Save", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var n = name.input.value.trim();
      var body = { perms: editor.get() };
      if (n !== str(u.name)) {
        var bad = checkUserName(n, u.id);
        if (bad) return void (b.msg.textContent = bad);
        body.name = n;
      }
      submitUserWrite(b.save, b.msg, "/api/v1/users/" + encodeURIComponent(u.id), body, "Saved " + n, "Could not save the user");
    });
    return { node: form, focus: name.input };
  }

  function resetPasswordForm(u) {
    var form = newForm("Reset the password of " + str(u.name));
    var pw = textField("New temporary password (at least " + MIN_PASSWORD + " characters)", "password", { autocomplete: "new-password" });
    var again = textField("New temporary password again", "password", { autocomplete: "new-password" });
    form.appendChild(pw.node);
    form.appendChild(again.node);
    var must = checkField("Must change the password at next login", true);
    form.appendChild(must.node);
    form.appendChild(line("note", "Every session of " + str(u.name) + " ends now."));
    var b = formButtons(form, "Reset password", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var bad = checkNewPassword(pw.input.value, again.input.value);
      if (bad) return void (b.msg.textContent = bad);
      submitUserWrite(b.save, b.msg, "/api/v1/users/" + encodeURIComponent(u.id) + "/password",
        { password: pw.input.value, mustChange: must.input.checked }, "Password reset for " + str(u.name), "Could not reset the password");
    });
    return { node: form, focus: pw.input };
  }

  function renameOwnerForm(current) {
    var form = newForm("Rename the owner");
    var name = textField("Owner name (1-32 letters, digits, _ . -)", "text", { maxLength: 32, value: current });
    form.appendChild(name.node);
    form.appendChild(line("note", "The owner can still log in with a blank username."));
    var b = formButtons(form, "Rename", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var n = name.input.value.trim();
      if (n === current) return void closeUserForm();
      var bad = checkUserName(n, "owner");
      if (bad) return void (b.msg.textContent = bad);
      submitUserWrite(b.save, b.msg, "/api/v1/users/owner", { name: n }, "The owner is now " + n, "Could not rename the owner");
      refreshSudoSoon();
    });
    return { node: form, focus: name.input };
  }

  /** The header shows the owner's name: read it again once the rename went through. */
  function refreshSudoSoon() {
    window.setTimeout(refreshSudo, 1500);
  }

  /** A one-click action (Disable, Log out everywhere, Delete): confirmed first, then written. */
  function userAction(btn, confirmText, path, body, okText, failWhat) {
    if (confirmText && !window.confirm(confirmText)) return;
    btn.disabled = true;
    userWrite(path, body).then(function (r) {
      toast(typeof okText === "function" ? okText(r) : okText);
      loadUsers();
    }, function (e) {
      btn.disabled = false;
      var t = failText(e, failWhat);
      if (t) toast(t);
    });
  }

  function smallButton(text, cls, onClick) {
    var b = document.createElement("button");
    b.type = "button";
    b.className = "mc-button small" + (cls ? " " + cls : "");
    b.textContent = text;
    b.addEventListener("click", function () { onClick(b); });
    return b;
  }

  function toggleForm(kind, id) {
    var f = users.form;
    users.form = f && f.kind === kind && f.id === id ? null : { kind: kind, id: id };
    renderUsers();
  }

  function userRow(u, focusTarget) {
    var row = document.createElement("div");
    row.className = "user-row" + (u.disabled ? " is-disabled" : "");
    var head = document.createElement("div");
    head.className = "user-head";
    head.appendChild(themeSpan("user-title", str(u.name)));
    head.appendChild(themeSpan("badge", presetLabel(u.perms)));
    if (u.disabled === true) head.appendChild(themeSpan("badge st-failed", "Disabled"));
    if (u.mustChange === true) head.appendChild(themeSpan("badge st-accepted", "Must change password"));
    row.appendChild(head);
    var last = typeof u.lastLoginAt === "number" ? u.lastLoginAt : null;
    var meta = ["Last login " + (last === null ? "never" : ago(last)), plural(String(num(u.sessions, 0)), "session")];
    var scope = scopeText(u.perms);
    if (scope) meta.push(scope);
    var metaLine = line("note user-meta", meta.join(" · "));
    if (last !== null) metaLine.title = "Last login " + new Date(last).toLocaleString();
    row.appendChild(metaLine);
    var actions = document.createElement("div");
    actions.className = "user-actions";
    var id = encodeURIComponent(u.id);
    var name = str(u.name);
    actions.appendChild(smallButton("Edit", "", function () { toggleForm("edit", u.id); }));
    actions.appendChild(smallButton(u.disabled ? "Enable" : "Disable", "", function (b) {
      userAction(b, u.disabled ? "" : "Disable " + name + "? Every session of " + name + " ends now, and they can't log in until you enable the account again.",
        "/api/v1/users/" + id, { disabled: !u.disabled }, (u.disabled ? "Enabled " : "Disabled ") + name, "Could not change the account");
    }));
    actions.appendChild(smallButton("Reset password", "", function () { toggleForm("reset", u.id); }));
    actions.appendChild(smallButton("Log out everywhere", "", function (b) {
      userAction(b, "Log " + name + " out of every browser?", "/api/v1/users/" + id + "/logout", {}, function (r) {
        var n = r && typeof r.ended === "number" ? r.ended : 0;
        return name + ": " + plural(String(n), "session") + " ended";
      }, "Could not log the user out");
    }));
    actions.appendChild(smallButton("Delete", "danger", function (b) {
      userAction(b, "Delete the user " + name + "?\n\nThis can't be undone. Their sessions end now; their orders stay.",
        "/api/v1/users/" + id + "/delete", {}, "Deleted " + name, "Could not delete the user");
    }));
    row.appendChild(actions);
    var f = users.form;
    if (f && f.id === u.id) {
      var built = f.kind === "edit" ? editUserForm(u) : f.kind === "reset" ? resetPasswordForm(u) : null;
      if (built) {
        row.appendChild(built.node);
        focusTarget.input = built.focus;
      }
    }
    return row;
  }

  function renderUsers() {
    if (!el.usersBody) return;
    var frag = document.createDocumentFragment();
    var d = users.data;
    if (!d) {
      if (users.error === "locked") {
        frag.appendChild(unlockBox("The users are only shown after you confirm your password.", function () {
          users.error = "";
          loadUsers();
        }));
      } else {
        frag.appendChild(line(users.error ? "note warn" : "note", users.error || "Loading..."));
      }
      el.usersBody.replaceChildren(frag);
      return;
    }
    var focusTarget = { input: null };
    var list = (Array.isArray(d.users) ? d.users : []).filter(function (u) { return u && typeof u === "object" && typeof u.id === "string"; });
    var ownerName = d.owner && typeof d.owner === "object" ? str(d.owner.name) : "";

    var ownerRow = document.createElement("div");
    ownerRow.className = "user-row owner-row";
    var ownerHead = document.createElement("div");
    ownerHead.className = "user-head";
    ownerHead.appendChild(themeSpan("user-title", ownerName || "owner"));
    ownerHead.appendChild(themeSpan("badge", "Owner"));
    ownerRow.appendChild(ownerHead);
    ownerRow.appendChild(line("note user-meta", "Every permission, always. Logs in with this name or a blank username."));
    var ownerActions = document.createElement("div");
    ownerActions.className = "user-actions";
    ownerActions.appendChild(smallButton("Rename", "", function () { toggleForm("owner", "owner"); }));
    ownerRow.appendChild(ownerActions);
    if (users.form && users.form.kind === "owner") {
      var rf = renameOwnerForm(ownerName);
      ownerRow.appendChild(rf.node);
      focusTarget.input = rf.focus;
    }
    frag.appendChild(ownerRow);

    var max = num(d.maxUsers, 50);
    var bar = document.createElement("div");
    bar.className = "users-bar";
    bar.appendChild(line("note", list.length + " of " + max + " sub-users"));
    var add = smallButton("Add user", "primary", function () { toggleForm("add", null); });
    add.disabled = list.length >= max;
    if (add.disabled) add.title = "At most " + max + " sub-users";
    bar.appendChild(add);
    frag.appendChild(bar);
    if (users.form && users.form.kind === "add") {
      var af = addUserForm();
      frag.appendChild(af.node);
      focusTarget.input = af.focus;
    }

    if (!list.length) frag.appendChild(line("note", "No sub-users yet. Add one to let someone else in with fewer permissions."));
    list.forEach(function (u) { frag.appendChild(userRow(u, focusTarget)); });
    frag.appendChild(line("note", IMPLIED_HINT));
    el.usersBody.replaceChildren(frag);
    if (focusTarget.input) focusTarget.input.focus({ preventScroll: false });
  }

  // ----- audit log -----

  function loadAudit() {
    if (!me.owner) return;
    el.auditStatus.textContent = "Loading...";
    el.auditRefresh.disabled = true;
    api("/api/v1/audit?limit=200").then(function (d) {
      var entries = d && Array.isArray(d.entries) ? d.entries.filter(function (x) { return x && typeof x === "object"; }) : [];
      renderAudit(entries);
      el.auditStatus.textContent = entries.length ? plural(String(entries.length), "entry").replace(/entrys$/, "entries") + ", newest first" : "";
    }, function (e) {
      el.auditStatus.textContent = failText(e, "Could not load the audit log");
    }).then(function () {
      el.auditRefresh.disabled = false;
    });
  }

  function renderAudit(entries) {
    var frag = document.createDocumentFragment();
    if (!entries.length) frag.appendChild(line("note", "Nothing logged yet."));
    entries.forEach(function (x) {
      var row = document.createElement("div");
      row.className = "audit-row" + (/failed$/.test(str(x.action)) ? " audit-fail" : "");
      var at = num(x.at, 0);
      var time = themeSpan("audit-time", at ? new Date(at).toLocaleString() : "");
      row.appendChild(time);
      row.appendChild(themeSpan("audit-user", str(x.user) || "–"));
      row.appendChild(themeSpan("audit-action", str(x.action)));
      var detail = hide(themeSpan("audit-detail", str(x.detail)));
      if (str(x.address)) {
        var addr = hiddenSpan(" · " + str(x.address));
        addr.className = "audit-address";
        detail.appendChild(addr);
      }
      row.appendChild(detail);
      frag.appendChild(row);
    });
    el.auditList.replaceChildren(frag);
  }

  el.audit.addEventListener("toggle", function () {
    if (el.audit.open) loadAudit();
  });
  el.auditRefresh.addEventListener("click", loadAudit);

  // ---------- dashboard: Activity page ----------
  // A live view of what the proxy is doing: connection, "now doing" items, modules, plugins and an event feed.
  // Polled every 2 s while visible (the bot then pushes every 2 s), every 15 s otherwise. textContent only.

  var act = { timer: 0, data: null, bot: null, levelFilter: "", sourceFilter: "", moduleQuery: "", enabledOnly: false };
  var LEVEL_COLORS = { info: "lvl-info", warn: "lvl-warn", error: "lvl-error" };

  function durationText(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + "s";
    var m = Math.floor(s / 60);
    if (m < 60) return m + "m";
    var h = Math.floor(m / 60);
    if (h < 48) return h + "h " + (m % 60) + "m";
    return Math.floor(h / 24) + "d " + (h % 24) + "h";
  }

  function loadActivity() {
    window.clearTimeout(act.timer);
    var b = currentBot();
    var visible = dash.page === "activity" && !document.hidden;
    if (dash.page === "activity" && b && hasCap(b, "activity") && !auth.blocked) {
      var botId = b.botId;
      api("/api/v1/bots/" + encodeURIComponent(botId) + "/activity").then(function (d) {
        if (botId !== dash.botId || dash.page !== "activity") return;
        if (act.bot !== botId) {
          act.bot = botId;
          act.sourceFilter = "";
        }
        act.data = d;
        renderActivity();
      }, function () {});
    } else if (dash.page === "activity") {
      el.actBody.hidden = true;
      el.actEmpty.hidden = false;
      el.actEmpty.textContent = !b ? "No dashboard bot yet." : "This bot doesn't send its activity (update the Qwerte Stash plugin).";
    }
    if (dash.page === "activity") act.timer = window.setTimeout(loadActivity, visible ? 2000 : 15000);
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && dash.page === "activity") loadActivity();
  });

  /** Ten pixel "hearts" or "drumsticks" for a 0-20 value, with half steps. */
  function pips(value, cls) {
    var box = document.createElement("span");
    box.className = "pips " + cls;
    var v = Math.max(0, Math.min(20, Math.round(num(value, 0))));
    for (var i = 0; i < 10; i++) {
      var p = document.createElement("span");
      var fill = v - i * 2;
      p.className = "pip" + (fill >= 2 ? " full" : fill === 1 ? " half" : "");
      box.appendChild(p);
    }
    box.title = v + " / 20";
    return box;
  }

  /** Health / food: pips (desktop), a thin bar (mobile) and "n / 20". */
  function vital(value, cls) {
    var v = Math.max(0, Math.min(20, Math.round(num(value, 0))));
    var box = document.createElement("span");
    box.className = "vital vital-" + cls;
    var p = pips(value, cls);
    p.classList.add("only-desktop");
    box.appendChild(p);
    var meter = document.createElement("span");
    meter.className = "meter only-mobile";
    var fill = document.createElement("span");
    fill.className = "meter-fill";
    fill.style.width = v * 5 + "%"; // CSSOM, allowed by the CSP
    meter.appendChild(fill);
    box.appendChild(meter);
    var n = document.createElement("span");
    n.className = "vital-num";
    n.textContent = v + " / 20";
    box.appendChild(n);
    return box;
  }

  /** A sensitive value that reads "Hidden (streamer)" in streamer mode (the value itself is not rendered then). */
  function streamerValue(text) {
    var wrap = document.createElement("span");
    wrap.className = "sv";
    var v = hiddenSpan(text);
    v.classList.add("sv-value");
    wrap.appendChild(v);
    var h = document.createElement("span");
    h.className = "sv-hidden";
    h.textContent = "Hidden (streamer)";
    wrap.appendChild(h);
    return wrap;
  }

  function stat(label, valueNode) {
    var d = document.createElement("div");
    d.className = "act-stat";
    d.appendChild(line("act-stat-label", label));
    var v = document.createElement("div");
    v.className = "act-stat-value";
    if (typeof valueNode === "string") v.textContent = valueNode;
    else v.appendChild(valueNode);
    d.appendChild(v);
    return d;
  }

  function renderActivity() {
    var d = act.data;
    var a = d && d.activity && typeof d.activity === "object" ? d.activity : null;
    el.actEmpty.hidden = !!a;
    el.actBody.hidden = !a;
    if (!a) {
      el.actEmpty.textContent = "Waiting for the bot's first activity report...";
      return;
    }
    // --- status card ---
    var c = a.connection && typeof a.connection === "object" ? a.connection : {};
    var head = document.createDocumentFragment();
    var actBot = currentBot();
    if (actBot) head.appendChild(line("act-bot only-mobile", botName(actBot)));
    var badge = document.createElement("span");
    var stateName = str(c.state) || "offline";
    badge.className = "conn-badge conn-" + stateName;
    badge.textContent = stateName === "queue" ? "In queue" + (typeof c.queuePosition === "number" ? " #" + c.queuePosition : "") +
      (str(c.queueEta) ? " · ETA " + str(c.queueEta) : "") : stateName.charAt(0).toUpperCase() + stateName.slice(1);
    head.appendChild(badge);
    if (str(c.server)) {
      var srv = document.createElement("div");
      srv.className = "act-server";
      srv.appendChild(streamerValue(str(c.server)));
      head.appendChild(srv);
    }
    var age = d.receivedAt ? serverNow() - num(d.receivedAt, 0) : null;
    var upd = line("note act-updated" + (age !== null && age > 60000 ? " warn" : ""), age === null ? "" :
      "last update " + durationText(age) + " ago" + (age > 60000 ? " (stale: the bot isn't reporting)" : ""));
    head.appendChild(upd);
    el.actHead.replaceChildren(head);

    var stats = document.createDocumentFragment();
    if (str(c.dimension)) stats.appendChild(stat("Dimension", str(c.dimension).replace(/^minecraft:/, "")));
    if (typeof c.x === "number") {
      stats.appendChild(stat("Position", streamerValue(Math.round(c.x) + ", " + Math.round(num(c.y, 0)) + ", " + Math.round(num(c.z, 0)))));
    }
    if (typeof c.health === "number") stats.appendChild(stat("Health", vital(c.health, "hearts")));
    if (typeof c.food === "number") stats.appendChild(stat("Food", vital(c.food, "food")));
    if (typeof c.ping === "number") stats.appendChild(stat("Ping", c.ping + " ms"));
    if (typeof c.uptimeSeconds === "number") stats.appendChild(stat("Uptime", durationText(c.uptimeSeconds * 1000)));
    if (typeof c.spectators === "number") stats.appendChild(stat("Spectators", String(c.spectators)));
    stats.appendChild(stat("Controlled by", str(c.controllingPlayer) || "nobody"));
    el.actStats.replaceChildren(stats);

    // --- now doing ---
    var cur = Array.isArray(a.current) ? a.current : [];
    var nf = document.createDocumentFragment();
    if (!cur.length) nf.appendChild(line("note", "Nothing running right now."));
    cur.forEach(function (it) {
      if (!it || typeof it !== "object") return;
      var card = document.createElement("div");
      card.className = "now-card " + (LEVEL_COLORS[it.level] || "lvl-info");
      var top = document.createElement("div");
      top.className = "now-top";
      top.appendChild(line("now-source", str(it.source) || "?"));
      if (typeof it.since === "number") top.appendChild(line("note", "since " + durationText(serverNow() - it.since)));
      card.appendChild(top);
      if (str(it.title)) card.appendChild(line("now-title", str(it.title)));
      if (str(it.detail)) card.appendChild(hide(line("now-detail", str(it.detail))));
      if (it.progress && typeof it.progress === "object" && num(it.progress.total, 0) > 0) {
        var bar = document.createElement("div");
        bar.className = "progress";
        var fill = document.createElement("div");
        fill.className = "progress-fill";
        fill.style.transform = "scaleX(" + Math.max(0, Math.min(1, num(it.progress.done, 0) / num(it.progress.total, 1))) + ")";
        bar.appendChild(fill);
        card.appendChild(bar);
        card.appendChild(line("note", num(it.progress.done, 0) + " / " + num(it.progress.total, 0)));
      }
      nf.appendChild(card);
    });
    el.actNow.replaceChildren(nf);

    renderModules(a);

    // --- plugins ---
    var pf = document.createDocumentFragment();
    (Array.isArray(a.plugins) ? a.plugins : []).forEach(function (p) {
      if (!p || typeof p !== "object") return;
      var row = document.createElement("div");
      row.className = "plugin-row";
      row.appendChild(line("plugin-name", str(p.name) || str(p.id)));
      row.appendChild(line("note", (str(p.id) && str(p.name) ? str(p.id) + " " : "") + (str(p.version) ? "v" + str(p.version) : "")));
      pf.appendChild(row);
    });
    el.actPlugins.replaceChildren(pf);

    renderEvents();
  }

  function renderModules(a) {
    var mods = Array.isArray(a.modules) ? a.modules : [];
    var q = act.moduleQuery;
    var groups = new Map();
    var on = 0;
    mods.forEach(function (m) {
      if (!m || typeof m !== "object") return;
      if (m.enabled) on++;
      if (act.enabledOnly && !m.enabled) return;
      if (q && (str(m.name) + " " + str(m.plugin) + " " + str(m.status)).toLowerCase().indexOf(q) < 0) return;
      var p = str(m.plugin) || "aquarius";
      if (!groups.has(p)) groups.set(p, []);
      groups.get(p).push(m);
    });
    el.actModCount.textContent = on + " of " + mods.length + " on";
    var proxyOk = hasCap(currentBot(), "proxy");
    var frag = document.createDocumentFragment();
    groups.forEach(function (list, plugin) {
      frag.appendChild(line("settings-group-title", plugin === "aquarius" ? "AquariusProxy" : plugin));
      var grid = document.createElement("div");
      grid.className = "module-grid";
      list.forEach(function (m) {
        var b = document.createElement(proxyOk ? "button" : "div");
        if (proxyOk) b.type = "button";
        b.className = "module-chip" + (m.enabled ? " on" : "");
        var dot = document.createElement("span");
        dot.className = "dot only-desktop " + (m.enabled ? "dot-on" : "dot-off");
        b.appendChild(dot);
        var mName = document.createElement("span");
        mName.className = "module-name";
        mName.textContent = " " + str(m.name);
        b.appendChild(mName);
        if (str(m.status)) b.appendChild(hide(line("module-status", str(m.status))));
        // Mobile: a switch showing the state. Read-only: the dashboard has no way to toggle a module from here.
        var sw = document.createElement("span");
        sw.className = "switch only-mobile";
        sw.setAttribute("aria-checked", m.enabled ? "true" : "false");
        if (proxyOk) {
          sw.setAttribute("aria-hidden", "true"); // inside a button: the state is read from the text below
          var srState = document.createElement("span");
          srState.className = "sr-only";
          srState.textContent = m.enabled ? ", on" : ", off";
          b.appendChild(srState);
        } else {
          sw.setAttribute("role", "switch");
          sw.setAttribute("aria-readonly", "true");
          sw.setAttribute("aria-disabled", "true");
          sw.setAttribute("aria-label", str(m.name));
        }
        b.appendChild(sw);
        b.title = (m.enabled ? "On" : "Off") + (proxyOk ? " · click for its settings" : "");
        if (proxyOk) b.addEventListener("click", function () { jumpToModule(str(m.name)); });
        grid.appendChild(b);
      });
      frag.appendChild(grid);
    });
    if (!groups.size) frag.appendChild(line("note", "No modules match."));
    el.actModules.replaceChildren(frag);
  }

  function jumpToModule(name) {
    proxyScope = "proxy";
    proxyForm.jump(name);
    showPage("proxy");
  }

  function renderEvents() {
    var d = act.data;
    var events = d && Array.isArray(d.events) ? d.events.slice() : [];
    events.sort(function (x, y) { return num(y && y.at, 0) - num(x && x.at, 0); }); // newest first, by time
    // the source filter's options
    var sources = [];
    events.forEach(function (e) { if (e && str(e.source) && sources.indexOf(str(e.source)) < 0) sources.push(str(e.source)); });
    sources.sort();
    var want = [""].concat(sources).join("\u0000");
    if (el.actSource.dataset.opts !== want) {
      el.actSource.dataset.opts = want;
      var of = document.createDocumentFragment();
      [""].concat(sources).forEach(function (s) {
        var o = document.createElement("option");
        o.value = s;
        o.textContent = s || "All sources";
        of.appendChild(o);
      });
      el.actSource.replaceChildren(of);
      el.actSource.value = act.sourceFilter;
    }
    var scroll = el.actEvents.scrollTop;
    var frag = document.createDocumentFragment();
    var shown = 0;
    events.forEach(function (e) {
      if (!e || typeof e !== "object") return;
      if (act.sourceFilter && str(e.source) !== act.sourceFilter) return;
      if (act.levelFilter && e.level !== act.levelFilter) return;
      shown++;
      var row = document.createElement("div");
      row.className = "event " + (LEVEL_COLORS[e.level] || "lvl-info");
      var t = line("event-time", durationText(serverNow() - num(e.at, 0)) + " ago");
      t.title = new Date(num(e.at, 0)).toLocaleString();
      row.appendChild(t);
      row.appendChild(line("event-source", str(e.source)));
      row.appendChild(hide(line("event-text", str(e.text))));
      frag.appendChild(row);
    });
    if (!shown) frag.appendChild(line("note", events.length ? "No events match." : "No events yet."));
    el.actEvents.replaceChildren(frag);
    el.actEvents.scrollTop = scroll;
  }

  el.actModFilter.addEventListener("input", function () {
    act.moduleQuery = el.actModFilter.value.trim().toLowerCase();
    if (act.data && act.data.activity) renderModules(act.data.activity);
  });
  el.actEnabledOnly.addEventListener("change", function () {
    act.enabledOnly = el.actEnabledOnly.checked;
    if (act.data && act.data.activity) renderModules(act.data.activity);
  });
  el.actSource.addEventListener("change", function () {
    act.sourceFilter = el.actSource.value;
    renderEvents();
  });
  el.actLevel.addEventListener("change", function () {
    act.levelFilter = el.actLevel.value;
    renderEvents();
  });

  // ---------- sudo mode ----------
  // Dangerous actions (console commands) need the password typed in the last 15 minutes. The server answers
  // 403 {"error":"confirm password"}; the page asks for the password, confirms it, and runs the action again.

  /** text: optional explanation for the password prompt (askPassword). */
  function withSudo(action, text) {
    return action().catch(function (err) {
      if (!(err && err.status === 403 && err.body && err.body.error === "confirm password")) throw err;
      return askPassword(text).then(function () { return action(); });
    });
  }

  function askPassword(text) {
    return new Promise(function (resolve, reject) {
      var form = document.createElement("form");
      form.className = "password-form";
      form.noValidate = true;
      form.appendChild(line("window-info", text || (me.owner
        ? "Console commands can control the whole proxy. Type the website password again; it is not asked for again for 15 minutes."
        : "Console commands and proxy changes need your password again; it is not asked for again for 15 minutes.")));
      var label = document.createElement("label");
      label.className = "auth-field";
      var span = document.createElement("span");
      span.className = "field-label";
      span.textContent = "Password";
      var input = document.createElement("input");
      input.type = "password";
      input.className = "mc-input";
      input.autocomplete = "current-password";
      input.maxLength = 1024;
      label.appendChild(span);
      label.appendChild(input);
      form.appendChild(label);
      var ok = document.createElement("button");
      ok.type = "submit";
      ok.className = "mc-button";
      ok.textContent = "Confirm";
      form.appendChild(ok);
      var msg = line("note warn", "");
      form.appendChild(msg);
      var done = false;
      form.addEventListener("submit", function (e) {
        e.preventDefault();
        ok.disabled = true;
        msg.textContent = "Checking...";
        api("/api/v1/auth/confirm", { method: "POST", body: { password: input.value } }).then(function (r) {
          sudoState(r && r.elevatedUntil ? num(r.elevatedUntil, 0) - state.clockSkew : Date.now() + 15 * 60 * 1000);
          done = true;
          closeWindow();
          resolve();
        }, function (err) {
          ok.disabled = false;
          var b = err && err.body;
          msg.textContent = err && err.status === 401 ? "Wrong password." :
            err && err.status === 429 ? "Too many attempts. Try again in " + (b && b.retryAfter ? b.retryAfter : 60) + " s." : (b && b.error) || "Could not confirm.";
          input.select();
        });
      });
      var win = openWindow("Confirm password", form, "password-window");
      input.focus();
      // Closing the window without confirming cancels the action.
      var observer = new MutationObserver(function () {
        if (!win.isConnected) {
          observer.disconnect();
          if (!done) reject({ status: 0, body: { error: "cancelled" } });
        }
      });
      observer.observe(document.body, { childList: true });
    });
  }

  // ---------- dashboard: Console page ----------

  var OUTPUT_COLORS = { error: "#aa0000", success: "#3a8a2a", primary: "#3f6bd8", queue: "#d6a400" };

  /** Text with the safe subset of Discord markdown: **bold** and `code`. Everything else stays text. */
  function rich(text) {
    var frag = document.createDocumentFragment();
    String(text).split(/(\*\*[^*\n]+?\*\*|`[^`\n]+`)/).forEach(function (part) {
      if (!part) return;
      if (part.length > 4 && part.slice(0, 2) === "**" && part.slice(-2) === "**") {
        var b = document.createElement("b");
        b.textContent = part.slice(2, -2);
        frag.appendChild(b);
      } else if (part.length > 2 && part.charAt(0) === "`" && part.charAt(part.length - 1) === "`") {
        var c = document.createElement("code");
        c.textContent = part.slice(1, -1);
        frag.appendChild(c);
      } else {
        frag.appendChild(document.createTextNode(part));
      }
    });
    return frag;
  }

  function renderOutput(o) {
    var box = hide(document.createElement("div"));
    box.className = "out";
    var color = str(o.color);
    var css = OUTPUT_COLORS[color] || (/^#[0-9a-fA-F]{6}$/.test(color) ? color : "");
    if (css) box.style.borderLeftColor = css; // CSSOM, allowed by the CSP
    if (color === "error") box.classList.add("out-error");
    if (str(o.title)) {
      var t = document.createElement("div");
      t.className = "out-title";
      t.appendChild(rich(str(o.title)));
      box.appendChild(t);
    }
    if (str(o.description)) {
      var d = document.createElement("div");
      d.className = "out-desc";
      d.appendChild(rich(str(o.description)));
      box.appendChild(d);
    }
    (Array.isArray(o.fields) ? o.fields : []).forEach(function (fd) {
      if (!fd || typeof fd !== "object") return;
      var row = document.createElement("div");
      row.className = "out-field";
      var n = document.createElement("div");
      n.className = "out-field-name";
      n.appendChild(rich(str(fd.name)));
      var v = document.createElement("div");
      v.className = "out-field-value";
      v.appendChild(rich(str(fd.value)));
      row.appendChild(n);
      row.appendChild(v);
      box.appendChild(row);
    });
    var lines = Array.isArray(o.lines) ? o.lines : [];
    if (lines.length) {
      var pre = document.createElement("div");
      pre.className = "out-lines";
      pre.textContent = lines.map(function (l) { return str(l); }).join("\n");
      box.appendChild(pre);
    }
    return box;
  }

  var CONSOLE_STATUS = { queued: "waiting", sent: "running", done: "done", error: "failed", expired: "not picked up", unknown: "no answer" };

  function renderEntry(e) {
    var item = document.createElement("div");
    item.className = "entry st-" + str(e.status);
    var head = document.createElement("div");
    head.className = "entry-head";
    var cmd = document.createElement("span");
    cmd.className = "entry-cmd" + (e.hidden ? " hidden-cmd" : "");
    cmd.textContent = (e.hidden ? "" : "> ") + str(e.command);
    hide(cmd);
    head.appendChild(cmd);
    var meta = document.createElement("span");
    meta.className = "note";
    meta.textContent = new Date(num(e.at, 0)).toLocaleTimeString();
    head.appendChild(meta);
    var badge = document.createElement("span");
    badge.className = "badge st-" + (e.status === "done" ? "delivered" : e.status === "error" || e.status === "expired" || e.status === "unknown" ? "failed" : "in_progress");
    badge.textContent = CONSOLE_STATUS[e.status] || str(e.status);
    head.appendChild(badge);
    item.appendChild(head);
    var outs = Array.isArray(e.outputs) ? e.outputs : [];
    outs.forEach(function (o) { if (o && typeof o === "object") item.appendChild(renderOutput(o)); });
    if (str(e.error) && !outs.length) item.appendChild(hide(line("out out-error out-lines", str(e.error))));
    return item;
  }

  function loadConsole() {
    window.clearTimeout(dash.cTimer);
    if (dash.page !== "console") return;
    var b = currentBot();
    if (dash.cBot !== dash.botId) {
      dash.cBot = dash.botId;
      dash.cRev = 0;
      dash.entries.clear();
      el.consoleLog.replaceChildren();
      dash.history = [];
      dash.histPos = -1;
    }
    var consoleOk = b && Array.isArray(b.caps) && b.caps.indexOf("console") >= 0;
    el.consoleOffline.hidden = !!(b && b.online && consoleOk) || !!(b && !consoleOk);
    el.consoleOffline.textContent = !b ? "No dashboard bot yet." : offlineText(b) + "Commands can't be sent until it is back.";
    if (b && !consoleOk) {
      el.consoleLog.replaceChildren(capHint("console"));
      dash.cBot = null;
      el.consoleCmd.disabled = true;
      el.consoleSend.disabled = true;
      dash.cTimer = window.setTimeout(loadConsole, 5000);
      return;
    }
    el.consoleCmd.disabled = !(b && b.online && consoleOk);
    el.consoleSend.disabled = el.consoleCmd.disabled;
    if (!b) {
      dash.cTimer = window.setTimeout(loadConsole, 5000);
      return;
    }
    var botId = b.botId;
    api("/api/v1/bots/" + encodeURIComponent(botId) + "/console?after=" + dash.cRev).catch(function (err) {
      if (err && err.status === 403 && err.body && err.body.error === "confirm password") {
        dash.cLocked = true;
        dash.entries.clear();
        dash.cRev = 0;
        el.consoleLog.replaceChildren(unlockBox("The console is only shown after you confirm the website password.", function () {
          el.consoleLog.replaceChildren();
          dash.cLocked = false;
          loadConsole();
        }));
      }
      throw err;
    }).then(function (r) {
      dash.cLocked = false;
      if (botId !== dash.botId) return;
      var atBottom = el.consoleLog.scrollHeight - el.consoleLog.scrollTop - el.consoleLog.clientHeight < 40;
      (r && Array.isArray(r.entries) ? r.entries : []).forEach(function (e) {
        if (!e || typeof e !== "object") return;
        var old = dash.entries.get(e.seq);
        var node = renderEntry(e);
        if (old) old.el.replaceWith(node);
        else el.consoleLog.appendChild(node);
        dash.entries.set(e.seq, { data: e, el: node });
      });
      // Entries that fell out of the server's 200-entry ring go here too.
      var first = r ? num(r.first, 0) : 0;
      dash.entries.forEach(function (v, seq) {
        if (seq < first) {
          v.el.remove();
          dash.entries.delete(seq);
        }
      });
      if (r && typeof r.rev === "number") dash.cRev = r.rev;
      if (atBottom) el.consoleLog.scrollTop = el.consoleLog.scrollHeight;
      el.consoleState.textContent = r && r.online ? "" : "offline";
    }, function () {}).then(function () {
      var pending = false;
      dash.entries.forEach(function (v) { if (v.data.status === "queued" || v.data.status === "sent") pending = true; });
      if (dash.page === "console" && !dash.cLocked) dash.cTimer = window.setTimeout(loadConsole, pending ? 1000 : 5000);
    });
  }

  el.consoleForm.addEventListener("submit", function (e) {
    e.preventDefault();
    var b = currentBot();
    var command = el.consoleCmd.value.trim();
    if (!b || !command) return;
    var word = command.split(/\s+/)[0].toLowerCase();
    if (CONSOLE_CONFIRM.indexOf(word) >= 0 &&
      !window.confirm("Run '" + command.slice(0, 80) + "' on " + botName(b) + "?\n\nThis command can disconnect the bot, restart the proxy or change how it logs in.")) {
      return;
    }
    el.consoleSend.disabled = true;
    withSudo(function () {
      return api("/api/v1/bots/" + encodeURIComponent(b.botId) + "/console", { method: "POST", body: { command: command } });
    }).then(function () {
      if (dash.history[dash.history.length - 1] !== command) dash.history.push(command);
      if (dash.history.length > 100) dash.history.shift();
      dash.histPos = -1;
      el.consoleCmd.value = "";
      el.consoleLog.scrollTop = el.consoleLog.scrollHeight;
      loadConsole();
    }, function (err) {
      var body = err && err.body;
      if (body && body.error === "cancelled") return;
      toast(body && body.error === "bot offline" ? "The bot is offline." : "Not sent: " + ((body && body.error) || "HTTP " + (err && err.status)));
    }).then(function () {
      el.consoleSend.disabled = false;
      el.consoleCmd.focus();
    });
  });

  el.consoleCmd.addEventListener("keydown", function (e) {
    if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
    if (!dash.history.length) return;
    e.preventDefault();
    if (e.key === "ArrowUp") {
      dash.histPos = dash.histPos < 0 ? dash.history.length - 1 : Math.max(0, dash.histPos - 1);
    } else {
      dash.histPos = dash.histPos < 0 ? -1 : dash.histPos + 1;
      if (dash.histPos >= dash.history.length) dash.histPos = -1;
    }
    el.consoleCmd.value = dash.histPos < 0 ? "" : dash.history[dash.histPos];
  });

  // ---------- controls ----------

  el.search.addEventListener("input", function () { queueRender(SEARCH_DEBOUNCE_MS); });
  el.sort.addEventListener("change", function () {
    if (isCreativeTab(state.tab)) state.sortTab = el.sort.value;
    else state.sortAll = el.sort.value;
    queueRender(0);
  });
  el.search.addEventListener("keydown", function (e) {
    if (e.key === "Escape") {
      el.search.value = "";
      queueRender(0);
    }
  });
  el.select.addEventListener("change", function () {
    selectStash(el.select.value || null);
  });

  // Keep "last seen 2m ago" fresh between polls.
  window.setInterval(function () {
    updateHeader();
    if (link.open) renderLink();
  }, 5000);
  window.setInterval(poll, POLL_MS);
  // The owner may change this user's permissions (or disable the account) at any time.
  window.setInterval(function () { if (!auth.blocked) refreshSudo(); }, 30000);
  el.sort.value = state.sortAll;
  checkAuth(); // renders the tabs once logged in (their icons need the session)
})();

// Runs before any stylesheet (loaded without defer), so the first frame already has the right theme.
// The theme is a per-browser preference ("sm-theme"), never sent to the server or the bot.
(function () {
  var KEY = "sm-theme";
  // Each theme's --bar colour (from themes.css), for <meta name="theme-color">: the CSS isn't loaded yet here.
  var BAR = { classic: "#1f1f1f", modern: "#ffffff", light: "#fafaf9", dark: "#0e1116", midnight: "#000000", contrast: "#000000" };
  var root = document.documentElement;
  var media = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  var listeners = [];
  var unsaved = null; // the choice, when storage is blocked (this page only)

  function known(v) { return Object.prototype.hasOwnProperty.call(BAR, v); }

  /** The current choice: a theme name, or "system" (also when nothing, or something unknown, is stored). */
  function saved() {
    if (unsaved !== null) return unsaved;
    try {
      var v = window.localStorage.getItem(KEY);
      return known(v) ? v : "system";
    } catch (e) {
      return "system"; // storage blocked
    }
  }

  /** The theme a choice shows now: "system" follows the device (dark: Dark, otherwise Classic). */
  function resolve(pref) {
    if (known(pref)) return pref;
    return media && media.matches ? "dark" : "classic";
  }

  function apply() {
    var pref = saved();
    var theme = resolve(pref);
    root.setAttribute("data-theme", theme);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", BAR[theme]);
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](pref, theme); } catch (e) { /* one broken listener must not stop the others */ }
    }
  }

  /** Saves a choice ("system" or a theme name) for this browser and shows it at once. */
  function set(pref) {
    var v = known(pref) ? pref : "system";
    try {
      window.localStorage.setItem(KEY, v);
      unsaved = null;
    } catch (e) {
      unsaved = v; // storage blocked: shown until the page is reloaded
    }
    apply();
  }

  apply();

  // "Match system" follows the device live.
  if (media) {
    var onMedia = function () { if (saved() === "system") apply(); };
    if (media.addEventListener) media.addEventListener("change", onMedia);
    else if (media.addListener) media.addListener(onMedia);
  }
  // Other tabs of this dashboard follow a change made in one of them.
  window.addEventListener("storage", function (e) {
    if (e.key === KEY || e.key === null) apply();
  });

  window.SM_THEMES = {
    key: KEY,
    bar: BAR,
    saved: saved,
    resolve: resolve,
    set: set,
    onChange: function (fn) { listeners.push(fn); }
  };
})();

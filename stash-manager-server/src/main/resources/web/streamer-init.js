// Runs before the page is drawn (loaded without defer), so streamer mode hides everything from the first frame.
(function () {
  try {
    if (window.localStorage.getItem("stashManager.streamer") === "1") document.documentElement.classList.add("streamer");
  } catch (e) {
    // storage blocked: default off
  }
})();

const reloadKey = "armada:stale-chunk-reload";
const now = Date.now();

try {
  const lastReloadAt = Number(window.sessionStorage.getItem(reloadKey) || "0");
  if (now - lastReloadAt > 3000) {
    window.sessionStorage.setItem(reloadKey, String(now));
    window.location.reload();
  }
} catch {
  window.location.reload();
}

export {};

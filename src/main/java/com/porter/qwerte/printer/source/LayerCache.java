package com.porter.qwerte.printer.source;

/**
 * The last render-layer filter that was read successfully. A read that fails (or is held back after a failure) answers with
 * it, so a flaky read does not revive blocks outside the layer or make the park pass thrash. Only before any read has
 * succeeded is there no filter. Pure.
 */
public final class LayerCache {
    private LayerFilter last;

    public void ok(LayerFilter f) {
        last = f;
    }

    public LayerFilter fallback() {
        return last == null ? LayerFilter.ALL : last;
    }
}

package com.porter.qwerte.utils;

/**
 * The container-delay rule, kept free of Minecraft types so it can be tested (and mirrored by the proxy plugin).
 * <p>
 * Two points are paced, both in ticks of the module's own tick counter:
 * <ul>
 * <li>an open click is held until at least {@code containerDelay} ticks have passed since the last container the bot closed;</li>
 * <li>the first click in a freshly opened window waits {@code containerDelay} ticks more than the usual click delay.</li>
 * </ul>
 * A delay of 0 changes nothing.
 */
public final class ContainerPacing {
    private ContainerPacing() { }

    /** True while the open click has to wait: fewer than {@code containerDelay} ticks since the last close. */
    public static boolean openHeld(int now, int lastClose, int containerDelay) {
        return containerDelay > 0 && now - lastClose < containerDelay;
    }

    /** Ticks to wait after a window opened, before the first click. */
    public static int firstClickWait(int clickDelay, int containerDelay) {
        return Math.max(0, clickDelay) + Math.max(0, containerDelay);
    }
}

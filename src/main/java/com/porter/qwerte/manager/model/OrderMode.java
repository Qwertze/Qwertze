package com.porter.qwerte.manager.model;

/**
 * Who carries out an order: the bots of a Stash Manager server, or this client itself. Pure rules for the choice and for when
 * this client must refuse.
 */
public enum OrderMode {
    SERVER_BOTS("Send to server bots"), THIS_CLIENT("Fulfil here (this client)");

    private final String label;

    OrderMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * Without a server connection this client is the only one there is. With one, the default is the server's bots, and the
     * user's earlier choice ({@code preferred}, may be null) is kept.
     */
    public static OrderMode choose(boolean connected, OrderMode preferred) {
        if (!connected) return THIS_CLIENT;
        return preferred == null ? SERVER_BOTS : preferred;
    }

    /** Both choices are offered only when connected. */
    public static boolean offersChoice(boolean connected) {
        return connected;
    }

    /**
     * Why this client cannot start an order now, or null. {@code stashManagerOn} is the bot module: it drives the same sorter
     * and may be handed server orders at any time, so the two never run together.
     */
    public static String localRefusal(boolean inWorld, boolean stashManagerOn, boolean localRunning, boolean sorterHoldsOrder,
                                      boolean hasStash, boolean hasDelivery) {
        return localRefusal(inWorld, stashManagerOn, localRunning, sorterHoldsOrder, hasStash, hasDelivery, null);
    }

    /** Ids of orders this client made start with this; anything else saved in the stash belongs to a server order. */
    public static final String LOCAL_PREFIX = "L-";

    public static boolean isLocalId(String id) {
        return id != null && id.startsWith(LOCAL_PREFIX);
    }

    /**
     * As above, with {@code savedOrderId}: the id of the order the stash has saved (a plan kept for a resume), or null. A saved
     * server order (stash-manager was turned off with it in hand) is kept for stash-manager to finish, so this client does not
     * start while it is there; a saved order of this client is simply replaced.
     */
    public static String localRefusal(boolean inWorld, boolean stashManagerOn, boolean localRunning, boolean sorterHoldsOrder,
                                      boolean hasStash, boolean hasDelivery, String savedOrderId) {
        if (!inWorld) return "Join the world with your stash first.";
        if (localRunning) return "An order is already running on this client.";
        if (stashManagerOn) return "stash-manager is on and may be taking server orders with the same sorter. Turn it off to fulfil orders here.";
        if (sorterHoldsOrder) return "The sorter is busy with another order.";
        if (!hasStash) return "No stash is loaded. Set stash-name in stash-sorter and scan the stash first.";
        if (!hasDelivery) return "No delivery container is set. Use Set delivery below, then right-click a chest in the stash.";
        if (savedOrderId != null && !isLocalId(savedOrderId)) {
            return "A server order is parked in this stash. Turn stash-manager on again to finish it, or cancel it on the website.";
        }
        return null;
    }

    /** What the sorter's drive blocker ("wrong dimension", "too far from the stash") means for the user. */
    public static String driveBlockerText(String blocker) {
        return switch (blocker) {
            case "wrong dimension" -> "You are in another dimension than the stash.";
            case "too far from the stash" -> "You are too far from the stash. Go to it first.";
            default -> "The sorter can't run here: " + blocker + ".";
        };
    }
}

package com.porter.qwerte.manager.local;

/**
 * One container that holds an item (or filled shulker kind) the player looked up. {@code distance} is in blocks, or -1
 * when it is in another dimension or the player's position is unknown. Local mode only; never shown while streamer
 * display is on.
 */
public record Where(ContainerKey key, String block, long count, int distance) {}

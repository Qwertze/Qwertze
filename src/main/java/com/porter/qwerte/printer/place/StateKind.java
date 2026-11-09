package com.porter.qwerte.printer.place;

/**
 * How a block state is placed. Pure. The relation to the player's look is documented on {@link LookWindow}; the clicks
 * per kind are built by {@link PlacementMapper}.
 */
public enum StateKind {
    /** No placement-relevant property: any clicked face. */
    FULL,
    /** axis = axis of the clicked face (logs, pillars, chains). */
    AXIS,
    /** facing = H.opposite (furnaces, pumpkins, glazed terracotta, chiseled bookshelf, ender chest, ...). */
    HFACING,
    /** facing = H (campfire, decorated pot). */
    HFACING_LOOK,
    /** facing = H.clockwise (anvils). */
    HFACING_CW,
    /** facing = L.opposite (piston, dispenser, dropper, barrel). */
    FACING6_LOOK,
    /** facing = L (observer). */
    FACING6_LOOK_SAME,
    /** facing = clicked face (shulker box, end rod, lightning rod, amethyst cluster). */
    FACING6_FACE,
    /** facing = clicked face opposite, down for a vertical click. */
    HOPPER,
    /** facing = H, half from the face or the click height. */
    STAIRS,
    /** type bottom / top from the face or the click height; double = a second placement click. */
    SLAB,
    /** side click: facing = face, half = click height; floor/ceiling click: facing = H.opposite, half from the face. */
    TRAPDOOR,
    /** facing = H, hinge from neighbours and the click offset; the upper half is a PART. */
    DOOR,
    /** facing = H (head side); the head half is a PART. */
    BED,
    /** facing = H; open by a use click. */
    FENCE_GATE,
    /** button, lever, grindstone: face FLOOR/CEILING (facing = H) or WALL (facing = clicked face). */
    ATTACH_FACE,
    /** a wall-mounted block (wall torch, wall sign, wall banner, wall skull, ladder): facing = clicked face. */
    WALL_OR_STANDING,
    /** standing sign, banner, skull or head: rotation from the yaw. */
    ROTATION16,
    /** lantern: hanging from the face clicked (bottom face of the block above = hanging). */
    HANGING,
    /** facing = H.opposite; delay by use clicks. */
    REPEATER,
    /** facing = H.opposite; subtract mode by a use click. */
    COMPARATOR,
    /** note by use clicks. */
    NOTE,
    /** daylight detector; inverted by a use click. */
    DAYLIGHT,
    /** candles, sea pickles, turtle eggs, snow layers: count by repeated placement clicks. */
    COUNT,
    /** bell: floor/ceiling (facing = H) or wall (facing from the clicked face). */
    BELL,
    /** single chest only; sneaking so it does not merge or open its neighbour. */
    CHEST,
    /** carpets, plates, rails, redstone, torches, plants: default state, needs a block below. */
    SUPPORTED_FLOOR,
    /** the second cell of a two-part block (door top, bed head, double plant top); placed with its partner. */
    PART,
    /** not placed by the printer; the spec carries the reason. */
    SKIP
}

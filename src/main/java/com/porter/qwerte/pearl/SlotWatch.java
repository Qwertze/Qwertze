package com.porter.qwerte.pearl;

/**
 * After we put a pearl in the hand, the next ticks tell whether it stayed: the selected slot is what we set, and the
 * hand holds the pearl. If not, who did it: another mod (the held slot changed without us), or the server (it sent the
 * inventory again, the menu state id moved).
 */
public final class SlotWatch {
    public enum Verdict { OK, HELD_SLOT_CHANGED, REVERTED_BY_SERVER, SLOT_CHANGED }

    private SlotWatch() {}

    public static Verdict judge(int expectedSelected, int nowSelected, boolean handHoldsPearl, boolean serverAnswered) {
        if (nowSelected != expectedSelected) return Verdict.HELD_SLOT_CHANGED;
        if (handHoldsPearl) return Verdict.OK;
        return serverAnswered ? Verdict.REVERTED_BY_SERVER : Verdict.SLOT_CHANGED;
    }

    /** The words for the warning, one slot number counted from 1 as on the hotbar. */
    public static String describe(Verdict verdict, int expectedSelected, int nowSelected) {
        return switch (verdict) {
            case OK -> "the pearl is in your hand";
            case HELD_SLOT_CHANGED -> "another mod changed your held slot (slot " + (expectedSelected + 1) + " -> " + (nowSelected + 1) + ")";
            case REVERTED_BY_SERVER -> "the server sent your inventory again and the pearl was moved back";
            case SLOT_CHANGED -> "the slot's contents changed without a server answer (another mod?)";
        };
    }
}

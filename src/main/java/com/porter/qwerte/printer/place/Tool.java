package com.porter.qwerte.printer.place;

/**
 * The tools (and the plant item) that finish a block after it is placed. Pure: the labels are what the Printer says in
 * its warnings and report.
 */
public enum Tool {
    AXE("axe", "Stripped", "Unstripped"),
    SHOVEL("shovel", "Flattened or doused", "Not flattened or doused"),
    HOE("hoe", "Tilled", "Not tilled"),
    HONEYCOMB("honeycomb", "Waxed", "Not waxed"),
    SHEARS("shears", "Carved", "Not carved"),
    FLINT_AND_STEEL("flint and steel", "Lit", "Not lit"),
    /** The plant item a flower pot is filled with (which plant depends on the pot). */
    PLANT("plant for the flower pots", "Potted", "Pots without plant");

    private final String label, doneLabel, leftTitle;

    Tool(String label, String doneLabel, String leftTitle) {
        this.label = label;
        this.doneLabel = doneLabel;
        this.leftTitle = leftTitle;
    }

    /** How the tool is called in a sentence. */
    public String label() {
        return label;
    }

    /** The word in "Tilled 3 block(s)". */
    public String doneLabel() {
        return doneLabel;
    }

    /** The title of the list of cells that were left undone. */
    public String leftTitle() {
        return leftTitle;
    }

    /**
     * The block's own right-click code (not the item's) does the job, so it is skipped while sneaking: the flower pot takes
     * the plant and the pumpkin takes the shears only when the player is not sneaking.
     */
    public boolean needsNoSneak() {
        return this == PLANT || this == SHEARS;
    }
}

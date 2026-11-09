package com.porter.qwerte.printer.stash;

import com.porter.qwerte.manager.model.Text;
import com.porter.qwerte.printer.schematic.LitematicReader;

/**
 * Text from the Stash Manager server (stash and server names, order ids, bot notes, error texts) made safe for the chat:
 * no formatting codes or control characters, no percent signs (chat messages go through String.format) and no parentheses
 * (Meteor reads "(highlight)", "(bold)" and the like as styling). Pure.
 */
public final class ChatText {
    private ChatText() {}

    public static String safe(String s, int max) {
        return LitematicReader.clean(Text.sanitize(s, max), max);
    }
}

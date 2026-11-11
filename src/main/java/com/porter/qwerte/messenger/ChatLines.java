package com.porter.qwerte.messenger;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;
import java.util.function.Predicate;

/**
 * Finds a whisper in a chat message of the game. A vanilla whisper is recognised by its translation key (the same in
 * every language); anything else by the text patterns. Messages of normal players ({@code chat.type.*}) are never
 * taken for whispers, so one player cannot fake a line by typing it.
 */
public final class ChatLines {
    private ChatLines() {
    }

    /** Whether a chat line is a console line (system message, death, join, client notice) and not player chat. See {@link LineKind}. */
    public static boolean isConsole(Component message, GuiMessageTag tag, List<String> prefixes) {
        if (message == null) return false;
        String key = message.getContents() instanceof TranslatableContents tc ? tc.getKey() : null;
        return LineKind.isConsole(tag == null ? null : tag.logTag(), key, ComponentText.plain(message), prefixes);
    }

    /** The type of a chat line for the Console routing. See {@link LineKind#type}. */
    public static LineKind.Type type(Component message, GuiMessageTag tag, List<String> prefixes) {
        if (message == null) return LineKind.Type.PLAYER;
        String key = message.getContents() instanceof TranslatableContents tc ? tc.getKey() : null;
        return LineKind.type(tag == null ? null : tag.logTag(), key, ComponentText.plain(message), prefixes);
    }

    /**
     * @param isOnline whether a player of that name is on the server (used to pick the name out of a decorated display
     *                 name like "[Mod] Steve")
     */
    public static DmParser.Parsed parse(Component message, DmParser parser, boolean vanilla, Predicate<String> isOnline) {
        if (message == null) return null;
        if (message.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            if (key.startsWith("chat.type.")) return null;
            Object[] args = tc.getArgs();
            if (vanilla && args.length >= 2) {
                String name = senderName(args[0], isOnline);
                if (name != null) {
                    DmParser.Parsed p = DmParser.parseVanilla(key, name, text(args[1]));
                    if (p != null) return p;
                }
            }
        }
        String line = ComponentText.plain(message);
        return parser.parse(line);
    }

    /**
     * The player's real name from a (possibly decorated) display name. Only the insertion of the root component is the
     * profile name the game puts there; an insertion on a child component is part of the (spoofable) text and is not
     * trusted by itself: it can only name a player who is also the one online player in the shown text, which the
     * words rule below finds anyway.
     */
    static String senderName(Object arg, Predicate<String> isOnline) {
        if (arg instanceof Component c) {
            return resolveName(c.getStyle().getInsertion(), ComponentText.plain(c), isOnline);
        }
        return resolveName(null, String.valueOf(arg), isOnline);
    }

    /**
     * Pure part of {@link #senderName}.
     * <ol>
     *   <li>a valid insertion of the root component;</li>
     *   <li>the word of the shown text that is an online player, if there is exactly one;</li>
     *   <li>else the last word that is a valid name (a nickname naming someone else, like "Notch's pal Steve", then
     *       cannot win just because that someone is online and the real sender is not).</li>
     * </ol>
     */
    static String resolveName(String rootInsertion, String display, Predicate<String> isOnline) {
        if (rootInsertion != null && ChatText.isValidName(rootInsertion)) return rootInsertion;
        String shown = ChatText.clean(display, 100);
        String last = null;
        String onlineToken = null;
        int onlineCount = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String token : shown.split("[^A-Za-z0-9_]+")) {
            if (!ChatText.isValidName(token)) continue;
            last = token;
            if (isOnline != null && seen.add(token.toLowerCase(java.util.Locale.ROOT)) && isOnline.test(token)) {
                onlineToken = token;
                onlineCount++;
            }
        }
        return onlineCount == 1 ? onlineToken : last;
    }


    private static String text(Object arg) {
        if (arg instanceof Component c) return ComponentText.plain(c);
        return String.valueOf(arg);
    }
}

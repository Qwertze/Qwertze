package com.porter.qwerte.messenger;

/**
 * The chat warning for a sound that can not be used. The file name comes from a setting, so it is shown only if it is a
 * valid sound file name, at most {@link #MAX_NAME} characters, and without the round brackets that Meteor's chat
 * formatting reads as colour markup ("(highlight)", "(bold)", ...). Pure.
 */
public final class SoundWarning {
    public static final int MAX_NAME = 40;
    public static final String INVALID = "(invalid name)";

    private SoundWarning() {
    }

    /** The name as it may be shown. */
    public static String shownName(String name) {
        if (!SoundFiles.validName(name)) return INVALID;
        String n = name.replace('(', '[').replace(')', ']');
        if (n.length() <= MAX_NAME) return n;
        int cut = MAX_NAME - 3;
        if (Character.isHighSurrogate(n.charAt(cut - 1))) cut--;
        return n.substring(0, cut) + "...";
    }

    public static String message(String name, String why) {
        return "Can not play the sound \"" + shownName(name) + "\": " + why + ". Using the built-in sound instead.";
    }
}

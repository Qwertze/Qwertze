package com.porter.qwerte.messenger;

/** A custom sound that can not be used; the message says why, in a few plain words (it is shown in chat). */
public final class SoundException extends Exception {
    private static final long serialVersionUID = 1L;

    public SoundException(String message) {
        super(message);
    }
}

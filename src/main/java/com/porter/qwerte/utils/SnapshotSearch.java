package com.porter.qwerte.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.function.Predicate;

/**
 * Searches a collection that another thread may be changing, without ever throwing a
 * {@link ConcurrentModificationException}. Used for the tab list, which the game changes on the render
 * thread while a profile lookup on a worker thread walks it.
 */
public final class SnapshotSearch {
    /** How often a copy is tried again when the collection changes under it. */
    public static final int ATTEMPTS = 8;

    private SnapshotSearch() {
    }

    /** The first element matching the test, or null when none does (or the collection kept changing). */
    public static <T> T find(Collection<T> source, Predicate<T> test) {
        List<T> copy = copy(source);
        if (copy == null) return null;
        for (T element : copy) {
            try {
                if (test.test(element)) return element;
            } catch (RuntimeException e) {
                // An element published half-way by the other thread (e.g. a null profile): skip it.
            }
        }
        return null;
    }

    /** A copy of the collection, or null when it changed during every one of the {@link #ATTEMPTS} tries. */
    public static <T> List<T> copy(Collection<T> source) {
        for (int i = 0; i < ATTEMPTS; i++) {
            try {
                // An iterator checks for changes as it goes; HashMap.values().toArray() (what new ArrayList(source)
                // uses) does not, and can fail with an ArrayIndexOutOfBoundsException when the map grows meanwhile.
                List<T> copy = new ArrayList<>();
                for (T element : source) copy.add(element);
                return copy;
            } catch (RuntimeException e) {
                // ConcurrentModificationException in the usual case; a HashMap read racing a resize can also
                // throw IndexOutOfBounds, NullPointer or ClassCast exceptions. None of them may escape.
                Thread.onSpinWait();
            }
        }
        return null;
    }
}

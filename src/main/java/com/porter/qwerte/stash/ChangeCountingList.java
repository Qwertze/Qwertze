package com.porter.qwerte.stash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A list that counts its structural changes: every add, remove, replace, sort and clear bumps {@link #changes()}, whatever the caller does, so a
 * cache keyed on the counter can't go stale when one element is removed and another added in the same pass (the size would not change).
 */
public final class ChangeCountingList<E> extends ArrayList<E> {
    private volatile int changes;

    /** Goes up on every structural change. */
    public int changes() {
        return changes;
    }

    private void bump() {
        changes++;
    }

    @Override
    public boolean add(E e) {
        bump();
        return super.add(e);
    }

    @Override
    public void add(int index, E element) {
        bump();
        super.add(index, element);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        bump();
        return super.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        bump();
        return super.addAll(index, c);
    }

    @Override
    public E remove(int index) {
        bump();
        return super.remove(index);
    }

    @Override
    public boolean remove(Object o) {
        bump();
        return super.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        bump();
        return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        bump();
        return super.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        bump();
        return super.removeIf(filter);
    }

    @Override
    public void clear() {
        bump();
        super.clear();
    }

    @Override
    public E set(int index, E element) {
        bump();
        return super.set(index, element);
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        bump();
        super.replaceAll(operator);
    }

    @Override
    public void sort(java.util.Comparator<? super E> c) {
        bump();
        super.sort(c);
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        bump();
        super.removeRange(fromIndex, toIndex);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        // A view that could change the list behind this one's back is not offered
        return java.util.Collections.unmodifiableList(super.subList(fromIndex, toIndex));
    }
}

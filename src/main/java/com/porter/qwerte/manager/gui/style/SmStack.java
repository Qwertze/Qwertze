package com.porter.qwerte.manager.gui.style;

import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;

/** A vertical list with a chosen spacing (Meteor pixels). */
public class SmStack extends WVerticalList {
    public SmStack(double spacing) {
        this.spacing = spacing;
    }
}

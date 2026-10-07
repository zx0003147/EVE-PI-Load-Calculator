package com.vepi.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;

/** Vertical stack that always stretches each visible child to the available width. */
final class FullWidthStackLayout implements LayoutManager2 {
    @Override public void addLayoutComponent(Component comp, Object constraints) { }
    @Override public void addLayoutComponent(String name, Component comp) { }
    @Override public void removeLayoutComponent(Component comp) { }
    @Override public void invalidateLayout(Container target) { }
    @Override public float getLayoutAlignmentX(Container target) { return 0f; }
    @Override public float getLayoutAlignmentY(Container target) { return 0f; }
    @Override public Dimension maximumLayoutSize(Container target) {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override public Dimension preferredLayoutSize(Container parent) { return measure(parent, false); }
    @Override public Dimension minimumLayoutSize(Container parent) { return measure(parent, true); }

    private Dimension measure(Container parent, boolean minimum) {
        Insets insets = parent.getInsets();
        int width = 0;
        long height = insets.top + insets.bottom;
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            Dimension size = minimum ? child.getMinimumSize() : child.getPreferredSize();
            width = Math.max(width, size.width);
            height += size.height;
        }
        return new Dimension(width + insets.left + insets.right,
                (int) Math.min(Integer.MAX_VALUE, height));
    }

    @Override public void layoutContainer(Container parent) {
        Insets insets = parent.getInsets();
        int y = insets.top;
        int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            int height = child.getPreferredSize().height;
            child.setBounds(insets.left, y, width, height);
            y += height;
        }
    }
}

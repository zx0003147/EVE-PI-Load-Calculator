package com.vepi.ui;

import javax.swing.JComboBox;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/** Routes wheel input from ordinary descendants to one page-level scroll pane. */
final class MouseWheelForwarder {
    private MouseWheelForwarder() {}

    static void install(Component root, JScrollPane page) {
        attach(root, page);
    }

    private static void attach(Component component, JScrollPane page) {
        if (component == page || Boolean.TRUE.equals(component instanceof javax.swing.JComponent jc
                ? jc.getClientProperty(MouseWheelForwarder.class) : null)) return;
        if (component instanceof javax.swing.JComponent jc) {
            jc.putClientProperty(MouseWheelForwarder.class, Boolean.TRUE);
        }
        if (component instanceof JScrollPane nested) {
            nested.setWheelScrollingEnabled(false);
        }
        MouseWheelListener listener = event -> forward(component, page, event);
        component.addMouseWheelListener(listener);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) attach(child, page);
            container.addContainerListener(new ContainerAdapter() {
                @Override public void componentAdded(ContainerEvent e) { attach(e.getChild(), page); }
            });
        }
    }

    private static void forward(Component source, JScrollPane page, MouseWheelEvent event) {
        if (source instanceof JComboBox<?> combo && combo.isPopupVisible()) return;
        JScrollBar bar = page.getVerticalScrollBar();
        if (!bar.isVisible()) return;
        int increment = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? bar.getBlockIncrement(event.getWheelRotation())
                    * Integer.signum(event.getWheelRotation())
                : bar.getUnitIncrement(event.getWheelRotation()) * event.getUnitsToScroll();
        bar.setValue(bar.getValue() + increment);
        event.consume();
    }
}

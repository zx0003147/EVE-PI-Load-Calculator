package com.vepi.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.event.MouseWheelEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MouseWheelForwarderTest {
    @Test
    void textAreaWheelMovesOuterPageFromTheFirstNotch() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel page = new JPanel(null);
            page.setPreferredSize(new Dimension(300, 1000));
            JTextArea area = new JTextArea("line\n".repeat(100));
            JScrollPane nested = new JScrollPane(area);
            nested.setBounds(10, 10, 250, 100);
            page.add(nested);
            JScrollPane outer = new JScrollPane(page);
            outer.setSize(300, 200);
            outer.doLayout();
            MouseWheelForwarder.install(page, outer);

            int innerBefore = nested.getVerticalScrollBar().getValue();
            area.dispatchEvent(new MouseWheelEvent(area, MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 5, 5, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));

            assertTrue(outer.getVerticalScrollBar().getValue() > 0);
            assertEquals(innerBefore, nested.getVerticalScrollBar().getValue());
        });
    }
}

package com.vepi.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.JPanel;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UiComponentsTest {
    @Test
    void primaryTextContrastsInNormalAndDisabledStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = new JButton("Parse");
            UiComponents.primaryButton(button);
            assertNotEquals(button.getBackground(), button.getForeground());
            button.setEnabled(false);
            assertNotEquals(button.getBackground(), button.getForeground());
        });
    }

    @Test
    void primaryStyleImmediatelyReflectsPreexistingDisabledState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = new JButton("Calculate Allocation");
            button.setEnabled(false);          // AllocationFrame's construction order
            UiComponents.primaryButton(button);
            assertEquals(UiConstants.PRIMARY_DISABLED, button.getBackground());
            assertEquals(UiConstants.BUTTON_TEXT_DISABLED, button.getForeground());

            button.setEnabled(true);
            assertEquals(UiConstants.PRIMARY, button.getBackground());
            button.getModel().setRollover(true);
            assertEquals(UiConstants.PRIMARY_DARK, button.getBackground());
            button.getModel().setArmed(true);
            button.getModel().setPressed(true);
            assertEquals(UiConstants.PRIMARY_PRESSED, button.getBackground());
        });
    }

    @Test
    void fullWidthStackEliminatesCenteredBlankColumn() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel column = new JPanel(new FullWidthStackLayout());
            JPanel card = new JPanel();
            card.setPreferredSize(new Dimension(180, 80));
            column.add(card);
            column.setSize(700, 200);
            column.doLayout();
            assertEquals(700, card.getWidth());
            assertEquals(0, card.getX());
        });
    }
}

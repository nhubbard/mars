package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import javax.swing.table.*;
import javax.swing.*;
import java.awt.*;

/*
 * Use this to render Monospaced and right-aligned data in JTables.
 * I am using it to render integer addresses and values that are stored as
 * Strings containing either the decimal or hexidecimal version
 * of the integer value.
 */
class MonoRightCellRenderer extends DefaultTableCellRenderer {
    public static final Font MONOSPACED_PLAIN_12POINT = new Font("Monospaced", Font.PLAIN, 12);

    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus, int row, int column) {
        JLabel cell = (JLabel) super.getTableCellRendererComponent(table, value,
                isSelected, hasFocus, row, column);
        cell.setFont(MONOSPACED_PLAIN_12POINT);
        cell.setHorizontalAlignment(SwingConstants.RIGHT);
        return cell;
    }
}
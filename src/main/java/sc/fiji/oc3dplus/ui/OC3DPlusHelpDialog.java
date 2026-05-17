package sc.fiji.oc3dplus.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Focused help dialog for the interactive 3D Objects Counter+ controls.
 */
public final class OC3DPlusHelpDialog {

    private static final Color BG = new Color(245, 245, 245);
    private static final Color HEADER = new Color(55, 71, 79);
    private static final Color SUBHEADER = new Color(78, 93, 101);
    private static final Color TEXT = new Color(33, 33, 33);
    private static final Color INFO_BG = new Color(232, 245, 253);
    private static final Color INFO_FG = new Color(15, 87, 140);
    private static final Color INFO_BORDER = new Color(71, 145, 196);
    private static final Dimension HELP_BUTTON_SIZE = new Dimension(22, 22);
    private static final int TEXT_WIDTH = 650;

    private OC3DPlusHelpDialog() {
    }

    public static JButton createHelpButton(String tooltip) {
        JButton button = new JButton("?");
        String safeTooltip = tooltip == null || tooltip.trim().isEmpty()
                ? "About these controls." : tooltip;
        button.setToolTipText(safeTooltip);
        button.getAccessibleContext().setAccessibleName(safeTooltip);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setBackground(INFO_BG);
        button.setForeground(INFO_FG);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(INFO_BORDER));
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setMinimumSize(HELP_BUTTON_SIZE);
        button.setPreferredSize(HELP_BUTTON_SIZE);
        button.setMaximumSize(HELP_BUTTON_SIZE);
        return button;
    }

    public static void show(Component owner) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Window window = owner instanceof Window
                ? (Window) owner
                : owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        final JDialog dialog = window == null
                ? new JDialog((Window) null, "About 3D Objects Counter+ Controls")
                : new JDialog(window, "About 3D Objects Counter+ Controls");
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setModal(false);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(createScrollPane(buildContentPanel()), BorderLayout.CENTER);
        dialog.getContentPane().add(createFooter(dialog), BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    static JPanel buildContentPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(12, 12, 8, 12));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(titleLabel("3D Objects Counter+ Controls"));
        panel.add(Box.createVerticalStrut(5));
        panel.add(paragraph("Use this dialog to choose the threshold, filter objects, "
                + "and decide which maps and result tables are created.", 12f, TEXT));
        panel.add(Box.createVerticalStrut(8));

        addListSection(panel, "Image and threshold", new String[] {
                "Image shows the stack that will be counted.",
                "Threshold sets the cutoff. Voxels at or above this value are highlighted on the image and can become objects.",
                "Slice changes the displayed z-slice so you can check the threshold through the stack.",
                "Changing Threshold or Slice updates the display only. Preview or OK runs the object counting."
        });

        addListSection(panel, "Filters", new String[] {
                "Size (Voxels) removes objects smaller than Min or larger than Max.",
                "Volume appears only when the image has calibrated spatial units, and uses those units cubed.",
                "Morphology rows filter by shape, calibrated volume, surface, and intensity measurements. Default ranges do not remove objects.",
                "Infinity means there is no upper limit.",
                "Exclude objects on edges removes objects touching the image border."
        });

        addListSection(panel, "Filter meanings", new String[] {
                "Size (Voxels): connected voxel count after thresholding; use it to remove tiny specks or very large merged objects.",
                "Sphericity: how close the object is to a sphere; higher values are rounder.",
                "Compactness: how tightly the object volume fits inside its surface; lower values are more irregular or spread out.",
                "Elongation: how stretched the object is; 1 is least elongated, larger values are more elongated.",
                "Volume: calibrated object volume in unit cubed; shown only when the image has real spatial units.",
                "Surface area: object boundary area; higher values usually mean larger or more irregular objects.",
                "Mean intensity: average voxel intensity inside the object, measured from the selected image or redirect image.",
                "Max intensity: brightest voxel inside the object, measured from the selected image or redirect image.",
                "Max Feret diameter: longest 3D caliper distance across the object.",
                "Exclude objects on edges: removes objects touching any border of the stack."
        });

        addListSection(panel, "Maps to show", new String[] {
                "Objects creates a labelled object map with object numbers at the centroids.",
                "Surfaces creates a map of object surface voxels with object numbers at the centroids.",
                "Centroids marks each object's geometric center and object number.",
                "Centers of mass marks each object's intensity-weighted center and object number.",
                "For very high object counts, text numbers are skipped while map pixels and statistics are preserved."
        });

        addListSection(panel, "Results tables to show", new String[] {
                "Statistics opens the per-object measurements table.",
                "X, Y, and Z are the object's geometric centroid. XM, YM, and ZM are the intensity-weighted center of mass.",
                "Those coordinate sets match when the measurement image has uniform values inside the object.",
                "Summary writes a short count summary to the ImageJ log.",
                "Redirect measurements to measures intensity from another open image while using this image for object detection."
        });

        addListSection(panel, "Action buttons", new String[] {
                "Preview runs the counter with the current settings, shows selected preview maps, and keeps this dialog open.",
                "OK runs the counter with the current settings, shows the selected outputs, and closes this dialog.",
                "Cancel closes this dialog without running the counter."
        });
        return panel;
    }

    private static JScrollPane createScrollPane(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        scroll.setPreferredSize(new Dimension(720, 560));
        return scroll;
    }

    private static JPanel createFooter(final JDialog dialog) {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        footer.setBackground(BG);
        JButton close = new JButton("Close");
        close.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
        footer.add(close);
        return footer;
    }

    private static JLabel titleLabel(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 17f));
        label.setForeground(HEADER);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void addListSection(JPanel panel, String heading, String[] values) {
        panel.add(Box.createVerticalStrut(8));
        JLabel headingLabel = new JLabel(heading == null ? "" : heading);
        headingLabel.setFont(headingLabel.getFont().deriveFont(Font.BOLD, 13f));
        headingLabel.setForeground(SUBHEADER);
        headingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(headingLabel);
        panel.add(Box.createVerticalStrut(4));

        if (values != null) {
            for (String value : values) {
                panel.add(bullet(value));
                panel.add(Box.createVerticalStrut(3));
            }
        }
        panel.add(Box.createVerticalStrut(4));
    }

    private static JLabel bullet(String text) {
        JLabel label = new JLabel("<html><body width='" + TEXT_WIDTH + "'>"
                + "&bull; " + htmlText(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(TEXT);
        label.setBorder(new EmptyBorder(0, 8, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel paragraph(String text, float size, Color color) {
        JLabel label = new JLabel("<html><body width='" + TEXT_WIDTH + "'>"
                + htmlText(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, size));
        label.setForeground(color);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static String htmlText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    static String collectVisibleTextForTests(Container root) {
        StringBuilder text = new StringBuilder();
        collectVisibleText(root, text);
        return text.toString();
    }

    private static void collectVisibleText(Component component, StringBuilder out) {
        if (component instanceof JLabel) {
            out.append(' ').append(((JLabel) component).getText());
        } else if (component instanceof JButton) {
            out.append(' ').append(((JButton) component).getText());
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collectVisibleText(children[i], out);
            }
        }
    }
}

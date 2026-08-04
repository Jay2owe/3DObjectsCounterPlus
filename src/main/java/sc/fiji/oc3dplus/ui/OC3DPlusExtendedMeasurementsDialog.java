package sc.fiji.oc3dplus.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Secondary modal editor for optional measurements. It edits a snapshot and
 * changes the main model only when the user presses {@code Use Settings}.
 */
public final class OC3DPlusExtendedMeasurementsDialog extends JDialog {

    public interface AppliedCallback {
        void settingsApplied();
    }

    private final OC3DPlusDialogModel destination;
    private final OC3DPlusDialogModel draft;
    private final List<RangeEditors> rangeEditors = new ArrayList<RangeEditors>();
    private final List<FamilyHeader> familyHeaders = new ArrayList<FamilyHeader>();
    private final JPanel rangesPanel;
    private final JCheckBox fractal;
    private final JCheckBox composites;
    private final JCheckBox arborization;
    private final JButton useSettings;

    public OC3DPlusExtendedMeasurementsDialog(Window owner,
                                               OC3DPlusDialogModel model,
                                               final AppliedCallback callback) {
        super(owner, "Extended measurements", Dialog.ModalityType.APPLICATION_MODAL);
        if (model == null) {
            throw new IllegalArgumentException("model must not be null.");
        }
        destination = model;
        draft = model.snapshot();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel explanation = new JLabel(
                "<html>Optional measurements are added as extra result columns.<br>"
                        + "Arborization is slower and uses Fiji's Skeletonize3D when available.</html>");
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(explanation);
        root.add(Box.createVerticalStrut(8));

        fractal = new JCheckBox("Fractal complexity (XY projection)", draft.measureFractalXY);
        composites = new JCheckBox("Composite shape indices (RI, SRI, PB, MP, VSD)",
                draft.measureComposites);
        arborization = new JCheckBox("Arborization and Sholl measurements (slow)",
                draft.measureArborization);
        fractal.setToolTipText(
                "Uses the object's union projection across Z; this is an XY measurement.");
        composites.setToolTipText(
                "Adds dependency-free indices derived from measurements already calculated.");
        arborization.setToolTipText(
                "Skeleton graph counts plus centroid-centred 5 um Sholl measurements; "
                        + "runtime can increase substantially.");
        root.add(fractal);
        root.add(composites);
        root.add(arborization);
        root.add(Box.createVerticalStrut(8));

        rangesPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 3, 2, 3);
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        addHeader(rangesPanel, gbc, "Measurement", 0);
        addHeader(rangesPanel, gbc, "Minimum", 1);
        addHeader(rangesPanel, gbc, "Maximum", 2);
        String previousFamily = "";
        for (OC3DPlusDialogModel.FeatureRange range : draft.extendedFeatureRanges()) {
            String family = familyOf(range.feature);
            if (!family.equals(previousFamily)) {
                gbc.gridy++;
                FamilyHeader familyHeader = addFamilyHeader(
                        rangesPanel, gbc, family, familyLabel(family));
                familyHeaders.add(familyHeader);
                previousFamily = family;
            }
            gbc.gridy++;
            RangeEditors editors = new RangeEditors(range);
            rangeEditors.add(editors);
            gbc.gridx = 0;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            rangesPanel.add(editors.label, gbc);
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.gridx = 1;
            rangesPanel.add(editors.min, gbc);
            gbc.gridx = 2;
            rangesPanel.add(editors.max, gbc);
        }

        JScrollPane scroll = new JScrollPane(rangesPanel);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(620, 330));
        root.add(scroll);
        root.add(Box.createVerticalStrut(8));

        ActionListener enabledChanged = new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                refreshRangeEnabledState();
            }
        };
        fractal.addActionListener(enabledChanged);
        composites.addActionListener(enabledChanged);
        arborization.addActionListener(enabledChanged);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        useSettings = new JButton("Use Settings");
        useSettings.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!applyEditorsToDraft()) return;
                destination.copyExtendedSettingsFrom(draft);
                if (callback != null) callback.settingsApplied();
                dispose();
            }
        });
        buttons.add(cancel);
        buttons.add(useSettings);
        root.add(buttons);

        DocumentListener validityListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                refreshUseSettingsEnabledState();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                refreshUseSettingsEnabledState();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                refreshUseSettingsEnabledState();
            }
        };
        for (int i = 0; i < rangeEditors.size(); i++) {
            rangeEditors.get(i).min.getDocument().addDocumentListener(validityListener);
            rangeEditors.get(i).max.getDocument().addDocumentListener(validityListener);
        }

        setContentPane(root);
        getRootPane().setDefaultButton(useSettings);
        getRootPane().registerKeyboardAction(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
        refreshRangeEnabledState();
    }

    private boolean applyEditorsToDraft() {
        draft.measureFractalXY = fractal.isSelected();
        draft.measureComposites = composites.isSelected();
        draft.measureArborization = arborization.isSelected();
        for (int i = 0; i < rangeEditors.size(); i++) {
            RangeEditors row = rangeEditors.get(i);
            if (familySelected(familyOf(row.range.feature))) {
                row.copyToModel();
            }
        }
        List<String> errors = draft.validate();
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) message.append('\n');
                message.append(errors.get(i));
            }
            JOptionPane.showMessageDialog(this, message.toString(),
                    "Invalid extended settings", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    private void refreshRangeEnabledState() {
        for (int i = 0; i < rangeEditors.size(); i++) {
            RangeEditors row = rangeEditors.get(i);
            String feature = row.range.feature;
            boolean enabled = fractal.isSelected()
                    && sc.fiji.oc3dplus.api.ExtendedFeatureCatalog.isFractalFeature(feature)
                    || composites.isSelected()
                    && sc.fiji.oc3dplus.api.ExtendedFeatureCatalog.isCompositeFeature(feature)
                    || arborization.isSelected()
                    && sc.fiji.oc3dplus.api.ExtendedFeatureCatalog.isArborizationFeature(feature);
            row.setVisible(enabled);
        }
        for (int i = 0; i < familyHeaders.size(); i++) {
            FamilyHeader header = familyHeaders.get(i);
            header.label.setVisible(familySelected(header.family));
        }
        rangesPanel.revalidate();
        rangesPanel.repaint();
        refreshUseSettingsEnabledState();
    }

    private void refreshUseSettingsEnabledState() {
        for (int i = 0; i < rangeEditors.size(); i++) {
            RangeEditors row = rangeEditors.get(i);
            if (row.min.isVisible()
                    && !row.range.accepts(row.min.getText(), row.max.getText())) {
                useSettings.setEnabled(false);
                return;
            }
        }
        useSettings.setEnabled(true);
    }

    private boolean familySelected(String family) {
        if ("fractal".equals(family)) return fractal.isSelected();
        if ("composites".equals(family)) return composites.isSelected();
        return arborization.isSelected();
    }

    private static String familyOf(String feature) {
        if (sc.fiji.oc3dplus.api.ExtendedFeatureCatalog.isFractalFeature(feature)) {
            return "fractal";
        }
        if (sc.fiji.oc3dplus.api.ExtendedFeatureCatalog.isCompositeFeature(feature)) {
            return "composites";
        }
        return "arborization";
    }

    private static String familyLabel(String family) {
        if ("fractal".equals(family)) return "Fractal XY";
        if ("composites".equals(family)) return "Composite indices";
        return "Arborization and Sholl";
    }

    private static FamilyHeader addFamilyHeader(JPanel panel,
                                                 GridBagConstraints gbc,
                                                 String family,
                                                 String text) {
        GridBagConstraints heading = (GridBagConstraints) gbc.clone();
        heading.gridx = 0;
        heading.gridwidth = 3;
        heading.fill = GridBagConstraints.HORIZONTAL;
        heading.insets = new Insets(8, 3, 2, 3);
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(label, heading);
        return new FamilyHeader(family, label);
    }

    private static void addHeader(JPanel panel,
                                  GridBagConstraints gbc,
                                  String text,
                                  int column) {
        gbc.gridx = column;
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(label, gbc);
    }

    private static final class RangeEditors {
        final OC3DPlusDialogModel.FeatureRange range;
        final JLabel label;
        final JTextField min = new JTextField(9);
        final JTextField max = new JTextField(9);

        RangeEditors(OC3DPlusDialogModel.FeatureRange range) {
            this.range = range;
            label = new JLabel(range.label);
            min.setText(range.minText);
            max.setText(range.maxText);
            String tooltip = tooltipFor(range.feature);
            label.setToolTipText(tooltip);
            min.setToolTipText(tooltip);
            max.setToolTipText(tooltip);
        }

        void setVisible(boolean visible) {
            label.setVisible(visible);
            min.setVisible(visible);
            max.setVisible(visible);
        }

        void copyToModel() {
            range.minText = min.getText();
            range.maxText = max.getText();
        }
    }

    private static String tooltipFor(String feature) {
        if ("fractal_dim_xy".equals(feature)) {
            return "Box-counting dimension of the object's XY union projection.";
        }
        if ("fractal_r2_xy".equals(feature)) {
            return "Fit quality. Values below 0.9 make the reported fractal and lacunarity values unavailable.";
        }
        if ("ri".equals(feature)) {
            return "RI = 1 / sphericity; this is a geometry-derived redundancy, not an independent observation.";
        }
        if ("mp".equals(feature)) {
            return "MP is undefined for spheres and near-spheres when its denominator is close to zero.";
        }
        if (feature != null && feature.startsWith("sholl_")) {
            return "Centroid-centred Sholl measurement using fixed 5 um radii; recognised spatial units are required.";
        }
        if (feature != null && feature.startsWith("skeleton_")) {
            return "Graph measurement from Fiji Skeletonize3D output; enabling arborization can be slow.";
        }
        return "Optional measurement filter. Leave the default range to keep all finite values.";
    }

    private static final class FamilyHeader {
        final String family;
        final JLabel label;

        FamilyHeader(String family, JLabel label) {
            this.family = family;
            this.label = label;
        }
    }
}

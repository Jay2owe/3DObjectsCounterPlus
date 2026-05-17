package sc.fiji.oc3dplus.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.process.ImageProcessor;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ImageOps;
import sc.fiji.oc3dplus.engine.ObjectMapBuilder;
import sc.fiji.oc3dplus.engine.SummaryReporter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Panel;
import java.awt.Scrollbar;
import java.awt.TextField;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.List;

/**
 * Interactive Swing dialog for 3D Objects Counter+. Lays out the native
 * 3D Objects Counter controls first, then adds fixed min/max morphology
 * ranges in the filter section.
 *
 * <p>The dialog is modeless so ImageJ image windows remain interactive while
 * it is open. Heavy work (Preview, OK) runs on a {@link SwingWorker} so the
 * EDT stays responsive. The model lives in {@link OC3DPlusDialogModel} so
 * the data-handling logic is unit-testable independently of the dialog.
 */
public final class OC3DPlusDialog extends JDialog {

    private static final String THRESHOLD_PREVIEW_CURRENT_PLANE_ONLY =
            "sc.fiji.oc3dplus.thresholdPreviewCurrentPlaneOnly";
    private static final Dimension NATIVE_SCROLLBAR_SIZE = new Dimension(180, 18);
    private static final Color SUBTLE_STATUS_TEXT = new Color(117, 117, 117);
    private static final int INPUT_FIELD_COLUMNS = 4;
    private static final int FILTER_INPUT_FIELD_COLUMNS = 4;

    /** Called on OK with the final accepted result. */
    public interface OkHandler {
        void onOk(OC3DPlusDialogModel model, OC3DPlusResult result);
    }

    private final OC3DPlusDialogModel model;
    private final ImagePlus targetImage;
    private final OkHandler okHandler;
    private final boolean targetImageManagedAtLaunch;

    private final TextField thresholdField;
    private final Scrollbar thresholdScrollbar;
    private final TextField sliceField;
    private final Scrollbar sliceScrollbar;
    private final JFormattedTextField minField;
    private final JFormattedTextField maxField;
    private final JCheckBox excludeEdges;
    private final JCheckBox showLabels;
    private final JCheckBox showSurfaces;
    private final JCheckBox showCentroids;
    private final JCheckBox showCentersOfMass;
    private final JCheckBox showStats;
    private final JCheckBox showSummary;
    private final JComboBox<String> redirectBox;
    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private final JButton previewButton;
    private final JButton okButton;
    private final JButton cancelButton;
    private final FilterRowsPanel filterRows;
    private final JLabel filterSummary;
    private final double thresholdDisplayMaximum;

    private final List<ImagePlus> previewMapImages = new java.util.ArrayList<ImagePlus>();
    private ImagePlus thresholdPreviewImage;
    private ImageProcessor targetThresholdDisplayProcessor;
    private SwingWorker<?, ?> currentWorker;
    private SwingWorker<ImagePlus, Void> thresholdPreviewWorker;
    private Timer thresholdPreviewTimer;
    private Timer busyStatusTimer;
    private int workerGeneration;
    private int thresholdPreviewGeneration;
    private long busyStartedMillis;
    private String busyBaseStatus;
    private boolean syncingThresholdControls;
    private boolean syncingSliceControls;
    private volatile boolean dialogDisposed;

    public OC3DPlusDialog(Frame owner, ImagePlus target, OkHandler okHandler) {
        super(owner, "3D Objects Counter+", false);
        if (target == null) {
            throw new IllegalArgumentException(
                    "target image must not be null (target=null; expected a 3D ImagePlus).");
        }
        this.targetImage = target;
        this.okHandler = okHandler == null ? new OkHandler() {
            @Override public void onOk(OC3DPlusDialogModel model, OC3DPlusResult result) {}
        } : okHandler;
        this.model = new OC3DPlusDialogModel();
        this.model.configureForImage(target);
        this.model.threshold = OC3DPlusDialogDefaults.isoDataThresholdAtCenterSlice(
                target, this.model.threshold);
        this.model.maxSize = defaultMaxSize(target);
        OC3DPlusDialogDefaults.moveToCenterSlice(target);
        this.thresholdDisplayMaximum = OC3DPlusDialogDefaults.finiteMaximum(
                target, this.model.threshold);
        this.targetImageManagedAtLaunch = isManagedImage(target);

        Container root = getContentPane();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cancelActiveWorker();
                releasePreviewWindows();
            }
        });

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        content.add(createHelpHeader());
        content.add(Box.createVerticalStrut(6));

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        gbc.gridy = row;
        gbc.gridx = 0;
        fields.add(new JLabel("Image:"), gbc);
        gbc.gridx = 1;
        fields.add(new JLabel(target.getTitle()), gbc);
        row++;

        gbc.gridy = row;
        gbc.gridx = 0;
        fields.add(new JLabel("Threshold:"), gbc);
        gbc.gridx = 1;
        int thresholdSliderMin = OC3DPlusDialogDefaults.sliderMinimum(target);
        int thresholdSliderMax = Math.max(thresholdSliderMin,
                OC3DPlusDialogDefaults.sliderMaximum(target, model.threshold));
        thresholdScrollbar = new Scrollbar(Scrollbar.HORIZONTAL,
                clampToSlider(model.threshold, thresholdSliderMin, thresholdSliderMax),
                1,
                thresholdSliderMin,
                scrollbarMaximumFor(thresholdSliderMax));
        setNativeScrollbarSize(thresholdScrollbar);
        thresholdField = new TextField(Integer.toString(model.threshold), INPUT_FIELD_COLUMNS);
        thresholdScrollbar.addAdjustmentListener(new AdjustmentListener() {
            @Override public void adjustmentValueChanged(AdjustmentEvent e) {
                onThresholdScrollbarChanged();
            }
        });
        thresholdField.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateThresholdFromField();
            }
        });
        thresholdField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                updateThresholdFromField();
            }
        });
        fields.add(createScrollbarControls(thresholdScrollbar, thresholdField), gbc);
        row++;

        gbc.gridy = row;
        gbc.gridx = 0;
        fields.add(new JLabel("Slice:"), gbc);
        gbc.gridx = 1;
        int sliceMax = targetSliceCount(target);
        int currentSlice = clampToSlider(currentTargetSlice(), 1, sliceMax);
        sliceScrollbar = new Scrollbar(Scrollbar.HORIZONTAL,
                currentSlice,
                1,
                1,
                scrollbarMaximumFor(sliceMax));
        setNativeScrollbarSize(sliceScrollbar);
        sliceField = new TextField(Integer.toString(currentSlice), INPUT_FIELD_COLUMNS);
        sliceScrollbar.addAdjustmentListener(new AdjustmentListener() {
            @Override public void adjustmentValueChanged(AdjustmentEvent e) {
                onSliceScrollbarChanged();
            }
        });
        sliceField.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateSliceFromField();
            }
        });
        sliceField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                updateSliceFromField();
            }
        });
        fields.add(createScrollbarControls(sliceScrollbar, sliceField), gbc);
        row++;

        content.add(fields);
        content.add(Box.createVerticalStrut(8));

        minField = new JFormattedTextField();
        minField.setColumns(FILTER_INPUT_FIELD_COLUMNS);
        minField.setText(Integer.toString(model.minSize));
        keepInputWidthToColumns(minField);
        minField.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateMinFromField();
            }
        });
        minField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                updateMinFromField();
            }
        });

        maxField = new JFormattedTextField();
        maxField.setColumns(FILTER_INPUT_FIELD_COLUMNS);
        maxField.setText(formatMaxSize(model.maxSize));
        keepInputWidthToColumns(maxField);
        maxField.addPropertyChangeListener("value", evt -> updateMaxFromField());
        maxField.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateMaxFromField();
            }
        });

        excludeEdges = new JCheckBox("Exclude objects on edges", model.excludeOnEdges);
        excludeEdges.setAlignmentX(Component.LEFT_ALIGNMENT);
        excludeEdges.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.excludeOnEdges = excludeEdges.isSelected();
                markPreviewStale();
            }
        });

        filterSummary = new JLabel(noMorphologyFiltersText());
        filterSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        styleSubtleStatusLine(filterSummary);

        FilterRowsPanel.ChangeCallback filtersChanged = new FilterRowsPanel.ChangeCallback() {
            @Override public void filtersChanged() {
                updateFilterSummary(null);
                markPreviewStale();
            }
        };
        filterRows = new FilterRowsPanel(model, filtersChanged, minField, maxField);

        content.add(sectionLabel("Filters:"));
        content.add(createFilterRowsScrollPane());
        content.add(Box.createVerticalStrut(2));
        content.add(excludeEdges);
        content.add(Box.createVerticalStrut(4));
        content.add(filterSummary);
        content.add(Box.createVerticalStrut(8));

        showLabels = new JCheckBox("Objects", model.showLabels);
        showLabels.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.showLabels = showLabels.isSelected();
            }
        });

        showSurfaces = new JCheckBox("Surfaces", model.showSurfaces);
        showSurfaces.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.showSurfaces = showSurfaces.isSelected();
            }
        });

        showCentroids = new JCheckBox("Centroids", model.showCentroids);
        showCentroids.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.showCentroids = showCentroids.isSelected();
            }
        });

        showCentersOfMass = new JCheckBox("Centers of mass", model.showCentersOfMass);
        showCentersOfMass.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.showCentersOfMass = showCentersOfMass.isSelected();
            }
        });

        content.add(sectionLabel("Maps to show:"));
        content.add(createCheckboxColumn(showLabels, showSurfaces, showCentroids, showCentersOfMass));
        content.add(Box.createVerticalStrut(8));

        showStats = new JCheckBox("Statistics", model.showStats);
        showStats.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.showStats = showStats.isSelected();
            }
        });

        showSummary = new JCheckBox("Summary", model.showSummary);
        showSummary.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                model.showSummary = showSummary.isSelected();
            }
        });

        content.add(sectionLabel("Results tables to show:"));
        content.add(createCheckboxColumn(showStats, showSummary));
        content.add(Box.createVerticalStrut(8));

        JPanel redirectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        redirectPanel.setOpaque(false);
        redirectPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        redirectPanel.add(new JLabel("Redirect measurements to:"));
        redirectBox = new JComboBox<String>(buildOpenImageTitles());
        redirectBox.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                Object sel = redirectBox.getSelectedItem();
                model.redirectTitle = (sel == null || "(none)".equals(sel)) ? "" : sel.toString();
                markPreviewStale();
            }
        });
        redirectPanel.add(redirectBox);
        content.add(redirectPanel);
        content.add(Box.createVerticalStrut(8));

        statusLabel = new JLabel(" ");
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        styleSubtleStatusLine(statusLabel);
        content.add(statusLabel);

        progressBar = new JProgressBar();
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(false);
        progressBar.setVisible(false);
        Dimension progressSize = progressBar.getPreferredSize();
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, progressSize.height));
        content.add(Box.createVerticalStrut(3));
        content.add(progressBar);

        add(content);
        add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        previewButton = new JButton("Preview");
        previewButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                runEngineAsync(/* commitResult */ false);
            }
        });
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        okButton = new JButton("OK");
        okButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                runEngineAsync(/* commitResult */ true);
            }
        });
        buttons.add(previewButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancelButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(okButton);
        add(buttons);

        pack();
        setLocationRelativeTo(owner);
        applyTargetThresholdDisplay(model.threshold);
        setStatus("Default threshold: center-slice IsoData = " + model.threshold + ".");
    }

    private Component createHelpHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("3D Objects Counter+");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        header.add(title, BorderLayout.CENTER);

        JButton helpButton = OC3DPlusHelpDialog.createHelpButton("About these controls.");
        helpButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                OC3DPlusHelpDialog.show(OC3DPlusDialog.this);
            }
        });

        JPanel helpPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        helpPanel.setOpaque(false);
        helpPanel.add(helpButton);
        header.add(helpPanel, BorderLayout.EAST);
        return header;
    }

    private Component createScrollbarControls(Scrollbar scrollbar, TextField field) {
        Panel controls = new Panel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controls.add(scrollbar);
        controls.add(field);
        return controls;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return label;
    }

    private static JPanel createCheckboxColumn(JCheckBox... boxes) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (boxes != null) {
            for (JCheckBox box : boxes) {
                if (box == null) continue;
                box.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(box);
            }
        }
        return panel;
    }

    private static void styleSubtleStatusLine(JLabel label) {
        if (label == null) return;
        label.setForeground(SUBTLE_STATUS_TEXT);
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
    }

    private void onThresholdScrollbarChanged() {
        if (syncingThresholdControls) return;
        model.threshold = thresholdScrollbar.getValue();
        syncingThresholdControls = true;
        try {
            thresholdField.setText(Integer.toString(model.threshold));
        } finally {
            syncingThresholdControls = false;
        }
        onThresholdChanged();
    }

    private boolean updateThresholdFromField() {
        if (syncingThresholdControls) return true;
        String text = thresholdField.getText() == null ? "" : thresholdField.getText().trim();
        int parsed;
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) {
                thresholdField.setText(Integer.toString(model.threshold));
                setStatus("Threshold value '" + text + "' is invalid; keeping " + model.threshold + ".");
                return false;
            }
            parsed = (int) Math.round(value);
        } catch (NumberFormatException invalidThreshold) {
            thresholdField.setText(Integer.toString(model.threshold));
            setStatus("Threshold value '" + text + "' is invalid; keeping " + model.threshold + ".");
            return false;
        }
        int min = thresholdScrollbar.getMinimum();
        int max = thresholdScrollbar.getMaximum() - thresholdScrollbar.getVisibleAmount();
        int clamped = clampToSlider(parsed, min, max);
        boolean changed = model.threshold != clamped;
        model.threshold = clamped;
        syncingThresholdControls = true;
        try {
            thresholdScrollbar.setValue(clamped);
            thresholdField.setText(Integer.toString(clamped));
        } finally {
            syncingThresholdControls = false;
        }
        if (changed) {
            onThresholdChanged();
        }
        return true;
    }

    private void onThresholdChanged() {
        applyTargetThresholdDisplay(model.threshold);
        setStatus("Threshold overlay updated. Press Preview to update object maps and counts.");
    }

    private void onSliceScrollbarChanged() {
        if (syncingSliceControls) return;
        int slice = sliceScrollbar.getValue();
        syncingSliceControls = true;
        try {
            sliceField.setText(Integer.toString(slice));
        } finally {
            syncingSliceControls = false;
        }
        setTargetSlice(slice);
        applyTargetThresholdDisplay(model.threshold);
        setStatus("Slice updated. Threshold overlay is shown on slice " + slice + ".");
    }

    private boolean updateSliceFromField() {
        if (syncingSliceControls) return true;
        String text = sliceField.getText() == null ? "" : sliceField.getText().trim();
        int parsed;
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) {
                sliceField.setText(Integer.toString(currentTargetSlice()));
                setStatus("Slice value '" + text + "' is invalid.");
                return false;
            }
            parsed = (int) Math.round(value);
        } catch (NumberFormatException invalidSlice) {
            sliceField.setText(Integer.toString(currentTargetSlice()));
            setStatus("Slice value '" + text + "' is invalid.");
            return false;
        }
        int clamped = clampToSlider(parsed, 1, targetSliceCount(targetImage));
        boolean changed = currentTargetSlice() != clamped;
        syncingSliceControls = true;
        try {
            sliceScrollbar.setValue(clamped);
            sliceField.setText(Integer.toString(clamped));
        } finally {
            syncingSliceControls = false;
        }
        if (changed) {
            setTargetSlice(clamped);
            applyTargetThresholdDisplay(model.threshold);
            setStatus("Slice updated. Threshold overlay is shown on slice " + clamped + ".");
        }
        return true;
    }

    private void runEngineAsync(final boolean commitResult) {
        if (dialogDisposed) return;
        if (currentWorker != null && !currentWorker.isDone()) {
            setStatus("A run is already in progress.");
            return;
        }

        if (!updateThresholdFromField() || !updateSliceFromField()
                || !updateMinFromField() || !updateMaxFromField()) {
            return;
        }

        List<String> errors = model.validate();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Image: " + titleOf(targetImage) + "\n" + String.join("\n", errors),
                    "Invalid parameters",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        final ImagePlus redirectImage = resolveRedirect();
        if (redirectImage == null && model.redirectTitle != null && !model.redirectTitle.isEmpty()) {
            return;
        }

        final OC3DPlusDialogModel runModel = model.snapshot();
        final String sourceTitle = titleOf(targetImage);
        final int requestId = ++workerGeneration;
        setControlsEnabled(false);
        beginBusyStatus(commitResult
                ? "Running count and preparing outputs"
                : "Computing preview and preparing maps");

        SwingWorker<EngineRunOutput, Void> worker = new SwingWorker<EngineRunOutput, Void>() {
            @Override protected EngineRunOutput doInBackground() {
                ProcessingImages processingImages = null;
                try {
                    if (isCancelled() || dialogDisposed) return null;
                    processingImages = createProcessingImages(targetImage, redirectImage);
                    if (isCancelled() || dialogDisposed) return null;
                    OC3DPlusParameters params = runModel.toParameters(
                            processingImages.redirect,
                            new OC3DPlusParameters.WarningSink() {
                                @Override public void warn(String message) {
                                    IJ.log(message);
                                }
                            });
                    OC3DPlusResult result = OC3DPlus.count(processingImages.target, params);
                    if (isCancelled() || dialogDisposed) {
                        discardResultImages(result);
                        return null;
                    }
                    processingImages.discard();
                    processingImages = null;
                    return buildEngineRunOutput(result, runModel, commitResult, sourceTitle);
                } finally {
                    if (processingImages != null) {
                        processingImages.discard();
                    }
                }
            }

            @Override protected void done() {
                if (requestId != workerGeneration || dialogDisposed || isCancelled()) {
                    if (currentWorker == this) currentWorker = null;
                    endBusyStatus();
                    return;
                }
                boolean finalOutputStarted = false;
                try {
                    EngineRunOutput output = get();
                    if (output != null) {
                        finalOutputStarted = onEngineDone(output, commitResult, runModel,
                                sourceTitle, requestId);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    handleEngineFailure(ie, runModel);
                } catch (CancellationException ce) {
                    setStatus("Cancelled.");
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    handleEngineFailure(cause, runModel);
                } finally {
                    if (!finalOutputStarted && currentWorker == this) currentWorker = null;
                    if (!finalOutputStarted) endBusyStatus();
                    if (!finalOutputStarted && requestId == workerGeneration && !dialogDisposed) {
                        setControlsEnabled(true);
                    }
                }
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    private boolean onEngineDone(EngineRunOutput output, boolean commitResult,
                                 OC3DPlusDialogModel runModel,
                                 String sourceTitle,
                                 int requestId) {
        OC3DPlusResult result = output.result;
        updateFilterSummary(result, runModel);
        if (commitResult) {
            setStatus("Count complete. Opening results and selected maps...");
            startFinalOutputWorker(output, runModel, sourceTitle, requestId);
            return true;
        } else {
            // Preview: replace any prior preview window
            reportMapWarnings(output);
            closePreviewMaps();
            showPreparedMaps(output.maps, true);
            setStatus("Preview: " + result.objectCount() + " object"
                    + (result.objectCount() == 1 ? "" : "s") + ".");
            return false;
        }
    }

    private void startFinalOutputWorker(final EngineRunOutput output,
                                        final OC3DPlusDialogModel runModel,
                                        final String sourceTitle,
                                        final int requestId) {
        SwingWorker<Void, Void> outputWorker = new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                showFinalOutputs(output, runModel, sourceTitle);
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    if (requestId == workerGeneration && !dialogDisposed) {
                        reportMapWarnings(output);
                        okHandler.onOk(runModel, output.result);
                        closePreviewMaps();
                        closeThresholdPreview();
                        dispose();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    handleEngineFailure(ie, runModel);
                } catch (CancellationException ce) {
                    setStatus("Cancelled.");
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    handleEngineFailure(cause, runModel);
                } finally {
                    if (currentWorker == this) currentWorker = null;
                    endBusyStatus();
                    if (requestId == workerGeneration && !dialogDisposed) {
                        setControlsEnabled(true);
                    }
                }
            }
        };
        currentWorker = outputWorker;
        outputWorker.execute();
    }

    private void showFinalOutputs(EngineRunOutput output,
                                  OC3DPlusDialogModel runModel,
                                  String sourceTitle) {
        if (output == null || runModel == null) return;
        OC3DPlusResult result = output.result;
        String title = sourceTitle == null || sourceTitle.isEmpty()
                ? titleOf(targetImage) : sourceTitle;
        IJ.showStatus("3D Objects Counter+: opening result windows...");
        if (runModel.showStats && result != null && result.statistics() != null) {
            result.statistics().show("Results for " + title);
        }
        if (runModel.showSummary) {
            SummaryReporter.log(title, redirectTitle(runModel), result,
                    runModel.minSize, runModel.maxSize, runModel.threshold);
        }
        buildAndShowFinalMaps(output, runModel, title);
        IJ.showStatus("3D Objects Counter+: outputs ready for '" + title + "'.");
    }

    private EngineRunOutput buildEngineRunOutput(OC3DPlusResult result,
                                                 OC3DPlusDialogModel runModel,
                                                 boolean commitResult,
                                                 String sourceTitle) {
        EngineRunOutput output = new EngineRunOutput(result);
        if (!commitResult) {
            buildSelectedMaps(output, runModel, commitResult, sourceTitle);
        }
        return output;
    }

    private void buildAndShowFinalMaps(final EngineRunOutput output,
                                       OC3DPlusDialogModel runModel,
                                       final String title) {
        final OC3DPlusResult result = output == null ? null : output.result;
        if (result == null || result.labelImage() == null || runModel == null) return;
        if (runModel.showLabels) {
            buildAndShowFinalMap(output, "Objects", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.objectMapInPlace(result.labelImage(),
                            result.statistics(), title);
                }
            });
        }
        if (runModel.showSurfaces) {
            buildAndShowFinalMap(output, "Surfaces", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.surfaceMap(result.labelImage(),
                            result.statistics(), title);
                }
            });
        }
        if (runModel.showCentroids) {
            buildAndShowFinalMap(output, "Centroids", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.centroidMap(result.labelImage(),
                            result.statistics(), title);
                }
            });
        }
        if (runModel.showCentersOfMass) {
            buildAndShowFinalMap(output, "Centers of mass", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.centerOfMassMap(result.labelImage(),
                            result.statistics(), title);
                }
            });
        }
    }

    private void buildAndShowFinalMap(EngineRunOutput output,
                                      String mapName,
                                      MapFactory factory) {
        try {
            ImagePlus map = factory == null ? null : factory.create();
            showMap(map, false);
        } catch (OutOfMemoryError oom) {
            if (output != null) output.mapWarnings.add(new MapBuildWarning(mapName, oom));
            System.gc();
        } catch (RuntimeException mapFailed) {
            if (output != null) output.mapWarnings.add(new MapBuildWarning(mapName, mapFailed));
        }
    }

    private void buildSelectedMaps(EngineRunOutput output,
                                   OC3DPlusDialogModel runModel,
                                   boolean commitResult,
                                   String sourceTitle) {
        OC3DPlusResult result = output == null ? null : output.result;
        if (result == null || result.labelImage() == null || runModel == null) return;
        String title = sourceTitle == null || sourceTitle.isEmpty() ? "<untitled>" : sourceTitle;
        boolean labelImageShown = false;
        if (runModel.showLabels) {
            try {
                output.maps.add(prefixPreview(ObjectMapBuilder.objectMapInPlace(result.labelImage(),
                        result.statistics(), title), !commitResult));
                labelImageShown = true;
            } catch (OutOfMemoryError oom) {
                output.mapWarnings.add(new MapBuildWarning("Objects", oom));
            } catch (RuntimeException mapFailed) {
                output.mapWarnings.add(new MapBuildWarning("Objects", mapFailed));
            }
        }
        if (runModel.showSurfaces) {
            buildGeneratedMap(output, "Surfaces", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.surfaceMap(result.labelImage(),
                            result.statistics(), title);
                }
            }, !commitResult);
        }
        if (runModel.showCentroids) {
            buildGeneratedMap(output, "Centroids", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.centroidMap(result.labelImage(),
                            result.statistics(), title);
                }
            }, !commitResult);
        }
        if (runModel.showCentersOfMass) {
            buildGeneratedMap(output, "Centers of mass", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.centerOfMassMap(result.labelImage(),
                            result.statistics(), title);
                }
            }, !commitResult);
        }
        if (!commitResult && !labelImageShown) {
            discardImage(result.labelImage());
        }
    }

    private interface MapFactory {
        ImagePlus create();
    }

    private void buildGeneratedMap(EngineRunOutput output,
                                   String mapName,
                                   MapFactory factory,
                                   boolean preview) {
        try {
            ImagePlus map = prefixPreview(factory == null ? null : factory.create(), preview);
            if (map != null && output != null) output.maps.add(map);
        } catch (OutOfMemoryError oom) {
            if (output != null) output.mapWarnings.add(new MapBuildWarning(mapName, oom));
        } catch (RuntimeException mapFailed) {
            if (output != null) output.mapWarnings.add(new MapBuildWarning(mapName, mapFailed));
        }
    }

    private void showPreparedMaps(List<ImagePlus> maps, boolean preview) {
        if (maps == null) return;
        for (int i = 0; i < maps.size(); i++) {
            showMap(maps.get(i), preview);
        }
    }

    private void reportMapWarnings(EngineRunOutput output) {
        if (output == null || output.mapWarnings.isEmpty()) return;
        HashSet<String> names = new HashSet<String>();
        boolean outOfMemory = false;
        for (int i = 0; i < output.mapWarnings.size(); i++) {
            MapBuildWarning warning = output.mapWarnings.get(i);
            if (warning == null) continue;
            names.add(warning.mapName);
            Throwable cause = warning.cause;
            if (cause instanceof OutOfMemoryError
                    || ObjectMapBuilder.isMemoryGuardFailure(cause)) {
                outOfMemory = true;
            }
            IJ.log("3D Objects Counter+ skipped " + warning.mapName + " map for image '"
                    + titleOf(targetImage) + "': " + causeSummary(cause));
        }
        if (outOfMemory) {
            System.gc();
            setStatus("Some maps were skipped: not enough memory. Results are still available.");
        } else {
            setStatus("Some maps were skipped: " + names.toString() + ".");
        }
    }

    private void handleMapGenerationFailure(String mapName, Throwable cause) {
        String safeName = mapName == null ? "Output" : mapName;
        IJ.log("3D Objects Counter+ skipped " + safeName + " map for image '"
                + titleOf(targetImage) + "': " + causeSummary(cause));
        if (cause instanceof OutOfMemoryError || ObjectMapBuilder.isMemoryGuardFailure(cause)) {
            System.gc();
            setStatus(safeName + " map skipped: not enough memory. Hide maps or close other images.");
        } else {
            setStatus(safeName + " map skipped: " + engineErrorMessage(cause));
        }
    }

    private ImagePlus prefixPreview(ImagePlus image, boolean preview) {
        if (image != null && preview) image.setTitle(mapTitle(image.getTitle(), true));
        return image;
    }

    private static String mapTitle(String title, boolean preview) {
        return preview ? "[Preview] " + title : title;
    }

    private static String redirectTitle(OC3DPlusDialogModel runModel) {
        if (runModel == null || runModel.redirectTitle == null
                || runModel.redirectTitle.isEmpty()) {
            return null;
        }
        return runModel.redirectTitle;
    }

    private void showMap(ImagePlus image, boolean preview) {
        if (image == null) return;
        logOverlaySkipped(image);
        image.show();
        if (preview) {
            previewMapImages.add(image);
        }
    }

    private void logOverlaySkipped(ImagePlus image) {
        String reason = ObjectMapBuilder.overlaySkippedReason(image);
        if (reason != null && !reason.isEmpty()) {
            IJ.log("3D Objects Counter+ " + reason + " Map pixels and statistics are unchanged.");
        }
    }

    private void updateFilterSummary(OC3DPlusResult result) {
        updateFilterSummary(result, model);
    }

    private void updateFilterSummary(OC3DPlusResult result, OC3DPlusDialogModel sourceModel) {
        List<sc.fiji.oc3dplus.api.MorphPredicate> predicates;
        try {
            predicates = sourceModel.enabledPredicates();
        } catch (RuntimeException invalidFilterValue) {
            filterSummary.setText("Morphology filter value is invalid.");
            return;
        }
        if (predicates.isEmpty()) {
            filterSummary.setText(noMorphologyFiltersText());
            return;
        }
        int[] counts = result == null ? null : result.survivingPerFilter();
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sb.append("<br>");
            sb.append(predicates.get(i).format()).append(": ");
            if (counts != null && i < counts.length) {
                sb.append(counts[i]).append(" surviving");
            } else {
                sb.append("(run preview)");
            }
        }
        sb.append("</html>");
        filterSummary.setText(sb.toString());
    }

    private static String noMorphologyFiltersText() {
        return "Morphology ranges: defaults (no extra filtering).";
    }

    private ImagePlus resolveRedirect() {
        if (model.redirectTitle == null || model.redirectTitle.isEmpty()) return null;
        ImagePlus redirect = WindowManager.getImage(model.redirectTitle);
        if (redirect == null) {
            setStatus("Redirect image '" + model.redirectTitle
                    + "' is no longer open. Select another image or (none).");
        }
        return redirect;
    }

    private boolean updateMinFromField() {
        String text = minField.getText() == null ? "" : minField.getText().trim();
        if (text.isEmpty()) {
            setStatus("Min size value is blank.");
            return false;
        }
        try {
            double parsed = Double.parseDouble(text);
            if (!Double.isFinite(parsed) || parsed < 0 || parsed >= Integer.MAX_VALUE) {
                setStatus("Min size value '" + text + "' is invalid.");
                return false;
            }
            model.minSize = (int) Math.round(parsed);
            minField.setText(Integer.toString(model.minSize));
            markPreviewStale();
            return true;
        } catch (NumberFormatException e) {
            setStatus("Min size value '" + text + "' is invalid.");
            return false;
        }
    }

    private boolean updateMaxFromField() {
        String text = maxField.getText() == null ? "" : maxField.getText().trim();
        String statusOverride = null;
        if (text.isEmpty()
                || "infinity".equalsIgnoreCase(text)
                || "inf".equalsIgnoreCase(text)) {
            model.maxSize = Integer.MAX_VALUE;
        } else {
            try {
                double parsed = Double.parseDouble(text);
                if (!Double.isFinite(parsed) || parsed < 0) {
                    model.maxSize = Integer.MAX_VALUE;
                    maxField.setText("Infinity");
                    statusOverride = "Max size value '" + text + "' is invalid; using Infinity.";
                } else if (parsed >= Integer.MAX_VALUE) {
                    model.maxSize = Integer.MAX_VALUE;
                } else {
                    model.maxSize = (int) Math.round(parsed);
                }
            } catch (NumberFormatException e) {
                model.maxSize = Integer.MAX_VALUE;
                maxField.setText("Infinity");
                statusOverride = "Max size value '" + text + "' is invalid; using Infinity.";
            }
        }
        if (statusOverride == null) {
            markPreviewStale();
        } else {
            setStatus(statusOverride);
        }
        return true;
    }

    private String[] buildOpenImageTitles() {
        int[] ids = WindowManager.getIDList();
        java.util.List<String> titles = new java.util.ArrayList<String>();
        titles.add("(none)");
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null && imp != targetImage) titles.add(imp.getTitle());
            }
        }
        return titles.toArray(new String[0]);
    }

    private void setControlsEnabled(boolean enabled) {
        previewButton.setEnabled(enabled);
        okButton.setEnabled(enabled);
        thresholdField.setEnabled(enabled);
        thresholdScrollbar.setEnabled(enabled);
        sliceField.setEnabled(enabled);
        sliceScrollbar.setEnabled(enabled);
        minField.setEnabled(enabled);
        maxField.setEnabled(enabled);
        excludeEdges.setEnabled(enabled);
        showLabels.setEnabled(enabled);
        showSurfaces.setEnabled(enabled);
        showCentroids.setEnabled(enabled);
        showCentersOfMass.setEnabled(enabled);
        showStats.setEnabled(enabled);
        showSummary.setEnabled(enabled);
        redirectBox.setEnabled(enabled);
        setTreeEnabled(filterRows, enabled);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    private void beginBusyStatus(String baseStatus) {
        busyBaseStatus = baseStatus == null || baseStatus.trim().isEmpty()
                ? "Working" : baseStatus.trim();
        busyStartedMillis = System.currentTimeMillis();
        setStatus(busyBaseStatus + "... Fiji is still working.");
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        progressBar.repaint();
        if (busyStatusTimer != null) {
            busyStatusTimer.stop();
        }
        busyStatusTimer = new Timer(1000, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                long seconds = Math.max(1L, (System.currentTimeMillis() - busyStartedMillis) / 1000L);
                setStatus(busyBaseStatus + "... still working (" + seconds + " s).");
            }
        });
        busyStatusTimer.start();
    }

    private void endBusyStatus() {
        if (busyStatusTimer != null) {
            busyStatusTimer.stop();
            busyStatusTimer = null;
        }
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        busyBaseStatus = null;
    }

    private void markPreviewStale() {
        setStatus("Parameters changed. Run preview to update counts.");
    }

    private void applyTargetThresholdDisplay(int threshold) {
        if (targetImage == null) return;
        ImageProcessor processor = targetImage.getProcessor();
        if (processor == null) return;
        if (targetThresholdDisplayProcessor != null
                && targetThresholdDisplayProcessor != processor) {
            targetThresholdDisplayProcessor.resetThreshold();
        }
        targetThresholdDisplayProcessor = processor;
        processor.setThreshold(threshold, Math.max(threshold, thresholdDisplayMaximum),
                ImageProcessor.RED_LUT);
        targetImage.updateAndDraw();
    }

    private void clearTargetThresholdDisplay() {
        if (targetThresholdDisplayProcessor == null) return;
        try {
            targetThresholdDisplayProcessor.resetThreshold();
            targetImage.updateAndDraw();
        } catch (RuntimeException ignored) {
            // The target image may already be closed; there is nothing left to update.
        }
        targetThresholdDisplayProcessor = null;
    }

    private void scheduleThresholdPreviewUpdate() {
        if (dialogDisposed) return;
        if (thresholdPreviewTimer == null) {
            thresholdPreviewTimer = new Timer(120, new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    runThresholdPreviewAsync();
                }
            });
            thresholdPreviewTimer.setRepeats(false);
        }
        thresholdPreviewTimer.restart();
    }

    private void runThresholdPreviewAsync() {
        if (dialogDisposed) return;

        SwingWorker<ImagePlus, Void> previous = thresholdPreviewWorker;
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }

        final int requestId = ++thresholdPreviewGeneration;
        final int threshold = model.threshold;
        SwingWorker<ImagePlus, Void> worker = new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() {
                if (isCancelled() || dialogDisposed) return null;
                ImagePlus preview = ImageOps.thresholdRetainedCurrentPlaneCopy(targetImage, threshold);
                if (preview != null) {
                    preview.setProperty(THRESHOLD_PREVIEW_CURRENT_PLANE_ONLY, Boolean.TRUE);
                    preview.setTitle("[Threshold current plane] " + targetImage.getTitle());
                }
                return preview;
            }

            @Override protected void done() {
                ImagePlus preview = null;
                try {
                    preview = get();
                    if (requestId != thresholdPreviewGeneration
                            || dialogDisposed || isCancelled()) {
                        discardImage(preview);
                        return;
                    }
                    showOrReplaceThresholdPreview(preview, threshold);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    discardImage(preview);
                } catch (CancellationException ce) {
                    discardImage(preview);
                } catch (ExecutionException ee) {
                    discardImage(preview);
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    IJ.log("3D Objects Counter+ threshold preview failed for image '"
                            + titleOf(targetImage) + "': " + causeSummary(cause));
                    if (requestId == thresholdPreviewGeneration && !dialogDisposed) {
                        setStatus("Threshold preview error: " + engineErrorMessage(cause));
                    }
                } finally {
                    if (thresholdPreviewWorker == this) thresholdPreviewWorker = null;
                }
            }
        };
        thresholdPreviewWorker = worker;
        worker.execute();
    }

    private void showOrReplaceThresholdPreview(ImagePlus preview, int threshold) {
        if (preview == null || dialogDisposed) {
            discardImage(preview);
            return;
        }
        boolean currentPlaneOnly = Boolean.TRUE.equals(
                preview.getProperty(THRESHOLD_PREVIEW_CURRENT_PLANE_ONLY));
        preview.setTitle((currentPlaneOnly ? "[Threshold current plane] " : "[Threshold] ")
                + targetImage.getTitle());
        if (thresholdPreviewImage == null || thresholdPreviewImage.getWindow() == null) {
            thresholdPreviewImage = preview;
            preview.show();
        } else {
            int c = Math.min(thresholdPreviewImage.getC(), Math.max(1, preview.getNChannels()));
            int z = Math.min(thresholdPreviewImage.getZ(), Math.max(1, preview.getNSlices()));
            int t = Math.min(thresholdPreviewImage.getT(), Math.max(1, preview.getNFrames()));
            thresholdPreviewImage.setStack(preview.getTitle(), preview.getStack());
            thresholdPreviewImage.setDimensions(
                    Math.max(1, preview.getNChannels()),
                    Math.max(1, preview.getNSlices()),
                    Math.max(1, preview.getNFrames()));
            thresholdPreviewImage.setOpenAsHyperStack(preview.isHyperStack());
            if (preview.getCalibration() != null) {
                thresholdPreviewImage.setCalibration(preview.getCalibration().copy());
            }
            thresholdPreviewImage.setProperty(THRESHOLD_PREVIEW_CURRENT_PLANE_ONLY,
                    currentPlaneOnly ? Boolean.TRUE : Boolean.FALSE);
            thresholdPreviewImage.setPosition(c, z, t);
            thresholdPreviewImage.updateAndDraw();
            preview.changes = false;
        }
        if (currentPlaneOnly) {
            setStatus("Threshold mask: current plane only, voxels >= "
                    + threshold + ". Run Preview to update counts.");
        } else {
            setStatus("Threshold mask: voxels >= " + threshold + ". Run Preview to update counts.");
        }
    }

    private void closePreviewMaps() {
        for (int i = 0; i < previewMapImages.size(); i++) {
            ImagePlus preview = previewMapImages.get(i);
            if (preview == null) continue;
            try {
                preview.changes = false;
                preview.close();
            } catch (Exception closeFailed) {
                // Preview windows may already be disposed; clearing the reference is enough.
            }
        }
        previewMapImages.clear();
    }

    private void closeThresholdPreview() {
        thresholdPreviewGeneration++;
        if (thresholdPreviewTimer != null) {
            thresholdPreviewTimer.stop();
            thresholdPreviewTimer = null;
        }
        if (thresholdPreviewWorker != null && !thresholdPreviewWorker.isDone()) {
            thresholdPreviewWorker.cancel(true);
        }
        thresholdPreviewWorker = null;
        if (thresholdPreviewImage != null) {
            try {
                thresholdPreviewImage.changes = false;
                thresholdPreviewImage.close();
            } catch (Exception closeFailed) {
                // Preview windows may already be disposed; clearing the reference is enough.
            }
            thresholdPreviewImage = null;
        }
    }

    private void releasePreviewWindows() {
        thresholdPreviewGeneration++;
        if (thresholdPreviewTimer != null) {
            thresholdPreviewTimer.stop();
            thresholdPreviewTimer = null;
        }
        if (thresholdPreviewWorker != null && !thresholdPreviewWorker.isDone()) {
            thresholdPreviewWorker.cancel(true);
        }
        thresholdPreviewWorker = null;
        for (int i = 0; i < previewMapImages.size(); i++) {
            markImageUnchanged(previewMapImages.get(i));
        }
        markImageUnchanged(thresholdPreviewImage);
        previewMapImages.clear();
        thresholdPreviewImage = null;
    }

    private JScrollPane createFilterRowsScrollPane() {
        JScrollPane scroll = new JScrollPane(filterRows) {
            @Override public Dimension getPreferredSize() {
                Dimension preferred = super.getPreferredSize();
                return new Dimension(preferred.width, Math.min(preferred.height, 320));
            }

            @Override public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        return scroll;
    }

    private void handleEngineFailure(Throwable cause, OC3DPlusDialogModel runModel) {
        IJ.log("3D Objects Counter+ failed for image '" + titleOf(targetImage)
                + "' with options \"" + safeMacroOptions(runModel) + "\": "
                + causeSummary(cause));
        String message = engineErrorMessage(cause);
        setStatus("Error: " + message);
        if (!isVisible()) {
            IJ.error("3D Objects Counter+", message);
        }
    }

    private String engineErrorMessage(Throwable cause) {
        if (targetImageMissingFromWindowManager() || looksLikeClosedImageError(cause)) {
            return "The target image is no longer open. Reopen it and run 3D Objects Counter+ again.";
        }
        if (cause instanceof OutOfMemoryError || containsOutOfMemory(cause)
                || ObjectMapBuilder.isMemoryGuardFailure(cause)) {
            return "Fiji ran out of memory. Close other images, hide unneeded maps, or increase Fiji memory.";
        }
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Could not run 3D Objects Counter+ for image '" + titleOf(targetImage) + "'."
                : message;
    }

    private boolean targetImageMissingFromWindowManager() {
        if (!targetImageManagedAtLaunch) return false;
        int[] ids = WindowManager.getIDList();
        if (ids == null) return true;
        for (int id : ids) {
            if (WindowManager.getImage(id) == targetImage) return false;
        }
        return true;
    }

    private static boolean looksLikeClosedImageError(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("closed") || lower.contains("disposed")
                        || lower.contains("stack") || lower.contains("image must contain")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsOutOfMemory(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof OutOfMemoryError
                    || current instanceof ObjectMapBuilder.OptionalMapMemoryException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void cancelActiveWorker() {
        dialogDisposed = true;
        workerGeneration++;
        SwingWorker<?, ?> worker = currentWorker;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        endBusyStatus();
    }

    private static void setTreeEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                setTreeEnabled(children[i], enabled);
            }
        }
    }

    private static boolean isManagedImage(ImagePlus image) {
        int[] ids = WindowManager.getIDList();
        if (ids == null) return false;
        for (int id : ids) {
            if (WindowManager.getImage(id) == image) return true;
        }
        return false;
    }

    private static int clampToSlider(int value, int sliderMin, int sliderMax) {
        if (value < sliderMin) return sliderMin;
        return value > sliderMax ? sliderMax : value;
    }

    private static void setNativeScrollbarSize(Scrollbar scrollbar) {
        if (scrollbar == null) return;
        scrollbar.setPreferredSize(NATIVE_SCROLLBAR_SIZE);
        scrollbar.setMinimumSize(NATIVE_SCROLLBAR_SIZE);
    }

    private static void keepInputWidthToColumns(JTextField field) {
        if (field == null) return;
        Dimension preferred = field.getPreferredSize();
        field.setPreferredSize(preferred);
        field.setMinimumSize(preferred);
        field.setMaximumSize(preferred);
    }

    private static int scrollbarMaximumFor(int inclusiveMaximum) {
        return inclusiveMaximum >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : inclusiveMaximum + 1;
    }

    private int currentTargetSlice() {
        if (targetImage == null) return 1;
        return clampToSlider(targetImage.getZ(), 1, targetSliceCount(targetImage));
    }

    private static int targetSliceCount(ImagePlus image) {
        return image == null ? 1 : Math.max(1, image.getNSlices());
    }

    private void setTargetSlice(int slice) {
        if (targetImage == null) return;
        int safeSlice = clampToSlider(slice, 1, targetSliceCount(targetImage));
        int channel = Math.max(1, targetImage.getC());
        int frame = Math.max(1, targetImage.getT());
        targetImage.setPosition(channel, safeSlice, frame);
    }

    private static int defaultMaxSize(ImagePlus image) {
        if (image == null) return Integer.MAX_VALUE;
        long width = Math.max(1, image.getWidth());
        long height = Math.max(1, image.getHeight());
        long slices = Math.max(1, image.getNSlices());
        long voxels = width * height * slices;
        return voxels >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) voxels;
    }

    private static String formatMaxSize(int maxSize) {
        return maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize);
    }

    private static void discardImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static void discardResultImages(OC3DPlusResult result) {
        if (result == null) return;
        discardImage(result.labelImage());
    }

    static ImagePlus snapshotForInteractiveProcessing(ImagePlus image) {
        return ImageOps.processingSnapshot(image);
    }

    private static ProcessingImages createProcessingImages(ImagePlus target,
                                                          ImagePlus redirect) {
        ImagePlus targetSnapshot = null;
        ImagePlus redirectSnapshot = null;
        try {
            targetSnapshot = snapshotForInteractiveProcessing(target);
            if (redirect != null) {
                redirectSnapshot = redirect == target
                        ? targetSnapshot
                        : snapshotForInteractiveProcessing(redirect);
            }
            return new ProcessingImages(targetSnapshot, redirectSnapshot);
        } catch (RuntimeException e) {
            discardProcessingImages(targetSnapshot, redirectSnapshot);
            throw e;
        } catch (Error e) {
            discardProcessingImages(targetSnapshot, redirectSnapshot);
            throw e;
        }
    }

    private static void discardProcessingImages(ImagePlus targetSnapshot,
                                                ImagePlus redirectSnapshot) {
        if (redirectSnapshot != null && redirectSnapshot != targetSnapshot) {
            discardImage(redirectSnapshot);
        }
        discardImage(targetSnapshot);
    }

    private static void markImageUnchanged(ImagePlus image) {
        if (image != null) {
            image.changes = false;
        }
    }

    private static String safeMacroOptions(OC3DPlusDialogModel runModel) {
        if (runModel == null) return "";
        try {
            return runModel.toMacroOptions();
        } catch (RuntimeException optionsUnavailable) {
            return "<unavailable: " + optionsUnavailable.getMessage() + ">";
        }
    }

    private static String causeSummary(Throwable cause) {
        if (cause == null) return "unknown error";
        StringBuilder sb = new StringBuilder();
        Throwable current = cause;
        while (current != null) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                sb.append(": ").append(message);
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private static String titleOf(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

    private static final class EngineRunOutput {
        final OC3DPlusResult result;
        final List<ImagePlus> maps = new ArrayList<ImagePlus>();
        final List<MapBuildWarning> mapWarnings = new ArrayList<MapBuildWarning>();

        EngineRunOutput(OC3DPlusResult result) {
            this.result = result;
        }
    }

    private static final class MapBuildWarning {
        final String mapName;
        final Throwable cause;

        MapBuildWarning(String mapName, Throwable cause) {
            this.mapName = mapName == null ? "Output" : mapName;
            this.cause = cause;
        }
    }

    private static final class ProcessingImages {
        final ImagePlus target;
        final ImagePlus redirect;

        ProcessingImages(ImagePlus target, ImagePlus redirect) {
            this.target = target;
            this.redirect = redirect;
        }

        void discard() {
            discardProcessingImages(target, redirect);
        }
    }

    @Override
    public void dispose() {
        cancelActiveWorker();
        clearTargetThresholdDisplay();
        releasePreviewWindows();
        super.dispose();
    }

    /** Convenience entry point used by the plugin entry. */
    public static void showAndRun(final ImagePlus target, final OkHandler okHandler) {
        Runnable showDialog = new Runnable() {
            @Override public void run() {
                Frame owner = WindowManager.getFrame("ImageJ") instanceof Frame
                        ? (Frame) WindowManager.getFrame("ImageJ") : null;
                OC3DPlusDialog dialog = new OC3DPlusDialog(owner, target, okHandler);
                dialog.setVisible(true);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            showDialog.run();
        } else {
            SwingUtilities.invokeLater(showDialog);
        }
    }
}

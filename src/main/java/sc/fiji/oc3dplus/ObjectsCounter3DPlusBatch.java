package sc.fiji.oc3dplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.batch.BatchFileDiscovery;
import sc.fiji.oc3dplus.batch.OC3DPlusBatchParameters;
import sc.fiji.oc3dplus.batch.OC3DPlusBatchRunner;
import sc.fiji.oc3dplus.ui.OC3DPlusDialog;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ProgressMonitor;
import javax.swing.Timer;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.List;

/** Separate folder batch command for 3D Objects Counter+. */
public final class ObjectsCounter3DPlusBatch implements PlugIn {

    @Override
    public void run(String arg) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.error("3D Objects Counter+ Batch",
                    "The folder batch command needs the Fiji desktop folder chooser.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser(
                "Choose folder of TIFF images for 3D Objects Counter+ Batch");
        String selected = chooser.getDirectory();
        if (selected == null || selected.trim().isEmpty()) return;
        final File inputDirectory = new File(selected);
        final File outputRoot = new File(inputDirectory, "3D Objects Counter Plus Batch");
        final List<File> files;
        try {
            files = BatchFileDiscovery.discover(inputDirectory, true, outputRoot);
        } catch (Exception failure) {
            IJ.error("3D Objects Counter+ Batch",
                    "Could not scan the selected folder.\n" + messageOf(failure));
            return;
        }
        if (files.isEmpty()) {
            IJ.error("3D Objects Counter+ Batch",
                    "No .tif or .tiff images were found in:\n"
                            + inputDirectory.getAbsolutePath());
            return;
        }

        IJ.log("3D Objects Counter+ Batch discovered " + files.size() + " image(s):");
        for (int i = 0; i < files.size(); i++) {
            IJ.log("  " + relativePath(inputDirectory, files.get(i)));
        }

        final ImagePlus sample = firstReadable(files);
        if (sample == null) {
            IJ.error("3D Objects Counter+ Batch",
                    "None of the discovered TIFF images could be opened by Fiji.");
            return;
        }
        sample.setTitle("[Batch settings sample] " + sample.getTitle());
        sample.show();

        Runnable showSettings = new Runnable() {
            @Override public void run() {
                final boolean[] accepted = {false};
                Frame owner = WindowManager.getFrame("ImageJ") instanceof Frame
                        ? (Frame) WindowManager.getFrame("ImageJ") : null;
                OC3DPlusDialogModel initial = defaultModel(sample);
                final OC3DPlusDialog dialog = new OC3DPlusDialog(
                        owner, sample, new OC3DPlusDialog.OkHandler() {
                    @Override public void onOk(final OC3DPlusDialogModel model,
                                               OC3DPlusResult ignored) {
                        accepted[0] = true;
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override public void run() {
                                discard(sample);
                                startBatch(new OC3DPlusBatchParameters(
                                        inputDirectory, files, model));
                            }
                        });
                    }
                }, initial, true);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent e) {
                        if (!accepted[0]) discard(sample);
                    }
                });
                dialog.setVisible(true);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            showSettings.run();
        } else {
            SwingUtilities.invokeLater(showSettings);
        }
    }

    private static void startBatch(final OC3DPlusBatchParameters parameters) {
        final ProgressMonitor monitor = new ProgressMonitor(
                null,
                "3D Objects Counter+ Batch",
                "Starting...",
                0,
                Math.max(1, parameters.inputFiles.size() + 1));
        monitor.setMillisToDecideToPopup(0);
        monitor.setMillisToPopup(0);
        final SwingWorker<?, ?>[] workerReference = new SwingWorker<?, ?>[1];
        final Timer cancellationPoll = new Timer(200, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                SwingWorker<?, ?> active = workerReference[0];
                if (monitor.isCanceled() && active != null && !active.isDone()) {
                    active.cancel(true);
                }
            }
        });
        SwingWorker<OC3DPlusBatchRunner.Result, Void> worker =
                new SwingWorker<OC3DPlusBatchRunner.Result, Void>() {
            @Override protected OC3DPlusBatchRunner.Result doInBackground() throws Exception {
                return OC3DPlusBatchRunner.run(parameters,
                        new OC3DPlusBatchRunner.ProgressListener() {
                    @Override public void progress(int completed,
                                                   int total,
                                                   String relativePath) {
                        if (monitor.isCanceled()) {
                            Thread.currentThread().interrupt();
                        }
                        IJ.showProgress(completed, Math.max(1, total));
                        IJ.showStatus("3D Objects Counter+ Batch "
                                + Math.min(completed + 1, total) + "/" + total
                                + ": " + relativePath);
                        final int value = Math.min(completed, total);
                        final String note = relativePath;
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override public void run() {
                                monitor.setProgress(value);
                                monitor.setNote(note);
                            }
                        });
                    }
                });
            }

            @Override protected void done() {
                cancellationPoll.stop();
                monitor.close();
                IJ.showProgress(1.0);
                try {
                    OC3DPlusBatchRunner.Result result = get();
                    IJ.showStatus("3D Objects Counter+ Batch complete");
                    IJ.showMessage("3D Objects Counter+ Batch",
                            "Complete.\n\nImages: " + result.inputCount
                                    + "\nSuccessful: " + result.successfulCount
                                    + "\nFailed: " + result.failedCount
                                    + "\nObjects: " + result.objectCount
                                    + "\n\nCSV folder:\n"
                                    + result.outputDirectory.getAbsolutePath());
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null
                            ? failure : failure.getCause();
                    if (cause instanceof InterruptedIOException
                            || isCancellation(cause)) {
                        IJ.showStatus("3D Objects Counter+ Batch cancelled");
                        IJ.showMessage("3D Objects Counter+ Batch",
                                "Cancelled. The output folder is marked .incomplete; "
                                        + "do not treat any files in it as a completed batch.");
                        return;
                    }
                    IJ.error("3D Objects Counter+ Batch",
                            "Batch processing failed.\n" + messageOf(
                                    cause));
                }
            }
        };
        workerReference[0] = worker;
        cancellationPoll.start();
        worker.execute();
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException
                    || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static OC3DPlusDialogModel defaultModel(ImagePlus sample) {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.configureForImage(sample);
        int center = Math.max(1, (Math.max(1, sample.getNSlices()) + 1) / 2);
        sample.setPosition(Math.max(1, sample.getC()), center, Math.max(1, sample.getT()));
        try {
            model.threshold = sample.getProcessor().getAutoThreshold();
        } catch (RuntimeException unavailable) {
            model.threshold = 128;
        }
        long voxels = (long) Math.max(1, sample.getWidth())
                * Math.max(1, sample.getHeight()) * Math.max(1, sample.getNSlices());
        model.maxSize = voxels >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) voxels;
        model.showLabels = false;
        model.showSurfaces = false;
        model.showCentroids = false;
        model.showCentersOfMass = false;
        model.showStats = false;
        model.showSummary = false;
        model.redirectTitle = "";
        return model;
    }

    private static ImagePlus firstReadable(List<File> files) {
        for (int i = 0; i < files.size(); i++) {
            ImagePlus image = IJ.openImage(files.get(i).getAbsolutePath());
            if (image != null) return image;
        }
        return null;
    }

    private static String relativePath(File root, File file) {
        try {
            return root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
        } catch (RuntimeException outsideRoot) {
            return file.getName();
        }
    }

    private static void discard(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) return "Unknown error";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}

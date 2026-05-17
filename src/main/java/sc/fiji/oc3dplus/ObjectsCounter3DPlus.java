package sc.fiji.oc3dplus;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import sc.fiji.oc3dplus.api.MorphPredicate;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ObjectMapBuilder;
import sc.fiji.oc3dplus.engine.SummaryReporter;
import sc.fiji.oc3dplus.ui.OC3DPlusDialog;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.awt.GraphicsEnvironment;

/**
 * Fiji plugin entry point. Registered in {@code plugins.config} at
 * {@code Analyze > 3D Objects Counter+}.
 *
 * <p>Two execution paths:
 * <ul>
 *   <li><b>Headless / macro</b>: when {@link Macro#getOptions()} returns a
 *       non-null options string (or we're running headless), parse the
 *       options via {@link MacroOptionsParser}, invoke
 *       {@link OC3DPlus#count}, and display the result. In non-headless
 *       runs, statistics and selected map images are shown unless hidden
 *       by macro flags. The Swing dialog is never shown.</li>
 *   <li><b>Interactive</b>: otherwise, show the dialog for the user to
 *       configure parameters. Preview shows temporary selected maps; OK shows
 *       the final statistics table and selected maps according to the dialog
 *       checkboxes.</li>
 * </ul>
 */
public class ObjectsCounter3DPlus implements PlugIn {

    /**
     * Execute the plugin against the current ImageJ image. This method reads
     * the active image from {@link WindowManager}, may show a Swing dialog,
     * may show image/table windows, records interactive OK runs when the macro
     * recorder is active, and reports warnings/errors through ImageJ log or
     * error dialogs.
     */
    @Override
    public void run(String arg) {
        String options = Macro.getOptions();
        boolean headless = GraphicsEnvironment.isHeadless();

        ImagePlus current = WindowManager.getCurrentImage();
        if (current == null) {
            IJ.error("3D Objects Counter+",
                    "No active image (image=null). Open a 3D stack before running this command.");
            return;
        }

        if (options != null || headless) {
            runFromOptions(current, options == null ? "" : options);
        } else {
            OC3DPlusDialog.showAndRun(current, new OC3DPlusDialog.OkHandler() {
                @Override public void onOk(OC3DPlusDialogModel model, OC3DPlusResult result) {
                    if (Recorder.record) {
                        Recorder.recordString("run(\"3D Objects Counter+\", \""
                                + model.toMacroOptions() + "\");\n");
                    }
                }
            });
        }
    }

    private static void runFromOptions(ImagePlus image, String options) {
        try {
            runFromOptionsChecked(image, options);
        } catch (RuntimeException e) {
            IJ.error("3D Objects Counter+", "Could not run 3D Objects Counter+.\n"
                    + "Image: " + imageTitle(image) + "\n"
                    + "Options: " + (options == null ? "null" : options) + "\n"
                    + "Error: " + throwableMessage(e));
        }
    }

    private static void runFromOptionsChecked(ImagePlus image, String options) {
        MacroOptionsParser.Parsed parsed = MacroOptionsParser.parse(options);

        ImagePlus redirect = null;
        if (parsed.redirectTitle != null && !parsed.redirectTitle.isEmpty()) {
            redirect = WindowManager.getImage(parsed.redirectTitle);
            if (redirect == null) {
                IJ.log("3D Objects Counter+: redirect image '" + parsed.redirectTitle
                        + "' not open for image '" + imageTitle(image)
                        + "' with options \"" + options
                        + "\"; continuing without intensity redirect.");
            }
        }

        OC3DPlus.Builder builder = OC3DPlus.builder()
                .threshold(parsed.threshold)
                .minSize(parsed.minSize)
                .maxSize(parsed.maxSize)
                .excludeOnEdges(parsed.excludeOnEdges)
                .intensityImage(redirect)
                .warningSink(new OC3DPlusParameters.WarningSink() {
                    @Override public void warn(String message) {
                        IJ.log(message);
                    }
                });
        for (MorphPredicate p : parsed.filters) {
            builder.addFilter(p);
        }

        OC3DPlusResult result = OC3DPlus.count(image, builder.build());

        if (parsed.showStats && result.statistics() != null) {
            result.statistics().show("Results for " + image.getTitle());
        }
        if (parsed.showSummary) {
            SummaryReporter.log(image.getTitle(),
                    redirect == null ? null : redirect.getTitle(), result,
                    parsed.minSize, parsed.maxSize, parsed.threshold);
        }
        showSelectedMaps(image.getTitle(), result, parsed.showLabels,
                parsed.showSurfaces, parsed.showCentroids, parsed.showCentersOfMass);

        recordIfNeeded(parsed);
    }

    private static void showSelectedMaps(String sourceTitle,
                                         OC3DPlusResult result,
                                         boolean showObjects,
                                         boolean showSurfaces,
                                         boolean showCentroids,
                                         boolean showCentersOfMass) {
        if (result == null || result.labelImage() == null) return;
        if (showObjects) {
            showGeneratedMap("Objects", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.objectMapInPlace(result.labelImage(),
                            result.statistics(), sourceTitle);
                }
            });
        }
        if (showSurfaces) {
            showGeneratedMap("Surfaces", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.surfaceMap(result.labelImage(),
                            result.statistics(), sourceTitle);
                }
            });
        }
        if (showCentroids) {
            showGeneratedMap("Centroids", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.centroidMap(result.labelImage(),
                            result.statistics(), sourceTitle);
                }
            });
        }
        if (showCentersOfMass) {
            showGeneratedMap("Centers of mass", new MapFactory() {
                @Override public ImagePlus create() {
                    return ObjectMapBuilder.centerOfMassMap(result.labelImage(),
                            result.statistics(), sourceTitle);
                }
            });
        }
    }

    private static void showMap(ImagePlus map) {
        if (map == null) return;
        logOverlaySkipped(map);
        map.show();
    }

    private static void logOverlaySkipped(ImagePlus map) {
        String reason = ObjectMapBuilder.overlaySkippedReason(map);
        if (reason != null && !reason.isEmpty()) {
            IJ.log("3D Objects Counter+ " + reason + " Map pixels and statistics are unchanged.");
        }
    }

    private interface MapFactory {
        ImagePlus create();
    }

    private static void showGeneratedMap(String mapName, MapFactory factory) {
        try {
            showMap(factory == null ? null : factory.create());
        } catch (ObjectMapBuilder.OptionalMapMemoryException lowMemory) {
            IJ.log("3D Objects Counter+ skipped " + mapName + " map: "
                    + throwableMessage(lowMemory) + ". Close other images or hide unneeded maps.");
            System.gc();
        } catch (OutOfMemoryError oom) {
            IJ.log("3D Objects Counter+ skipped " + mapName + " map: not enough memory. "
                    + "Close other images or hide unneeded maps.");
            System.gc();
        } catch (RuntimeException mapFailed) {
            IJ.log("3D Objects Counter+ skipped " + mapName + " map: "
                    + throwableMessage(mapFailed));
        }
    }

    private static void recordIfNeeded(MacroOptionsParser.Parsed parsed) {
        if (!Recorder.record || Macro.getOptions() != null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Recorder.recordOption("threshold", String.valueOf(parsed.threshold));
        Recorder.recordOption("min", String.valueOf(parsed.minSize));
        Recorder.recordOption("max",
                parsed.maxSize == Integer.MAX_VALUE ? "Infinity" : String.valueOf(parsed.maxSize));
        if (parsed.excludeOnEdges) Recorder.recordOption("exclude_edges");
        if (parsed.redirectTitle != null && !parsed.redirectTitle.isEmpty()) {
            Recorder.recordOption("redirect", "[" + parsed.redirectTitle + "]");
        }
        for (int i = 0; i < parsed.filters.size(); i++) {
            Recorder.recordOption(parsed.filters.get(i).format());
        }
        if (!parsed.showLabels) Recorder.recordOption("hide_labels");
        if (!parsed.showSurfaces) Recorder.recordOption("hide_surfaces");
        if (!parsed.showCentroids) Recorder.recordOption("hide_centroids");
        if (!parsed.showCentersOfMass) Recorder.recordOption("hide_centers_of_mass");
        if (!parsed.showStats) Recorder.recordOption("hide_stats");
        if (!parsed.showSummary) Recorder.recordOption("hide_summary");
    }

    private static String imageTitle(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

    private static String throwableMessage(Throwable cause) {
        if (cause == null) return "unknown error";
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cause.getClass().getName();
        }
        return message;
    }
}

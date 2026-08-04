package sc.fiji.oc3dplus.batch;

import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable parameters for one streamed folder batch. */
public final class OC3DPlusBatchParameters {

    public final File inputDirectory;
    public final List<File> inputFiles;
    private final OC3DPlusDialogModel settings;

    public OC3DPlusBatchParameters(File inputDirectory,
                                   List<File> inputFiles,
                                   OC3DPlusDialogModel settings) {
        if (inputDirectory == null || !inputDirectory.isDirectory()) {
            throw new IllegalArgumentException("inputDirectory must be an existing directory.");
        }
        if (inputFiles == null) {
            throw new IllegalArgumentException("inputFiles must not be null.");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null.");
        }
        this.inputDirectory = inputDirectory.getAbsoluteFile();
        this.inputFiles = Collections.unmodifiableList(new ArrayList<File>(inputFiles));
        this.settings = settings.snapshot();
        this.settings.redirectTitle = "";
    }

    /** Returns a defensive settings copy so this parameter object stays immutable. */
    public OC3DPlusDialogModel settings() {
        return settings.snapshot();
    }
}

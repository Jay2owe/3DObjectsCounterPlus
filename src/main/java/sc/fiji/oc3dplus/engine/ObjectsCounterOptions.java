package sc.fiji.oc3dplus.engine;

import ij.IJ;
import sc.fiji.oc3dplus.MacroOptionsParser;

/**
 * Macro-equivalent helper to configure the native 3D Objects Counter options
 * dialog. Required when callers need the legacy {@code Counter3D} path
 * to redirect intensity measurements to a specific image. The thread-safe
 * {@code runNative} path in {@link ObjectsCounter3DWrapper} does not need this.
 */
public final class ObjectsCounterOptions {

    private ObjectsCounterOptions() {}

    /** Macro {@code set_redirect_3DObjectCounter(image_title)} equivalent. */
    public static void setRedirectTo(String imageTitle) {
        String safeImageTitle = requireSafeRedirectTitle(imageTitle);
        String args = "volume surface nb_of_obj._voxels nb_of_surf._voxels integrated_density mean_gray_value "
                + "std_dev_gray_value median_gray_value minimum_gray_value maximum_gray_value centroid "
                + "mean_distance_to_surface std_dev_distance_to_surface median_distance_to_surface centre_of_mass "
                + "bounding_box show_masked_image_(redirection_requiered) dots_size=5 font_size=10 show_numbers "
                + "white_numbers store_results_within_a_table_named_after_the_image_(macro_friendly) "
                + "redirect_to=[" + safeImageTitle + "]";
        IJ.run("3D OC Options", args);
    }

    static String requireSafeRedirectTitle(String imageTitle) {
        if (imageTitle == null || imageTitle.trim().isEmpty()) {
            throw new IllegalArgumentException("Redirect image title must not be blank "
                    + "(imageTitle='" + imageTitle + "').");
        }
        return MacroOptionsParser.requireSafeBracketedValue(imageTitle, "Redirect image title");
    }
}

package sc.fiji.oc3dplus.equivalence;

import org.junit.Test;
import sc.fiji.oc3dplus.MacroOptionsParser;
import sc.fiji.oc3dplus.api.MorphPredicate;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * "Every option in the README table parses to the same parameters" (harness
 * section 4), asserted as a round-trip through the recorder's own output:
 * model to macro options to parsed options must be a fixed point.
 *
 * <p>This is the option surface the migration must not disturb. It is checked
 * independently of the corpus because the sweep does not exercise the display
 * flags, and those are options users write in macros too.
 */
public class MacroRoundTripTest {

    @Test
    public void everyOptionSurvivesTheRoundTrip() {
        List<OC3DPlusDialogModel> models = models();
        for (int i = 0; i < models.size(); i++) {
            OC3DPlusDialogModel model = models.get(i);
            String options = model.toMacroOptions();
            MacroOptionsParser.Parsed parsed = MacroOptionsParser.parse(options);
            String context = "options='" + options + "'";

            assertEquals(context + " threshold", model.threshold, parsed.threshold);
            assertEquals(context + " min", model.minSize, parsed.minSize);
            assertEquals(context + " max", model.maxSize, parsed.maxSize);
            assertEquals(context + " exclude_edges", model.excludeOnEdges, parsed.excludeOnEdges);
            assertEquals(context + " hide_labels", model.showLabels, parsed.showLabels);
            assertEquals(context + " hide_surfaces", model.showSurfaces, parsed.showSurfaces);
            assertEquals(context + " hide_centroids", model.showCentroids, parsed.showCentroids);
            assertEquals(context + " hide_centers_of_mass",
                    model.showCentersOfMass, parsed.showCentersOfMass);
            assertEquals(context + " hide_stats", model.showStats, parsed.showStats);
            assertEquals(context + " hide_summary", model.showSummary, parsed.showSummary);
            assertEquals(context + " measure_fractal_xy",
                    model.measureFractalXY, parsed.measureFractalXY);
            assertEquals(context + " measure_composites",
                    model.measureComposites, parsed.measureComposites);
            assertEquals(context + " measure_arborization",
                    model.measureArborization, parsed.measureArborization);
            assertEquals(context + " redirect",
                    model.redirectTitle.isEmpty() ? null : model.redirectTitle,
                    parsed.redirectTitle);
            assertEquals(context + " filters", formatted(model), formattedPredicates(parsed.filters));
        }
    }

    /** Re-parsing the recorder's output must not change it. */
    @Test
    public void macroOptionsAreAFixedPoint() {
        List<OC3DPlusDialogModel> models = models();
        for (int i = 0; i < models.size(); i++) {
            OC3DPlusDialogModel model = models.get(i);
            String first = model.toMacroOptions();
            MacroOptionsParser.Parsed parsed = MacroOptionsParser.parse(first);
            OC3DPlusDialogModel rebuilt = new OC3DPlusDialogModel();
            rebuilt.threshold = parsed.threshold;
            rebuilt.minSize = parsed.minSize;
            rebuilt.maxSize = parsed.maxSize;
            rebuilt.excludeOnEdges = parsed.excludeOnEdges;
            rebuilt.showLabels = parsed.showLabels;
            rebuilt.showSurfaces = parsed.showSurfaces;
            rebuilt.showCentroids = parsed.showCentroids;
            rebuilt.showCentersOfMass = parsed.showCentersOfMass;
            rebuilt.showStats = parsed.showStats;
            rebuilt.showSummary = parsed.showSummary;
            rebuilt.measureFractalXY = parsed.measureFractalXY;
            rebuilt.measureComposites = parsed.measureComposites;
            rebuilt.measureArborization = parsed.measureArborization;
            rebuilt.redirectTitle = parsed.redirectTitle == null ? "" : parsed.redirectTitle;
            for (int f = 0; f < parsed.filters.size(); f++) {
                MorphPredicate predicate = parsed.filters.get(f);
                rebuilt.addFilter(new OC3DPlusDialogModel.FilterRow(
                        predicate.featureName, predicate.op.symbol(), predicate.value, true));
            }
            assertEquals("macro options are not a fixed point", first, rebuilt.toMacroOptions());
        }
    }

    private static List<OC3DPlusDialogModel> models() {
        List<OC3DPlusDialogModel> out = new ArrayList<OC3DPlusDialogModel>();
        out.add(new OC3DPlusDialogModel());

        OC3DPlusDialogModel bounded = new OC3DPlusDialogModel();
        bounded.threshold = 77;
        bounded.minSize = 3;
        bounded.maxSize = 4096;
        out.add(bounded);

        OC3DPlusDialogModel edges = new OC3DPlusDialogModel();
        edges.excludeOnEdges = true;
        out.add(edges);

        OC3DPlusDialogModel hidden = new OC3DPlusDialogModel();
        hidden.showLabels = false;
        hidden.showSurfaces = false;
        hidden.showCentroids = false;
        hidden.showCentersOfMass = false;
        hidden.showStats = false;
        hidden.showSummary = false;
        out.add(hidden);

        OC3DPlusDialogModel extended = new OC3DPlusDialogModel();
        extended.measureFractalXY = true;
        extended.measureComposites = true;
        extended.measureArborization = true;
        out.add(extended);

        OC3DPlusDialogModel redirected = new OC3DPlusDialogModel();
        redirected.redirectTitle = "raw stack.tif";
        out.add(redirected);

        OC3DPlusDialogModel filtered = new OC3DPlusDialogModel();
        filtered.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", 0.6, true));
        filtered.addFilter(new OC3DPlusDialogModel.FilterRow("volume", "<=", 4000.0, true));
        filtered.addFilter(new OC3DPlusDialogModel.FilterRow("elongation", ">", 1.5, true));
        filtered.addFilter(new OC3DPlusDialogModel.FilterRow("feret_diameter_max", "<", 90.0, true));
        out.add(filtered);

        OC3DPlusDialogModel everything = new OC3DPlusDialogModel();
        everything.threshold = 1;
        everything.minSize = 0;
        everything.maxSize = 123456;
        everything.excludeOnEdges = true;
        everything.showLabels = false;
        everything.showSummary = false;
        everything.measureFractalXY = true;
        everything.measureComposites = true;
        everything.measureArborization = true;
        everything.redirectTitle = "intensity.tif";
        everything.addFilter(new OC3DPlusDialogModel.FilterRow("compactness", ">=", 0.25, true));
        out.add(everything);
        return out;
    }

    private static String formatted(OC3DPlusDialogModel model) {
        List<String> out = new ArrayList<String>();
        List<OC3DPlusDialogModel.FilterRow> rows = model.filters();
        for (int i = 0; i < rows.size(); i++) {
            OC3DPlusDialogModel.FilterRow row = rows.get(i);
            if (!row.enabled) continue;
            out.add(row.feature + row.operator + row.value);
        }
        return ColumnContract.join(out, " ");
    }

    private static String formattedPredicates(List<MorphPredicate> predicates) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < predicates.size(); i++) {
            out.add(predicates.get(i).format());
        }
        return ColumnContract.join(out, " ");
    }
}

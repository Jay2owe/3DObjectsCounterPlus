package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import org.junit.Test;
import sc.fiji.oc3dplus.MacroOptionsParser;
import sc.fiji.oc3dplus.api.MorphPredicate;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * "Headless output byte-identical to GUI output" (harness section 9), in the form
 * that is actually assertable without a display.
 *
 * <p>The GUI does not measure anything itself: {@code OC3DPlusDialog} reads and
 * writes through {@code OC3DPlusDialogModel}, and the model produces both the
 * engine parameters and the recorded macro-options string. So the honest test of
 * GUI/headless parity is that the two routes out of one model - direct
 * {@code toParameters}, and {@code toMacroOptions} parsed back by the plugin
 * entry point - drive the engine to identical output, digests included.
 */
public class HeadlessGuiParityTest {

    @Test
    public void guiParametersAndParsedMacroOptionsAgree() {
        String[] fixtures = {"blobs-8bit", "blobs-16bit", "sphere-aniso-z5", "u-shape"};
        List<OC3DPlusDialogModel> models = models();
        for (int f = 0; f < fixtures.length; f++) {
            for (int m = 0; m < models.size(); m++) {
                OC3DPlusDialogModel model = models.get(m);
                String viaGui = run(fixtures[f], model.toParameters(null, null));
                String viaMacro = run(fixtures[f], parametersFrom(model.toMacroOptions()));
                assertEquals("fixture=" + fixtures[f] + " options='" + model.toMacroOptions() + "'",
                        viaGui, viaMacro);
            }
        }
    }

    private static List<OC3DPlusDialogModel> models() {
        List<OC3DPlusDialogModel> out = new ArrayList<OC3DPlusDialogModel>();

        OC3DPlusDialogModel plain = new OC3DPlusDialogModel();
        plain.threshold = 100;
        plain.minSize = 1;
        out.add(plain);

        OC3DPlusDialogModel edges = new OC3DPlusDialogModel();
        edges.threshold = 100;
        edges.minSize = 1;
        edges.excludeOnEdges = true;
        out.add(edges);

        OC3DPlusDialogModel bounded = new OC3DPlusDialogModel();
        bounded.threshold = 100;
        bounded.minSize = 8;
        bounded.maxSize = 100;
        out.add(bounded);

        OC3DPlusDialogModel extended = new OC3DPlusDialogModel();
        extended.threshold = 100;
        extended.minSize = 1;
        extended.measureFractalXY = true;
        extended.measureComposites = true;
        out.add(extended);

        OC3DPlusDialogModel filtered = new OC3DPlusDialogModel();
        filtered.threshold = 100;
        filtered.minSize = 1;
        filtered.addFilter(new OC3DPlusDialogModel.FilterRow("volume", ">=", 30.0, true));
        out.add(filtered);
        return out;
    }

    private static OC3DPlusParameters parametersFrom(String macroOptions) {
        MacroOptionsParser.Parsed parsed = MacroOptionsParser.parse(macroOptions);
        OC3DPlus.Builder builder = OC3DPlus.builder()
                .threshold(parsed.threshold)
                .minSize(parsed.minSize)
                .maxSize(parsed.maxSize)
                .excludeOnEdges(parsed.excludeOnEdges)
                .measureFractalXY(parsed.measureFractalXY)
                .measureCompositeIndices(parsed.measureComposites)
                .measureArborization(parsed.measureArborization);
        for (int i = 0; i < parsed.filters.size(); i++) {
            MorphPredicate predicate = parsed.filters.get(i);
            builder.addFilter(predicate);
        }
        return builder.build();
    }

    /** Statistics plus label digest, so this compares measured values and not just counts. */
    private static String run(String fixtureName, OC3DPlusParameters parameters) {
        ImagePlus input = FixtureCorpus.byName(fixtureName).createInput();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, parameters);
            labels = result.labelImage();
            StringBuilder out = new StringBuilder();
            out.append("objects=").append(result.objectCount()).append('\n');
            out.append("labels=").append(ImageDigest.labelDigest(labels)).append('\n');
            ResultsTable statistics = result.statistics();
            String[] headings = statistics.getHeadings();
            for (int h = 0; h < headings.length; h++) {
                out.append(headings[h]).append(':');
                for (int row = 0; row < statistics.size(); row++) {
                    double value = Double.NaN;
                    try {
                        value = statistics.getValue(headings[h], row);
                    } catch (RuntimeException unreadable) {
                        value = Double.NaN;
                    }
                    out.append(' ').append(CaptureRecord.number(value));
                }
                out.append('\n');
            }
            return out.toString();
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }
}

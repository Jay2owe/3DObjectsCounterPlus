package sc.fiji.oc3dplus.ui;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.MacroOptionsParser;
import sc.fiji.oc3dplus.api.MorphPredicate;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OC3DPlusDialogModelTest {

    @Test
    public void defaultModelValidates() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        List<String> errors = model.validate();
        assertTrue("default model should be valid: " + errors, errors.isEmpty());
    }

    /**
     * A plain 3D stack has one channel and one frame, so there is nothing to pin
     * and the sentinel stays. That is what keeps every macro string the plugin has
     * ever recorded byte-identical.
     */
    @Test
    public void aPlainStackKeepsTheCurrentPositionSentinel() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        ImagePlus stack = new ImagePlus("plain", new ByteProcessor(4, 4));
        model.configureForImage(stack);

        assertFalse("a plain stack is not a hyperstack",
                OC3DPlusDialogModel.isHyperstack(stack));
        assertEquals(OC3DPlusParameters.USE_CURRENT_POSITION, model.channel);
        assertEquals(OC3DPlusParameters.USE_CURRENT_POSITION, model.frame);
        assertFalse(model.toMacroOptions().contains("channel="));
        assertFalse(model.toMacroOptions().contains("frame="));
    }

    /**
     * On a hyperstack the answer depends on which channel and frame are measured,
     * so the model records the position rather than deferring to whatever the next
     * image happens to be displaying when a recorded macro replays.
     */
    @Test
    public void aHyperstackPinsTheDisplayedPosition() {
        ij.ImageStack planes = new ij.ImageStack(4, 4);
        for (int i = 0; i < 12; i++) planes.addSlice(new ByteProcessor(4, 4));
        ImagePlus hyperstack = new ImagePlus("hyper", planes);
        hyperstack.setDimensions(2, 3, 2);
        hyperstack.setOpenAsHyperStack(true);
        hyperstack.setPosition(2, 2, 2);

        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.configureForImage(hyperstack);

        assertTrue(OC3DPlusDialogModel.isHyperstack(hyperstack));
        assertEquals(2, model.channel);
        assertEquals(2, model.frame);
        assertTrue(model.toMacroOptions().contains("channel=2"));
        assertTrue(model.toMacroOptions().contains("frame=2"));

        OC3DPlusParameters parameters = model.toParameters(null, null);
        assertEquals(2, parameters.channel);
        assertEquals(2, parameters.frame);
    }

    @Test
    public void snapshotCarriesThePosition() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.channel = 3;
        model.frame = 5;
        OC3DPlusDialogModel copy = model.snapshot();
        assertEquals(3, copy.channel);
        assertEquals(5, copy.frame);
    }

    @Test
    public void disabledExtendedFamilyDoesNotValidateHiddenRangeText() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.extendedFeatureRanges().get(0).minText = "abc";

        model.measureFractalXY = false;
        assertTrue(model.validate().isEmpty());

        model.measureFractalXY = true;
        assertFalse(model.validate().isEmpty());
    }

    @Test
    public void extendedRangeCanDriveLiveUseSettingsValidity() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        OC3DPlusDialogModel.FeatureRange dimension =
                model.extendedFeatureRanges().get(0);
        OC3DPlusDialogModel.FeatureRange fit =
                model.extendedFeatureRanges().get(1);

        assertTrue(dimension.accepts("-Infinity", "Infinity"));
        assertFalse(dimension.accepts("abc", "Infinity"));
        assertFalse(dimension.accepts("2", "1"));
        assertTrue(fit.accepts("0", "1"));
        assertFalse(fit.accepts("-0.1", "1"));
        assertFalse(fit.accepts("0", "1.1"));
    }

    @Test
    public void compositeLabelsMatchTheirDocumentedDefinitions() {
        assertTrue(hasExtendedLabel(modelWithDefaults(),
                "Surface roughness index (SRI)"));
        assertTrue(hasExtendedLabel(modelWithDefaults(), "Process burden (PB)"));
        assertTrue(hasExtendedLabel(modelWithDefaults(),
                "Volume-span discrepancy (VSD)"));
    }

    @Test
    public void rejectsMaxBelowMin() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.minSize = 100;
        model.maxSize = 50;
        List<String> errors = model.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Max size"));
    }

    @Test
    public void rejectsNegativeMin() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.minSize = -1;
        assertFalse(model.validate().isEmpty());
    }

    @Test
    public void rejectsFilterWithBlankFeature() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.addFilter(new OC3DPlusDialogModel.FilterRow("", ">=", 0.5, true));
        List<String> errors = model.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("feature"));
    }

    @Test
    public void rejectsFilterWithUnknownOperator() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", "==", 0.5, true));
        List<String> errors = model.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("operator"));
    }

    @Test
    public void disabledFiltersAreNotValidated() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.addFilter(new OC3DPlusDialogModel.FilterRow("", ">=", 0.5, false));
        assertTrue("disabled filters are skipped", model.validate().isEmpty());
    }

    @Test
    public void rejectsNonFiniteFilterValues() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", Double.NaN, true));
        model.addFilter(new OC3DPlusDialogModel.FilterRow("volume", "<=", Double.POSITIVE_INFINITY, true));

        List<String> errors = model.validate();
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("finite number"));
        assertTrue(errors.get(1).contains("finite number"));
    }

    @Test
    public void rejectsUnsafeRedirectTitleForMacroRecording() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.redirectTitle = "raw] hide_stats";

        List<String> errors = model.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Redirect image title"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void toMacroOptionsFailsClosedOnUnsafeRedirectTitle() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.redirectTitle = "raw\" hide_stats";

        model.toMacroOptions();
    }

    @Test
    public void enabledPredicatesExcludesDisabledRows() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", 0.6, true));
        model.addFilter(new OC3DPlusDialogModel.FilterRow("volume", ">=", 100, false));
        model.addFilter(new OC3DPlusDialogModel.FilterRow("elongation", "<", 2, true));

        List<MorphPredicate> ps = model.enabledPredicates();
        assertEquals(2, ps.size());
        assertEquals("sphericity", ps.get(0).featureName);
        assertEquals("elongation", ps.get(1).featureName);
    }

    @Test
    public void defaultFeatureRangesDoNotEmitPredicates() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();

        assertEquals(7, model.featureRanges().size());
        assertEquals(0, model.enabledPredicates().size());
        assertFalse(model.toMacroOptions().contains("filter1="));
        assertFalse(model.toMacroOptions().contains("sphericity>="));
    }

    @Test
    public void calibratedVolumeRangeOnlyAppearsForRealImageUnits() {
        OC3DPlusDialogModel uncalibrated = new OC3DPlusDialogModel();
        uncalibrated.configureForImage(new ImagePlus("raw", new ByteProcessor(2, 2)));

        assertFalse(hasFeature(uncalibrated, "volume_calibrated"));

        ImagePlus calibratedImage = new ImagePlus("calibrated", new ByteProcessor(2, 2));
        Calibration calibration = new Calibration();
        calibration.setUnit("um");
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.5;
        calibration.pixelDepth = 2.0;
        calibratedImage.setCalibration(calibration);

        OC3DPlusDialogModel calibrated = new OC3DPlusDialogModel();
        calibrated.configureForImage(calibratedImage);
        String unit = OC3DPlusDialogModel.calibratedVolumeUnit(calibratedImage);

        assertTrue(hasFeature(calibrated, "volume_calibrated"));
        assertNotNull(unit);
        assertTrue(hasLabel(calibrated, "Volume (" + unit + "^3)"));
    }

    @Test
    public void tightenedFeatureRangesEmitMinAndMaxPredicates() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.featureRanges().get(0).minText = "0.6";
        model.featureRanges().get(0).maxText = "0.95";
        model.featureRanges().get(2).minText = "1.5";

        List<MorphPredicate> ps = model.enabledPredicates();

        assertEquals(3, ps.size());
        assertEquals("sphericity", ps.get(0).featureName);
        assertEquals(MorphPredicate.Operator.GE, ps.get(0).op);
        assertEquals(0.6, ps.get(0).value, 1e-12);
        assertEquals(MorphPredicate.Operator.LE, ps.get(1).op);
        assertEquals(0.95, ps.get(1).value, 1e-12);
        assertEquals("elongation", ps.get(2).featureName);
        assertEquals(MorphPredicate.Operator.GE, ps.get(2).op);
        assertEquals(1.5, ps.get(2).value, 1e-12);
    }

    @Test
    public void validatesObjectiveRangeBounds() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.featureRanges().get(0).minText = "-0.1";
        model.featureRanges().get(1).maxText = "2";
        model.featureRanges().get(2).minText = "0.5";

        List<String> errors = model.validate();

        assertEquals(3, errors.size());
        assertTrue(errors.get(0).contains("Sphericity"));
        assertTrue(errors.get(1).contains("Compactness"));
        assertTrue(errors.get(2).contains("Elongation"));
    }

    @Test
    public void toMacroOptionsRoundTripsThroughParser() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 128;
        model.minSize = 20;
        model.maxSize = 50_000;
        model.excludeOnEdges = true;
        model.showLabels = false;
        model.showSurfaces = false;
        model.showCentroids = false;
        model.showCentersOfMass = false;
        model.showSummary = false;
        model.redirectTitle = "raw.tif";
        model.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", 0.6, true));

        String options = model.toMacroOptions();
        assertTrue(options.contains("threshold=128"));
        assertTrue(options.contains("min=20"));
        assertTrue(options.contains("max=50000"));
        assertTrue(options.contains("exclude_edges"));
        assertTrue(options.contains("hide_labels"));
        assertTrue(options.contains("hide_surfaces"));
        assertTrue(options.contains("hide_centroids"));
        assertTrue(options.contains("hide_centers_of_mass"));
        assertTrue(options.contains("hide_summary"));
        assertTrue(options.contains("redirect=[raw.tif]"));
        assertTrue(options.contains("sphericity>=0.6"));
        assertFalse(options.contains("filter1="));

        assertEquals(1, MacroOptionsParser.parse(options).filters.size());
    }

    @Test
    public void toMacroOptionsEmitsInfinityForUnboundedMax() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.maxSize = Integer.MAX_VALUE;
        assertTrue(model.toMacroOptions().contains("max=Infinity"));
    }

    @Test
    public void toMacroOptionsSkipsDisabledFilters() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", 0.6, true));
        model.addFilter(new OC3DPlusDialogModel.FilterRow("volume", ">=", 100, false));
        model.addFilter(new OC3DPlusDialogModel.FilterRow("elongation", "<", 2, true));

        String opts = model.toMacroOptions();
        assertTrue(opts.contains("sphericity>=0.6"));
        assertTrue(opts.contains("elongation<2.0"));
        assertFalse(opts.contains("filter1="));
        assertFalse(opts.contains("filter2="));
        assertFalse("disabled volume filter must not appear",
                opts.contains("volume>=100"));
    }

    @Test
    public void toParametersIncludesEnabledFiltersOnly() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 64;
        model.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", 0.6, true));
        model.addFilter(new OC3DPlusDialogModel.FilterRow("volume", ">=", 100, false));

        OC3DPlusParameters params = model.toParameters(null, null);
        assertEquals(64, params.threshold);
        assertEquals(1, params.morphPredicates.size());
        assertEquals("sphericity", params.morphPredicates.get(0).featureName);
    }

    @Test
    public void copyFromIsDefensive() {
        OC3DPlusDialogModel source = new OC3DPlusDialogModel();
        source.threshold = 200;
        source.addFilter(new OC3DPlusDialogModel.FilterRow("sphericity", ">=", 0.6, true));

        OC3DPlusDialogModel snapshot = source.snapshot();
        source.threshold = 99;
        source.filters().get(0).value = 0.99;
        source.filters().clear();

        assertEquals(200, snapshot.threshold);
        assertTrue(snapshot.showSurfaces);
        assertTrue(snapshot.showCentroids);
        assertTrue(snapshot.showCentersOfMass);
        assertTrue(snapshot.showSummary);
        assertEquals("0", snapshot.featureRanges().get(0).minText);
        assertEquals(1, snapshot.filters().size());
        assertEquals(0.6, snapshot.filters().get(0).value, 1e-12);
    }

    @Test
    public void featureAndOperatorOptionsAreStable() {
        assertNotNull(OC3DPlusDialogModel.featureOptions());
        assertTrue(OC3DPlusDialogModel.featureOptions().contains("sphericity"));
        assertEquals(4, OC3DPlusDialogModel.operatorOptions().size());
    }

    private static boolean hasFeature(OC3DPlusDialogModel model, String feature) {
        for (OC3DPlusDialogModel.FeatureRange range : model.featureRanges()) {
            if (feature.equals(range.feature)) return true;
        }
        return false;
    }

    private static boolean hasLabel(OC3DPlusDialogModel model, String label) {
        for (OC3DPlusDialogModel.FeatureRange range : model.featureRanges()) {
            if (label.equals(range.label)) return true;
        }
        return false;
    }

    private static OC3DPlusDialogModel modelWithDefaults() {
        return new OC3DPlusDialogModel();
    }

    private static boolean hasExtendedLabel(OC3DPlusDialogModel model, String label) {
        for (OC3DPlusDialogModel.FeatureRange range : model.extendedFeatureRanges()) {
            if (label.equals(range.label)) return true;
        }
        return false;
    }
}

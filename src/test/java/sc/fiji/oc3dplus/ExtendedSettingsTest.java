package sc.fiji.oc3dplus;

import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExtendedSettingsTest {

    @Test
    public void macroFlagsRoundTripAndBuilderDefaultsRemainOff() {
        MacroOptionsParser.Parsed parsed = MacroOptionsParser.parse(
                "measure_fractal_xy measure_composites measure_arborization");
        assertTrue(parsed.measureFractalXY);
        assertTrue(parsed.measureComposites);
        assertTrue(parsed.measureArborization);

        OC3DPlusParameters defaults = OC3DPlus.builder().build();
        assertFalse(defaults.measurements.anyEnabled());
    }

    @Test
    public void dialogSnapshotCopiesExtendedSettingsAndMacroOptions() {
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.measureFractalXY = true;
        model.measureComposites = true;
        model.extendedFeatureRanges().get(0).minText = "1.2";

        OC3DPlusDialogModel snapshot = model.snapshot();
        assertTrue(snapshot.measureFractalXY);
        assertTrue(snapshot.measureComposites);
        assertTrue(snapshot.toMacroOptions().contains("measure_fractal_xy"));
        assertTrue(snapshot.toMacroOptions().contains("fractal_dim_xy>=1.2"));
    }
}

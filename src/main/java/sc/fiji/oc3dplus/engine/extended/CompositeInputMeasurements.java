package sc.fiji.oc3dplus.engine.extended;

import ij.ImagePlus;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.geom2.measurements.MeasureAbstract;
import mcib3d.geom2.measurements.MeasureDistancesCenter;
import mcib3d.geom2.measurements.MeasureEllipsoid;
import mcib3d.geom2.measurements.MeasureFeret;
import mcib3d.geom2.measurements.MeasureVolume;
import mcib3d.image3d.ImageHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Obtains the existing mcib3d scalar measurements needed by the dependency-free
 * composite formula class. mcib3d-core is already a required Fiji dependency.
 */
public final class CompositeInputMeasurements {

    private CompositeInputMeasurements() {}

    public static Map<Integer, Inputs> measure(ImagePlus labelImage) {
        if (labelImage == null || labelImage.getStack() == null) {
            return Collections.emptyMap();
        }
        Map<Integer, Inputs> out = new LinkedHashMap<Integer, Inputs>();
        try {
            Objects3DIntPopulation population =
                    new Objects3DIntPopulation(ImageHandler.wrap(labelImage));
            List<Object3DInt> objects = population.getObjects3DInt();
            for (int i = 0; i < objects.size(); i++) {
                Object3DInt object = objects.get(i);
                if (object == null) continue;
                int label = positiveLabel(object.getLabel());
                if (label <= 0) continue;
                Inputs inputs = new Inputs();
                try {
                    MeasureDistancesCenter distances = new MeasureDistancesCenter(object);
                    inputs.distanceMean = value(distances,
                            MeasureDistancesCenter.DIST_CENTER_AVG_UNIT);
                    inputs.distanceStandardDeviation = value(distances,
                            MeasureDistancesCenter.DIST_CENTER_SD_UNIT);
                } catch (RuntimeException unavailable) {
                    // Degenerate objects retain NaN for the affected composite.
                }
                try {
                    MeasureEllipsoid ellipsoid = new MeasureEllipsoid(object);
                    inputs.spareness = value(ellipsoid, MeasureEllipsoid.ELL_SPARENESS);
                    inputs.elongation = value(ellipsoid, MeasureEllipsoid.ELL_ELONGATION);
                    inputs.flatness = value(ellipsoid, MeasureEllipsoid.ELL_FLATNESS);
                } catch (RuntimeException unavailable) {
                    // Degenerate objects retain NaN for the affected composites.
                }
                try {
                    inputs.feretDiameter = value(new MeasureFeret(object), MeasureFeret.FERET_UNIT);
                } catch (RuntimeException unavailable) {
                    // Keep VSD unavailable.
                }
                try {
                    inputs.volume = value(new MeasureVolume(object), MeasureVolume.VOLUME_UNIT);
                } catch (RuntimeException unavailable) {
                    // Keep VSD unavailable.
                }
                out.put(Integer.valueOf(label), inputs);
            }
        } catch (RuntimeException unavailable) {
            return Collections.emptyMap();
        }
        return out;
    }

    private static int positiveLabel(float value) {
        return Float.isFinite(value) && value > 0f && value <= Integer.MAX_VALUE
                ? Math.round(value) : 0;
    }

    private static double value(MeasureAbstract measure, String key) {
        Double measured = measure == null || key == null
                ? null : measure.getValueMeasurement(key);
        return measured != null && Double.isFinite(measured.doubleValue())
                ? measured.doubleValue() : Double.NaN;
    }

    public static final class Inputs {
        public double distanceMean = Double.NaN;
        public double distanceStandardDeviation = Double.NaN;
        public double spareness = Double.NaN;
        public double elongation = Double.NaN;
        public double flatness = Double.NaN;
        public double feretDiameter = Double.NaN;
        public double volume = Double.NaN;
    }
}

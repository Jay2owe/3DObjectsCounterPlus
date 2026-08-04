package sc.fiji.oc3dplus.api;

/**
 * Immutable selection of optional, relatively expensive measurements.
 *
 * <p>All options are disabled by default so existing callers keep the same
 * result columns and performance profile.
 */
public final class OC3DPlusMeasurements {

    public static final OC3DPlusMeasurements NONE =
            new OC3DPlusMeasurements(false, false, false);

    public final boolean fractalXY;
    public final boolean compositeIndices;
    public final boolean arborization;

    public OC3DPlusMeasurements(boolean fractalXY,
                                boolean compositeIndices,
                                boolean arborization) {
        this.fractalXY = fractalXY;
        this.compositeIndices = compositeIndices;
        this.arborization = arborization;
    }

    public boolean anyEnabled() {
        return fractalXY || compositeIndices || arborization;
    }

    public OC3DPlusMeasurements withFractalXY(boolean enabled) {
        return new OC3DPlusMeasurements(enabled, compositeIndices, arborization);
    }

    public OC3DPlusMeasurements withCompositeIndices(boolean enabled) {
        return new OC3DPlusMeasurements(fractalXY, enabled, arborization);
    }

    public OC3DPlusMeasurements withArborization(boolean enabled) {
        return new OC3DPlusMeasurements(fractalXY, compositeIndices, enabled);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OC3DPlusMeasurements)) return false;
        OC3DPlusMeasurements that = (OC3DPlusMeasurements) other;
        return fractalXY == that.fractalXY
                && compositeIndices == that.compositeIndices
                && arborization == that.arborization;
    }

    @Override
    public int hashCode() {
        int result = fractalXY ? 1 : 0;
        result = 31 * result + (compositeIndices ? 1 : 0);
        result = 31 * result + (arborization ? 1 : 0);
        return result;
    }
}

package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;

/**
 * One named, deterministic synthetic input. Fixtures are generated, never
 * committed as binaries, so the corpus is reproducible from source.
 *
 * <p>A fixture declares which harness case it exercises. The declaration is not
 * taken on trust: {@link ReferenceMatrixTest} asserts that Case A fixtures
 * really do take the classic path on the current build, and Case B fixtures
 * really do not.
 */
public abstract class Fixture {

    /**
     * How much of the configuration sweep a fixture gets. Reduced sweeps exist
     * only for fixtures whose size makes the full sweep disproportionately
     * expensive; which fixtures those are, and what was therefore not run, is
     * printed by the capture so a reduced sweep can never read as full coverage.
     */
    public enum Sweep {
        /** Every configuration. */
        FULL,
        /** Detection-shape configurations only; no extended measurement groups. */
        BASIC,
        /** The default configuration alone. */
        MINIMAL
    }

    public final String name;
    public final HarnessCase harnessCase;
    public final Sweep sweep;

    protected Fixture(String name, HarnessCase harnessCase, Sweep sweep) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("fixture name must not be empty");
        }
        this.name = name;
        this.harnessCase = harnessCase;
        this.sweep = sweep;
    }

    /**
     * The primary input. For Cases A and B this is an intensity stack to be
     * thresholded; for Case C it is an already-labelled image.
     */
    public abstract ImagePlus createInput();

    /**
     * Optional redirect target for intensity measurements. Must match the input
     * in width, height and slice count or the engine ignores it.
     */
    public ImagePlus createRedirectImage() {
        ImagePlus input = createInput();
        try {
            return Stacks.gradient(input.getWidth(), input.getHeight(),
                    input.getStack().getSize());
        } finally {
            Stacks.discard(input);
        }
    }

    /** Free-text note carried into the golden, e.g. why a fixture is expected to fail. */
    public String note() {
        return "";
    }

    @Override public String toString() {
        return name + " (case " + harnessCase + ", sweep " + sweep + ")";
    }
}

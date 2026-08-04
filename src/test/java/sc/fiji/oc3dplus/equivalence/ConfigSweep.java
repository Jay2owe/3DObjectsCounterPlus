package sc.fiji.oc3dplus.equivalence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The configuration sweep. Harness section 6 asks for thresholds spanning empty
 * to all-foreground, min/max size, edge exclusion on and off, redirect on and
 * off, each extended measurement group on and off, and each map on and off.
 *
 * <p>Reduced sweeps exist for fixtures whose size makes the full sweep
 * disproportionately expensive. What a reduced sweep leaves out is reported by
 * the capture rather than left implicit, so partial coverage never reads as
 * total coverage.
 */
public final class ConfigSweep {

    private ConfigSweep() {}

    /** Foreground of every fixture is {@link Stacks#FOREGROUND}, so 100 selects it and 201 selects nothing. */
    private static final int THRESHOLD_SELECTS_FOREGROUND = 100;
    private static final int THRESHOLD_SELECTS_EVERYTHING = 0;
    private static final int THRESHOLD_SELECTS_NOTHING = 201;

    public static List<RunConfig> forFixture(Fixture fixture) {
        if (fixture.harnessCase == HarnessCase.C) return caseC();
        if (fixture.sweep == Fixture.Sweep.MINIMAL) return minimal();
        if (fixture.sweep == Fixture.Sweep.BASIC) return basic();
        return full();
    }

    /** What a reduced sweep omits, for the capture report. */
    public static List<String> omittedBy(Fixture.Sweep sweep) {
        List<String> omitted = new ArrayList<String>();
        if (sweep == Fixture.Sweep.FULL) return omitted;
        List<RunConfig> included = sweep == Fixture.Sweep.BASIC ? basic() : minimal();
        List<RunConfig> everything = full();
        for (int i = 0; i < everything.size(); i++) {
            String name = everything.get(i).name;
            boolean present = false;
            for (int j = 0; j < included.size(); j++) {
                if (included.get(j).name.equals(name)) present = true;
            }
            if (!present) omitted.add(name);
        }
        return omitted;
    }

    public static List<RunConfig> full() {
        List<RunConfig> out = new ArrayList<RunConfig>();
        out.add(defaultConfig());
        out.add(RunConfig.named("thr-all-foreground")
                .threshold(THRESHOLD_SELECTS_EVERYTHING).build());
        out.add(RunConfig.named("thr-empty")
                .threshold(THRESHOLD_SELECTS_NOTHING).build());
        out.add(RunConfig.named("edges-on")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).excludeOnEdges().build());
        out.add(RunConfig.named("minsize-8")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).minSize(8).build());
        out.add(RunConfig.named("maxsize-30")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).maxSize(30).build());
        out.add(RunConfig.named("size-window-8-100")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).minSize(8).maxSize(100).build());
        out.add(RunConfig.named("redirect-on")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).redirect().build());
        out.add(RunConfig.named("maps-on")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).maps().build());
        out.add(RunConfig.named("filter-volume")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).filter("volume", ">=", 8.0).build());
        out.add(RunConfig.named("filter-shape")
                .threshold(THRESHOLD_SELECTS_FOREGROUND)
                .filter("sphericity", ">=", 0.0)
                .filter("compactness", ">=", 0.0)
                .filter("elongation", ">=", 1.0)
                .filter("feret_diameter_max", ">=", 0.0)
                .build());
        out.add(RunConfig.named("ext-fractal")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).fractal().build());
        out.add(RunConfig.named("ext-composites")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).composites().build());
        out.add(RunConfig.named("ext-arborization")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).arborization().build());
        out.add(RunConfig.named("ext-all-with-maps")
                .threshold(THRESHOLD_SELECTS_FOREGROUND)
                .fractal().composites().arborization().maps().redirect()
                .build());
        return Collections.unmodifiableList(out);
    }

    public static List<RunConfig> basic() {
        List<RunConfig> out = new ArrayList<RunConfig>();
        out.add(defaultConfig());
        out.add(RunConfig.named("thr-empty").threshold(THRESHOLD_SELECTS_NOTHING).build());
        out.add(RunConfig.named("edges-on")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).excludeOnEdges().build());
        out.add(RunConfig.named("minsize-8")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).minSize(8).build());
        out.add(RunConfig.named("redirect-on")
                .threshold(THRESHOLD_SELECTS_FOREGROUND).redirect().build());
        return Collections.unmodifiableList(out);
    }

    public static List<RunConfig> minimal() {
        return Collections.singletonList(defaultConfig());
    }

    /**
     * Case C enters through {@code ObjectsCounter3DWrapper.fromLabelImage},
     * which takes no threshold, no edge exclusion and no morphology filters -
     * only size bounds, a redirect target and the map flags.
     */
    public static List<RunConfig> caseC() {
        List<RunConfig> out = new ArrayList<RunConfig>();
        out.add(RunConfig.named("default").build());
        out.add(RunConfig.named("minsize-8").minSize(8).build());
        out.add(RunConfig.named("maxsize-30").maxSize(30).build());
        out.add(RunConfig.named("size-window-8-100").minSize(8).maxSize(100).build());
        out.add(RunConfig.named("redirect-on").redirect().build());
        out.add(RunConfig.named("maps-on").maps().build());
        return Collections.unmodifiableList(out);
    }

    private static RunConfig defaultConfig() {
        return RunConfig.named("default").threshold(THRESHOLD_SELECTS_FOREGROUND).build();
    }
}

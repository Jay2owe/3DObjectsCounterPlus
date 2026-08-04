package sc.fiji.oc3dplus.equivalence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One point of the configuration sweep (harness section 6). */
public final class RunConfig {

    /** A single morphology predicate expressed the way the macro grammar does. */
    public static final class Filter {
        public final String feature;
        public final String operator;
        public final double value;

        Filter(String feature, String operator, double value) {
            this.feature = feature;
            this.operator = operator;
            this.value = value;
        }

        public String describe() {
            return feature + operator + value;
        }
    }

    public final String name;
    public final int threshold;
    public final int minSize;
    public final int maxSize;
    public final boolean excludeOnEdges;
    public final boolean redirect;
    public final boolean maps;
    public final boolean fractal;
    public final boolean composites;
    public final boolean arborization;
    public final List<Filter> filters;

    private RunConfig(Builder builder) {
        this.name = builder.name;
        this.threshold = builder.threshold;
        this.minSize = builder.minSize;
        this.maxSize = builder.maxSize;
        this.excludeOnEdges = builder.excludeOnEdges;
        this.redirect = builder.redirect;
        this.maps = builder.maps;
        this.fractal = builder.fractal;
        this.composites = builder.composites;
        this.arborization = builder.arborization;
        this.filters = Collections.unmodifiableList(new ArrayList<Filter>(builder.filters));
    }

    static Builder named(String name) {
        return new Builder(name);
    }

    /** Key-ordered description written into the golden so a config is self-documenting. */
    public List<String> describe() {
        List<String> lines = new ArrayList<String>();
        lines.add("config.threshold=" + threshold);
        lines.add("config.minSize=" + minSize);
        lines.add("config.maxSize=" + (maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize)));
        lines.add("config.excludeOnEdges=" + excludeOnEdges);
        lines.add("config.redirect=" + redirect);
        lines.add("config.maps=" + maps);
        lines.add("config.fractal=" + fractal);
        lines.add("config.composites=" + composites);
        lines.add("config.arborization=" + arborization);
        List<String> described = new ArrayList<String>();
        for (int i = 0; i < filters.size(); i++) {
            described.add(filters.get(i).describe());
        }
        lines.add("config.filters=" + ColumnContract.join(described, " "));
        return lines;
    }

    static final class Builder {
        private final String name;
        private int threshold = 100;
        private int minSize = 1;
        private int maxSize = Integer.MAX_VALUE;
        private boolean excludeOnEdges;
        private boolean redirect;
        private boolean maps;
        private boolean fractal;
        private boolean composites;
        private boolean arborization;
        private final List<Filter> filters = new ArrayList<Filter>();

        private Builder(String name) {
            this.name = name;
        }

        Builder threshold(int value) {
            this.threshold = value;
            return this;
        }

        Builder minSize(int value) {
            this.minSize = value;
            return this;
        }

        Builder maxSize(int value) {
            this.maxSize = value;
            return this;
        }

        Builder excludeOnEdges() {
            this.excludeOnEdges = true;
            return this;
        }

        Builder redirect() {
            this.redirect = true;
            return this;
        }

        Builder maps() {
            this.maps = true;
            return this;
        }

        Builder fractal() {
            this.fractal = true;
            return this;
        }

        Builder composites() {
            this.composites = true;
            return this;
        }

        Builder arborization() {
            this.arborization = true;
            return this;
        }

        Builder filter(String feature, String operator, double value) {
            filters.add(new Filter(feature, operator, value));
            return this;
        }

        RunConfig build() {
            return new RunConfig(this);
        }
    }
}

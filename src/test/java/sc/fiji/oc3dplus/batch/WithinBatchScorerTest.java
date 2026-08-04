package sc.fiji.oc3dplus.batch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WithinBatchScorerTest {

    private static final double EPSILON = 1e-12;

    @Test
    public void excludesNonFiniteValuesAndUsesPopulationStandardDeviation() {
        double[] values = {
                1.0, 2.0, 3.0,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };

        WithinBatchScorer.Result result = WithinBatchScorer.score(values);
        double[] z = result.zScores();
        double[] percentile = result.percentiles();

        assertEquals(3, result.finiteCount());
        assertEquals(-Math.sqrt(1.5), z[0], EPSILON);
        assertEquals(0.0, z[1], EPSILON);
        assertEquals(Math.sqrt(1.5), z[2], EPSILON);
        assertEquals(100.0 / 6.0, percentile[0], EPSILON);
        assertEquals(50.0, percentile[1], EPSILON);
        assertEquals(500.0 / 6.0, percentile[2], EPSILON);
        for (int i = 3; i < values.length; i++) {
            assertTrue(Double.isNaN(z[i]));
            assertTrue(Double.isNaN(percentile[i]));
        }
    }

    @Test
    public void calculatesEmpiricalMidrankPercentilesForTies() {
        WithinBatchScorer.Result result =
                WithinBatchScorer.score(new double[]{1.0, 1.0, 3.0, 5.0});

        assertEquals(25.0, result.percentiles()[0], EPSILON);
        assertEquals(25.0, result.percentiles()[1], EPSILON);
        assertEquals(62.5, result.percentiles()[2], EPSILON);
        assertEquals(87.5, result.percentiles()[3], EPSILON);
    }

    @Test
    public void fewerThanThreeFiniteValuesMakesBothScoresUnavailable() {
        WithinBatchScorer.Result result =
                WithinBatchScorer.score(new double[]{1.0, Double.NaN, 2.0});

        assertEquals(2, result.finiteCount());
        for (double value : result.zScores()) {
            assertTrue(Double.isNaN(value));
        }
        for (double value : result.percentiles()) {
            assertTrue(Double.isNaN(value));
        }
    }

    @Test
    public void constantPopulationHasPercentilesButNoZScores() {
        WithinBatchScorer.Result result =
                WithinBatchScorer.score(new double[]{4.0, 4.0, 4.0, 4.0});

        for (double value : result.zScores()) {
            assertTrue(Double.isNaN(value));
        }
        for (double value : result.percentiles()) {
            assertEquals(50.0, value, EPSILON);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullValues() {
        WithinBatchScorer.score(null);
    }
}

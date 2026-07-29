package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU reference checks for the weighted-reservoir identity implemented in lighting.slang. */
final class RestirReservoirMathTest {
    @Test
    void mergedReservoirCarriesTheSourceEffectiveSampleCount() {
        Reservoir destination = new Reservoir(8.0, 12.0, 2.0);

        // A finalized source represents M samples, not one sample. Its current-receiver weight is
        // pHat(current) * W(source) * M(source): 3 * 0.5 * 16 = 24.
        merge(destination, 16.0, 0.5, 3.0, 0.0, 160.0);

        assertEquals(24.0, destination.m, 1.0e-12);
        assertEquals(36.0, destination.weightSum, 1.0e-12);
        assertEquals(3.0, destination.selectedTarget, 1.0e-12);
        double finalW = destination.weightSum / (destination.m * destination.selectedTarget);
        assertEquals(destination.weightSum / destination.m,
                finalW * destination.selectedTarget, 1.0e-12);
    }

    @Test
    void historyMIsClampedAndSelectionMatchesResamplingWeights() {
        Reservoir capped = new Reservoir(8.0, 1.0, 1.0);
        for (int i = 0; i < 20; i++) {
            merge(capped, 100.0, 1.0, 1.0, 0.5, 160.0);
        }
        assertEquals(160.0, capped.m, 0.0);

        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(0x5eedL);
        int sourceWins = 0;
        int trials = 200_000;
        for (int i = 0; i < trials; i++) {
            // Existing stream weight 2, incoming source weight 6 -> survivor probability 6 / 8.
            Reservoir r = new Reservoir(1.0, 2.0, 2.0);
            merge(r, 1.0, 2.0, 3.0, random.nextDouble(), 20.0);
            if (r.selectedTarget == 3.0) {
                sourceWins++;
            }
        }
        assertTrue(Math.abs(sourceWins / (double) trials - 0.75) < 0.005);
    }

    private static void merge(Reservoir destination, double sourceM, double sourceW,
                              double targetAtCurrentReceiver, double uniformRandom, double maxM) {
        double acceptedM = Math.min(sourceM, Math.max(0.0, maxM - destination.m));
        if (acceptedM <= 0.0 || sourceW <= 0.0) {
            return;
        }
        double weight = targetAtCurrentReceiver * sourceW * acceptedM;
        destination.m += acceptedM;
        if (weight <= 0.0) {
            return;
        }
        destination.weightSum += weight;
        if (uniformRandom * destination.weightSum < weight) {
            destination.selectedTarget = targetAtCurrentReceiver;
        }
    }

    private static final class Reservoir {
        private double m;
        private double weightSum;
        private double selectedTarget;

        private Reservoir(double m, double weightSum, double selectedTarget) {
            this.m = m;
            this.weightSum = weightSum;
            this.selectedTarget = selectedTarget;
        }
    }
}

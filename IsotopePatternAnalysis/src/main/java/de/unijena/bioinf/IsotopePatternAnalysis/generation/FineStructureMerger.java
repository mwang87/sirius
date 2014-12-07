package de.unijena.bioinf.IsotopePatternAnalysis.generation;

import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleMutableSpectrum;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;
import gnu.trove.list.array.TDoubleArrayList;

import static java.lang.Math.*;

/**
 * Created by kaidu on 17.11.14.
 */
public class FineStructureMerger {

    private final double resolution;

    public FineStructureMerger(double resolution) {
        this.resolution = resolution;
    }

    private static final double step(double x, double[] xs, double[] ints, double sigma) {
        return f1(x, xs, ints, sigma) / f2(x, xs, ints, sigma);
    }

    private static final double f0(double x, double[] xs, double[] ints, double sigma) {
        double v = 0d;
        for (int i = 0; i < xs.length; ++i) {
            v += ints[i] * norm(x, xs[i], sigma);
        }
        return v;
    }

    private static final double f1(double x, double[] xs, double[] ints, double sigma) {
        double v = 0d;
        for (int i = 0; i < xs.length; ++i) {
            v += ints[i] * norm1(x, xs[i], sigma);
        }
        return v;
    }

    private static final double f2(double x, double[] xs, double[] ints, double sigma) {
        double v = 0d;
        for (int i = 0; i < xs.length; ++i) {
            v += ints[i] * norm2(x, xs[i], sigma);
        }
        return v;
    }

    private final static double norm2(double x, double mu, double sigma) {
        final double c = 1d / (sigma * sigma);
        return c * (c * (x - mu) * (x - mu) - 1) * norm(x, mu, sigma);
    }

    private final static double norm1(double x, double mu, double sigma) {
        return -((x - mu) / (sigma * sigma)) * (norm(x, mu, sigma));
    }

    private static double norm(double x, double mu, double sigma) {
        return 1d / (sigma * sqrt(2d * PI)) * exp((-1d / 2d) * Math.pow((x - mu) / sigma, 2));
    }

    public SimpleSpectrum merge(FinestructureGenerator.Iterator iter, double monoMass) {
        final TDoubleArrayList[] masses = new TDoubleArrayList[8];
        final TDoubleArrayList[] intensities = new TDoubleArrayList[8];
        while (iter.hasNext()) {
            iter.next();
            final double mz = iter.getMass();
            final double intens = iter.getAbundance();
            final int nominal = (int) (Math.round(mz - monoMass));
            if (nominal >= masses.length) continue;
            if (masses[nominal] == null) {
                masses[nominal] = new TDoubleArrayList();
                intensities[nominal] = new TDoubleArrayList();
            }
            masses[nominal].add(mz);
            intensities[nominal].add(intens);
        }
        final SimpleMutableSpectrum peaks = new SimpleMutableSpectrum(masses.length);
        for (int k = 0; k < masses.length; ++k) {
            if (masses[k] != null) {
                final double[] mz = masses[k].toArray();
                final double[] ints = intensities[k].toArray();
                addByNewton(mz, ints, peaks);
            }
        }
        return new SimpleSpectrum(peaks);
    }

    public void addByNewton(double[] normalDistributions, double[] intensities, SimpleMutableSpectrum spec) {
        int basePeak = 0;
        for (int k = 0; k < normalDistributions.length; ++k) {
            if (intensities[k] > intensities[basePeak]) {
                basePeak = k;
            }
        }
        final double sd = 2.35482 * (normalDistributions[basePeak] / resolution);
        double x = normalDistributions[basePeak];
        for (int i = 0; i < 100; ++i) {
            final double x2 = step(x, normalDistributions, intensities, sd);
            if (Math.abs(x2) < 1e-10) {
                break;
            } else x -= x2;
        }
        spec.addPeak(x, f0(x, normalDistributions, intensities, sd));
    }

}

package de.unijena.bioinf.ChemistryBase.math;

import static java.lang.Math.pow;

/**
 * A distribution which is uniform from a to b and pareto distributed from b to infinity
 */
public class PartialParetoDistribution implements DensityFunction {

    private final double a, b, k, norm, opt, kdivbnorm;

    /**
     * @param a minimal allowed value. P(x < a) = 0
     * @param b interval [a,b) is optimal, For all x with a < x < b, density(x) is maximal
     * @param k shape parameter of underlying pareto distribution
     */
    public PartialParetoDistribution(double a, double b, double k) {
        this.a = a;
        this.b = b;
        this.k = k;
        final ParetoDistribution pareto = new ParetoDistribution(k, b);
        final double opt = pareto.getDensity(b);
        final double square = (b-a)*opt;
        this.norm = 1d/(square+1d);
        this.opt = opt*this.norm;
        this.kdivbnorm = (k/b)*norm;
    }

    @Override
    public double getDensity(double x) {
        if (x < b) {
            if (x > a) return opt;
            else return 0d;
        } else {
            return kdivbnorm * pow(b / x, k + 1);
        }
    }
}

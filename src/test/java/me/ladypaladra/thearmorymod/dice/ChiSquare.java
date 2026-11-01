package me.ladypaladra.thearmorymod.dice;

/**
 * Pearson's chi-square test of counts against an even spread, and its p-value.
 *
 * <p>The p-value is the regularised upper incomplete gamma function Q(k/2, x/2), by its series below a + 1 and by
 * Lentz's continued fraction above, the textbook pair (Numerical Recipes, chapter 6.2).</p>
 */
final class ChiSquare {

    private ChiSquare() {
    }

    /**
     * The chance of a spread at least this uneven from a fair die.
     */
    static double pEven(long[] counts) {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        double expected = (double) total / counts.length;
        double statistic = 0.0;
        for (long c : counts) {
            double d = c - expected;
            statistic += d * d / expected;
        }
        return upperGamma((counts.length - 1) / 2.0, statistic / 2.0);
    }

    static double upperGamma(double a, double x) {
        if (x <= 0.0) {
            return 1.0;
        }
        if (x < a + 1.0) {
            double sum = 1.0 / a;
            double term = sum;
            for (int n = 1; n < 10_000; n++) {
                term *= x / (a + n);
                sum += term;
                if (Math.abs(term) < Math.abs(sum) * 1.0E-15) {
                    break;
                }
            }
            return 1.0 - sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
        }
        double tiny = 1.0E-300;
        double b = x + 1.0 - a;
        double c = 1.0 / tiny;
        double d = 1.0 / b;
        double h = d;
        for (int i = 1; i < 10_000; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = b + an / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < 1.0E-15) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /**
     * Lanczos' approximation, good to about 15 digits for the arguments used here.
     */
    static double logGamma(double x) {
        double[] g = {
                0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                1.5056327351493116e-7
        };
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1.0 - x);
        }
        x -= 1.0;
        double a = g[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) {
            a += g[i] / (x + i);
        }
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }
}

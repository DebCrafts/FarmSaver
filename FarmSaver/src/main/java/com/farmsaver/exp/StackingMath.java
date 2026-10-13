package com.farmsaver.exp;

/** Pure stacking maths (no Bukkit dependency, covered by unit tests). Multipliers are "1.25 = +25%". */
public final class StackingMath {
    private StackingMath() {}

    /**
     * @param values multipliers of the eligible items (first {@code count} entries are used)
     * @param max    upper cap for the result
     * @return the final multiplier, 1.0 when nothing applies or the result is not a sane number
     */
    public static double combine(Stacking mode, double[] values, int count, double max) {
        if (count <= 0) return 1.0;
        double r;
        switch (mode) {
            case ADDITIVE -> {
                r = 1.0;
                for (int i = 0; i < count; i++) r += values[i] - 1.0;   // 1 + sum of each item's bonus
            }
            case MULTIPLICATIVE -> {
                r = 1.0;
                for (int i = 0; i < count; i++) r *= values[i];
            }
            case FIRST -> r = values[0];
            default -> {
                r = 0;
                for (int i = 0; i < count; i++) r = Math.max(r, values[i]);
            }
        }
        if (Double.isNaN(r) || Double.isInfinite(r) || r <= 0) return 1.0;
        return Math.min(r, max);
    }
}

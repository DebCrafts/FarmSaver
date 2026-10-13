package com.farmsaver.exp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackingMathTest {
    private static final double EPS = 1e-9;

    @Test
    void noEnchantedPiecesGivesNoBonus() {
        assertEquals(1.0, StackingMath.combine(Stacking.ADDITIVE, new double[4], 0, 10), EPS);
    }

    @Test
    void singlePieceUsesOnlyItsBonus() {
        assertEquals(1.25, StackingMath.combine(Stacking.ADDITIVE, new double[]{1.25}, 1, 10), EPS);
    }

    @Test
    void fourPiecesAtOnePointTwoFiveGiveTwoX() {   // 1 + 0.25 * 4
        assertEquals(2.0, StackingMath.combine(Stacking.ADDITIVE, new double[]{1.25, 1.25, 1.25, 1.25}, 4, 10), EPS);
        assertEquals(200.0, 100 * StackingMath.combine(Stacking.ADDITIVE, new double[]{1.25, 1.25, 1.25, 1.25}, 4, 10), 1e-6);
    }

    @Test
    void mixedLevelsUseEachPiecesOwnBonus() {      // 1 + 0.25 + 0.50 + 0.25
        assertEquals(2.0, StackingMath.combine(Stacking.ADDITIVE, new double[]{1.25, 1.5, 1.25}, 3, 10), EPS);
    }

    @Test
    void resultIsCappedByMaxMultiplier() {
        assertEquals(3.0, StackingMath.combine(Stacking.ADDITIVE, new double[]{2.0, 2.0, 2.0, 2.0}, 4, 3.0), EPS);
    }

    @Test
    void otherModesAreUnchanged() {
        double[] v = {1.25, 1.5};
        assertEquals(1.5, StackingMath.combine(Stacking.HIGHEST, v, 2, 10), EPS);
        assertEquals(1.25, StackingMath.combine(Stacking.FIRST, v, 2, 10), EPS);
        assertEquals(1.875, StackingMath.combine(Stacking.MULTIPLICATIVE, v, 2, 10), EPS);
    }

    @Test
    void nonsenseValuesFallBackToNoBonus() {
        assertEquals(1.0, StackingMath.combine(Stacking.HIGHEST, new double[]{Double.NaN}, 1, 10), EPS);
        assertEquals(1.0, StackingMath.combine(Stacking.MULTIPLICATIVE, new double[]{-1.0}, 1, 10), EPS);
    }
}

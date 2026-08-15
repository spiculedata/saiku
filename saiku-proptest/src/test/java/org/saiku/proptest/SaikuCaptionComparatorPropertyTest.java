/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.util.SaikuCubeCaptionComparator;

/**
 * {@link Comparator} contract for the caption-ordering comparators used to sort cubes and dimensions
 * for display.
 *
 * <p>These matter beyond tidiness. A comparator that breaks the contract makes
 * {@code Collections.sort} throw {@code IllegalArgumentException: Comparison method violates its
 * general contract!} from TimSort — but only for certain inputs and certain list sizes, so it ships
 * green and fails in production on someone else's cube list. {@link SaikuUniqueNameComparator} is
 * already covered; these are its untested siblings.
 *
 * <p>saiku#1851: a null caption used to short-circuit to {@code 0}, which is not transitive (null
 * vs "a" is 0, null vs "b" is 0, but "a" vs "b" is not). Nulls now sort LAST, so the contract holds
 * across null-bearing input too — see {@link #theContractHoldsWhenCaptionsMayBeNull}.
 */
class SaikuCaptionComparatorPropertyTest {

    private static final Generator<String> CAPTION = fromRegex("[a-zA-Z0-9 ._-]{0,20}");

    /** Captions including the null case — the shape that broke the old comparator's transitivity. */
    private static final Generator<String> NULLABLE_CAPTION =
            CAPTION.flatMap(c -> Generators.booleans().map(isNull -> isNull ? null : c));

    private static final SaikuCubeCaptionComparator CUBES = new SaikuCubeCaptionComparator();

    private static SaikuCube cube(String caption) {
        return new SaikuCube("conn", "[" + caption + "]", caption, caption, "cat", "sch");
    }

    /** Antisymmetry: {@code signum(compare(a,b)) == -signum(compare(b,a))}. */
    @HegelTest
    void cubeCompareIsAntisymmetric(TestCase tc) {
        SaikuCube a = cube(tc.draw(CAPTION, "a"));
        SaikuCube b = cube(tc.draw(CAPTION, "b"));

        assertEquals(
                Integer.signum(CUBES.compare(a, b)),
                -Integer.signum(CUBES.compare(b, a)),
                "compare must be antisymmetric");
    }

    /** Transitivity: {@code a > b && b > c} implies {@code a > c}. */
    @HegelTest
    void cubeCompareIsTransitive(TestCase tc) {
        SaikuCube a = cube(tc.draw(CAPTION, "a"));
        SaikuCube b = cube(tc.draw(CAPTION, "b"));
        SaikuCube c = cube(tc.draw(CAPTION, "c"));

        if (CUBES.compare(a, b) > 0 && CUBES.compare(b, c) > 0) {
            assertTrue(CUBES.compare(a, c) > 0, "compare must be transitive");
        }
    }

    /** Equal captions compare equal, and unequal captions never do. */
    @HegelTest
    void cubeCompareIsConsistentWithCaptionEquality(TestCase tc) {
        String x = tc.draw(CAPTION, "x");
        String y = tc.draw(CAPTION, "y");

        assertEquals(x.equals(y), CUBES.compare(cube(x), cube(y)) == 0, "equality must agree with compare == 0");
    }

    /**
     * The end-to-end consequence: sorting a generated list must not blow up. This is the property
     * that would actually have caught a TimSort contract violation in production.
     */
    @HegelTest
    void sortingAnyCubeListSucceeds(TestCase tc) {
        List<SaikuCube> cubes = new ArrayList<>();
        int size = tc.draw(dev.hegel.Generators.integers().min(0).max(40), "size");
        for (int i = 0; i < size; i++) {
            cubes.add(cube(tc.draw(CAPTION, "caption" + i)));
        }

        cubes.sort(CUBES); // must not throw "Comparison method violates its general contract!"

        for (int i = 1; i < cubes.size(); i++) {
            assertTrue(
                    cubes.get(i - 1).getCaption().compareTo(cubes.get(i).getCaption()) <= 0,
                    "result is not ordered at index " + i);
        }
    }

    /**
     * saiku#1851: a null caption used to short-circuit to {@code 0}, which is not transitive — a
     * null compared "equal" to every caption while those captions were not equal to each other.
     * Nulls now sort LAST, which is total and transitive.
     */
    @HegelTest
    void nullCaptionsSortLastAndStayTransitive(TestCase tc) {
        String other = tc.draw(fromRegex("[a-zA-Z]{1,10}"), "other");
        String third = tc.draw(fromRegex("[a-zA-Z]{1,10}"), "third");
        tc.assume(!other.equals(third));

        SaikuCube nul = cube(null);
        SaikuCube a = cube(other);
        SaikuCube b = cube(third);

        // A null caption sorts after any real caption, consistently in both directions.
        assertTrue(CUBES.compare(nul, a) > 0, "null did not sort last");
        assertTrue(CUBES.compare(a, nul) < 0, "null ordering is not antisymmetric");
        assertEquals(0, CUBES.compare(nul, cube(null)), "two nulls should compare equal");
        // ...and the two real captions still order against each other.
        Assertions.assertNotEquals(0, CUBES.compare(a, b));
    }

    /**
     * The contract clauses hold across null-BEARING inputs too — this is the combination that made
     * the old implementation non-transitive, and the one TimSort would have thrown on.
     */
    @HegelTest
    void theContractHoldsWhenCaptionsMayBeNull(TestCase tc) {
        SaikuCube a = cube(tc.draw(NULLABLE_CAPTION, "a"));
        SaikuCube b = cube(tc.draw(NULLABLE_CAPTION, "b"));
        SaikuCube c = cube(tc.draw(NULLABLE_CAPTION, "c"));

        assertEquals(
                Integer.signum(CUBES.compare(a, b)),
                -Integer.signum(CUBES.compare(b, a)),
                "compare must be antisymmetric with nulls present");

        if (CUBES.compare(a, b) > 0 && CUBES.compare(b, c) > 0) {
            assertTrue(CUBES.compare(a, c) > 0, "compare must be transitive with nulls present");
        }
    }

    /** Sorting a list that CONTAINS nulls must not throw the TimSort contract violation. */
    @HegelTest
    void sortingAListContainingNullCaptionsSucceeds(TestCase tc) {
        List<SaikuCube> cubes = new ArrayList<>();
        int size = tc.draw(dev.hegel.Generators.integers().min(0).max(40), "size");
        for (int i = 0; i < size; i++) {
            cubes.add(cube(tc.draw(NULLABLE_CAPTION, "caption" + i)));
        }

        cubes.sort(CUBES); // must not throw "Comparison method violates its general contract!"

        // Every null caption ends up after every non-null one.
        boolean seenNull = false;
        for (SaikuCube c : cubes) {
            if (c.getCaption() == null) {
                seenNull = true;
            } else {
                assertFalse(seenNull, "a non-null caption sorted after a null one");
            }
        }
    }
}

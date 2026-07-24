/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.olap.dto.ISaikuObject;
import org.saiku.olap.util.SaikuUniqueNameComparator;

/**
 * {@link Comparator} contract for {@link SaikuUniqueNameComparator}, which orders OLAP metadata
 * objects by their unique name. A broken comparator (non-antisymmetric, non-transitive, or
 * inconsistent with equals) can throw {@code IllegalArgumentException} from {@code TimSort} at
 * runtime, so these properties lock the three contract clauses across generated names.
 */
class SaikuUniqueNameComparatorPropertyTest {

    private static final Generator<String> NAME = fromRegex("[a-zA-Z0-9 ._-]{0,20}");

    private static final SaikuUniqueNameComparator CMP = new SaikuUniqueNameComparator();

    /** Minimal {@link ISaikuObject} carrying just a unique name — all the comparator reads. */
    private static final class Obj implements ISaikuObject {
        private final String uniqueName;

        Obj(String uniqueName) {
            this.uniqueName = uniqueName;
        }

        @Override
        public String getUniqueName() {
            return uniqueName;
        }

        @Override
        public String getName() {
            return uniqueName;
        }

        @Override
        public String toString() {
            return uniqueName;
        }
    }

    /** Antisymmetry: {@code signum(compare(a,b)) == -signum(compare(b,a))}. */
    @HegelTest
    void compareIsAntisymmetric(TestCase tc) {
        Obj a = new Obj(tc.draw(NAME, "a"));
        Obj b = new Obj(tc.draw(NAME, "b"));

        assertEquals(
                Integer.signum(CMP.compare(a, b)), -Integer.signum(CMP.compare(b, a)), "compare must be antisymmetric");
    }

    /** Transitivity: {@code compare(a,b) > 0 && compare(b,c) > 0} implies {@code compare(a,c) > 0}. */
    @HegelTest
    void compareIsTransitive(TestCase tc) {
        Obj a = new Obj(tc.draw(NAME, "a"));
        Obj b = new Obj(tc.draw(NAME, "b"));
        Obj c = new Obj(tc.draw(NAME, "c"));

        if (CMP.compare(a, b) > 0 && CMP.compare(b, c) > 0) {
            assertTrue(CMP.compare(a, c) > 0, "compare must be transitive");
        }
    }

    /** Consistency with equality: {@code compare(a,b) == 0} iff the unique names are equal. */
    @HegelTest
    void compareIsConsistentWithEquality(TestCase tc) {
        Obj a = new Obj(tc.draw(NAME, "a"));
        Obj b = new Obj(tc.draw(NAME, "b"));

        boolean zero = CMP.compare(a, b) == 0;
        boolean namesEqual = a.getUniqueName().equals(b.getUniqueName());

        assertEquals(namesEqual, zero, "compare == 0 must agree with uniqueName equality");
    }
}

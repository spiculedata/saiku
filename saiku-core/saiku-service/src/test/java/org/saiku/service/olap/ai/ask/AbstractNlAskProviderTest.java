/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;

import java.util.Optional;
import org.junit.Test;

/**
 * Unit tests for {@link AbstractNlAskProvider#parseRetryAfterMs(Optional, String)} — the 429
 * wait-hint parse core. Tested via the {@code (Optional<String>, String)} overload rather than the
 * {@code HttpResponse} one since building a real {@code HttpResponse<String>} for a unit test is
 * awkward; the {@code HttpResponse} overload is a one-line delegation to this core.
 */
public class AbstractNlAskProviderTest {

    @Test
    public void headerIntegerSecondsWinsOverBody() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(Optional.of("9"), "ignored body");
        assertEquals(9000L, ms);
    }

    @Test
    public void headerFractionalSecondsRoundsUp() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(Optional.of("9.5"), null);
        assertEquals(9500L, ms);
    }

    @Test
    public void noHeaderFallsBackToBodySecondsOnlyPhrasing() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(
                Optional.empty(), "Rate limit reached. Please try again in 9.512s.");
        assertEquals(9512L, ms);
    }

    @Test
    public void noHeaderFallsBackToBodyMinutesAndSecondsPhrasing() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(Optional.empty(), "try again in 2m1.2s");
        assertEquals(121200L, ms);
    }

    @Test
    public void neitherHeaderNorBodyHintReturnsZero() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(Optional.empty(), "no hint here");
        assertEquals(0L, ms);
    }

    @Test
    public void nullBodyAndNoHeaderReturnsZero() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(Optional.empty(), null);
        assertEquals(0L, ms);
    }

    @Test
    public void unparseableHeaderFallsBackToBody() {
        long ms = AbstractNlAskProvider.parseRetryAfterMs(
                Optional.of("Wed, 21 Oct 2026 07:28:00 GMT"), "try again in 3s");
        assertEquals(3000L, ms);
    }
}

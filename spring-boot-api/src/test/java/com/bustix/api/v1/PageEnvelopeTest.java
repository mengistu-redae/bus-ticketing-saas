package com.bustix.api.v1;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PageEnvelopeTest {

    private static final List<Integer> TWENTY_FIVE = IntStream.range(0, 25).boxed().toList();

    @Test
    void slicesTheRequestedPageAndReportsTheFullTotal() {
        PageEnvelope<Integer> page = PageEnvelope.of(TWENTY_FIVE, 1, 10);

        assertThat(page.items()).containsExactly(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(25);
    }

    @Test
    void lastPartialPageIsShorterThanTheSize() {
        assertThat(PageEnvelope.of(TWENTY_FIVE, 2, 10).items()).containsExactly(20, 21, 22, 23, 24);
    }

    @Test
    void pageBeyondTheEndIsEmptyButStillReportsTotal() {
        PageEnvelope<Integer> page = PageEnvelope.of(TWENTY_FIVE, 9, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(25);
    }

    @Test
    void clampsNegativePageAndOutOfRangeSize() {
        assertThat(PageEnvelope.of(TWENTY_FIVE, -3, 10).page()).isZero();
        assertThat(PageEnvelope.of(TWENTY_FIVE, 0, 0).size()).isEqualTo(1);
        assertThat(PageEnvelope.of(TWENTY_FIVE, 0, 9999).size()).isEqualTo(100);
    }
}

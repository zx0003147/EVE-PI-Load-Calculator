package com.vepi.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatsTest {

    @Test
    void runtime_hourFractions() {
        assertEquals("0s", Formats.runtime(0));
        assertEquals("1h", Formats.runtime(3600));
        assertEquals("2h", Formats.runtime(7200));
        assertEquals("30m", Formats.runtime(1800));
        assertEquals("45s", Formats.runtime(45));
        assertEquals("1h 30m", Formats.runtime(5400));
        assertEquals("1h 1s", Formats.runtime(3601));
    }

    @Test
    void runtime_dayBasedBeyond24h() {
        assertEquals("1d", Formats.runtime(86400));
        assertEquals("1d 1h", Formats.runtime(90000));   // 25h, spec example
        assertEquals("1d 22h", Formats.runtime(165600)); // 46h, IRD acceptance runtime
        assertEquals("2d 6h", Formats.runtime(194400));
        assertEquals("1d 1h 1m 1s", Formats.runtime(90061));
    }

    @Test
    void runtimeExact_appendsSeconds() {
        assertEquals("1d 22h (165600 s)", Formats.runtimeExact(165600));
        assertEquals("0s (0 s)", Formats.runtimeExact(0));
    }

    @Test
    void amount_grouping() {
        assertEquals("19,872", Formats.amount(19872L));
        assertEquals("128", Formats.amount(128L));
        assertEquals("1,234,567", Formats.amount(1234567L));
        assertEquals("2,208", Formats.amount(2208L));
    }

    @Test
    void amount_decimalStripsTrailingZeros() {
        assertEquals("19,872.5", Formats.amount(new BigDecimal("19872.50")));
        assertEquals("432", Formats.amount(new BigDecimal("432.00")));
        assertEquals("0.75", Formats.amount(new BigDecimal("0.750")));
        assertEquals("19,872 m3", Formats.volume(new BigDecimal("19872")));
        assertEquals("6,624 m3", Formats.volume(new BigDecimal("6624.0")));
    }

    // ---- presentation helpers added with the UI/UX revision ----

    @Test
    void capacityUsed_pairsUsedAndTotal() {
        assertEquals("53,865 / 54,000 m3",
                Formats.capacityUsed(new BigDecimal("53865"), new BigDecimal("54000")));
        assertEquals("19,872 / 20,000 m3",
                Formats.capacityUsed(new BigDecimal("19872.00"), new BigDecimal("20000")));
    }

    @Test
    void addCell_emDashAtZero_groupedOtherwise() {
        assertEquals("\u2014", Formats.addCell(0), "nothing to add reads as an em dash");
        assertEquals("55", Formats.addCell(55));
        assertEquals("42,890", Formats.addCell(42890));
    }
}

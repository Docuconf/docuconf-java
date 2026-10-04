package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.docuconf.contract.GoDuration;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

/**
 * Why the contract records {@code encoding: "iso8601"} for Spring: its simple style takes a single unit, so the
 * Go form of most durations does not parse, while every ISO-8601 string the platform renders does.
 */
class DurationEncodingTest {

    @Test
    void springRejectsCompoundGoDurations() {
        assertThrows(IllegalArgumentException.class, () -> DurationStyle.detectAndParse("1m30s"));
        assertThrows(IllegalArgumentException.class, () -> DurationStyle.detectAndParse("1h30m"));
        assertThrows(IllegalArgumentException.class, () -> DurationStyle.detectAndParse("1s500ms"));
        // Single-unit Go durations happen to work, which is why the problem is easy to miss.
        assertEquals(Duration.ofSeconds(90), DurationStyle.detectAndParse("90s"));
    }

    @Test
    void springParsesEveryIso8601Rendering() {
        // The renderings #RenderDuration produces (spec/cue/examples/durations.cue): PT<seconds>[.<fraction>]S.
        for (String go : List.of("90s", "250ms", "1500ms", "26h3m4s500ms", "7ms", "720h", "0s", "1m30s")) {
            Duration d = GoDuration.parse(go);
            String wire = GoDuration.iso8601(d);
            assertEquals(d, DurationStyle.detectAndParse(wire), go + " rendered as " + wire);
        }
        assertEquals("PT90S", GoDuration.iso8601(Duration.ofSeconds(90)));
        assertEquals("PT0.25S", GoDuration.iso8601(Duration.ofMillis(250)));
    }
}

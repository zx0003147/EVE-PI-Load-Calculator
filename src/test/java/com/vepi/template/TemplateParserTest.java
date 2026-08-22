package com.vepi.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Template tests using the real fixture (Integrity Response Drones P4 factory).
 * Verifies the text parses, pin count is correct, and producers are identified
 * without using route quantities. The core entry is parse(String) — parse(Path)
 * is only a Files.readString wrapper around it.
 */
class TemplateParserTest {

    private static final String FIXTURE = "data/templates/IntegrityResponseDrones.json";

    private static String fixtureText() {
        try {
            return Files.readString(Path.of(FIXTURE));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parsesRealFixtureText() {
        PiTemplate tpl = new TemplateParser().parse(fixtureText());
        assertNotNull(tpl.Cmt, "comment must be present");
        assertEquals(9, tpl.P.size(), "fixture has 9 pins");
    }

    @Test
    void parsesRealFixtureFile() {
        PiTemplate tpl = new TemplateParser().parse(Path.of(FIXTURE));
        assertEquals(9, tpl.P.size(), "fixture has 9 pins");
    }

    @Test
    void toleratesLeadingAndTrailingWhitespace() {
        String padded = "\n\n  " + fixtureText() + "  \n";
        PiTemplate tpl = new TemplateParser().parse(padded);
        assertEquals(9, tpl.P.size(), "surrounding whitespace must not break parsing");
    }

    @Test
    void identifies8ProducersAnd1NonProducer() {
        PiTemplate tpl = new TemplateParser().parse(fixtureText());
        List<PiTemplate.Pin> producers = tpl.P.stream()
                .filter(PiTemplate.Pin::isProducer).toList();
        assertEquals(8, producers.size(), "8 production facilities (High-Tech plants)");

        // The non-producer is the launchpad.
        List<PiTemplate.Pin> nonProducers = tpl.P.stream()
                .filter(p -> !p.isProducer()).toList();
        assertEquals(1, nonProducers.size());
        assertEquals(2544L, nonProducers.get(0).T, "non-producer is the Barren Launchpad");
    }

    @Test
    void producersAreHighTechFacilityProducingIrd() {
        PiTemplate tpl = new TemplateParser().parse(fixtureText());
        for (PiTemplate.Pin p : tpl.P) {
            if (!p.isProducer()) continue;
            assertEquals(2475L, p.T, "facility type is Barren High-Tech Production Plant");
            assertEquals(2868L, p.S, "output typeID is Integrity Response Drones (2868)");
        }
    }

    @Test
    void invalidJsonThrowsInvalidTemplate() {
        assertThrows(TemplateException.InvalidTemplate.class,
                () -> new TemplateParser().parse("{ not valid json"));
    }

    @Test
    void plainNonJsonTextThrowsInvalidTemplate() {
        assertThrows(TemplateException.InvalidTemplate.class,
                () -> new TemplateParser().parse("hello"));
        assertThrows(TemplateException.InvalidTemplate.class,
                () -> new TemplateParser().parse("{abc"));
    }

    @Test
    void blankTextThrowsInvalidTemplate() {
        assertThrows(TemplateException.InvalidTemplate.class,
                () -> new TemplateParser().parse(""));
        assertThrows(TemplateException.InvalidTemplate.class,
                () -> new TemplateParser().parse("   \n\n  "));
        assertThrows(TemplateException.InvalidTemplate.class,
                () -> new TemplateParser().parse((String) null));
    }

    @Test
    void validJsonWithoutPinsIsUnsupportedTemplate() {
        // Valid JSON, but not a PI template — distinct from malformed JSON.
        assertThrows(TemplateException.UnsupportedTemplate.class,
                () -> new TemplateParser().parse("{\"hello\":\"world\"}"));
        assertThrows(TemplateException.UnsupportedTemplate.class,
                () -> new TemplateParser().parse("{\"Cmt\":\"x\"}"));
    }
}

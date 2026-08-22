package com.vepi.template;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a CCP PI template export (JSON) into a {@link PiTemplate}.
 *
 * <p>The core entry point is {@link #parse(String)}: the pasted/loaded template
 * text itself. {@link #parse(Path)} is a thin convenience wrapper that reads the
 * file and delegates — there is exactly ONE parsing code path, shared by pasted
 * text and files alike. File extensions are never a criterion; only the content
 * decides whether the text is a valid PI template.
 *
 * <p>Leading/trailing whitespace around the JSON is tolerated (stripped before
 * parsing); the JSON's internal string content is never modified.
 *
 * <p>Phase 0 parses only what the calculation needs: the pin array ({@code P}),
 * reading each pin's facility typeID ({@code T}) and output typeID ({@code S}).
 * Links and routes are accepted but not consumed — route quantities are never
 * used as recipe data (the SDE is the sole recipe source).
 */
public final class TemplateParser {

    private final Gson gson = new Gson();

    /** Gson view of the raw template; only the fields we care about are bound. */
    private static final class RawTemplate {
        @SerializedName("Cmt") String cmt;
        @SerializedName("P") List<RawPin> pins;
        @SerializedName("Pln") Long pln;
        // L, R, CmdCtrLv, Diam etc. are present in the file but ignored by Gson.
    }

    private static final class RawPin {
        @SerializedName("T") Long t;
        @SerializedName("S") Long s;
    }

    /**
     * Parses template text that came from a file. Pure wrapper:
     * {@code Files.readString} + {@link #parse(String)}.
     */
    public PiTemplate parse(Path templateFile) {
        String json;
        try {
            json = Files.readString(templateFile);
        } catch (IOException e) {
            throw new TemplateException.InvalidTemplate(
                    "Cannot read template file " + templateFile, e);
        }
        return parse(json);
    }

    /**
     * Core parser: accepts the raw template JSON text (as pasted by the user).
     *
     * @throws TemplateException.InvalidTemplate   the text is not valid JSON
     *                                             (or is empty/null)
     * @throws TemplateException.UnsupportedTemplate the JSON is syntactically
     *                                             valid but is not a PI template
     *                                             (no pins / empty P array)
     */
    public PiTemplate parse(String templateText) {
        if (templateText == null) {
            throw new TemplateException.InvalidTemplate("Template content is empty");
        }
        String json = templateText.strip();   // tolerate surrounding whitespace only
        if (json.isEmpty()) {
            throw new TemplateException.InvalidTemplate("Template content is empty");
        }
        RawTemplate raw;
        try {
            raw = gson.fromJson(json, RawTemplate.class);
        } catch (JsonSyntaxException e) {
            throw new TemplateException.InvalidTemplate("Template is not valid JSON", e);
        }
        if (raw == null) {
            throw new TemplateException.InvalidTemplate("Template parsed to null (empty object?)");
        }
        if (raw.pins == null || raw.pins.isEmpty()) {
            // Valid JSON, but without a pin array it is not a PI template.
            throw new TemplateException.UnsupportedTemplate(
                    "JSON is valid but has no pins (P array missing/empty) — not a PI template");
        }
        List<PiTemplate.Pin> pins = new ArrayList<>(raw.pins.size());
        for (int i = 0; i < raw.pins.size(); i++) {
            RawPin rp = raw.pins.get(i);
            if (rp == null || rp.t == null) {
                throw new TemplateException.InvalidTemplate(
                        "Pin #" + (i + 1) + " is missing required field T (facility typeID)");
            }
            pins.add(new PiTemplate.Pin(rp.t, rp.s));
        }
        return new PiTemplate(raw.cmt, pins, raw.pln);
    }
}

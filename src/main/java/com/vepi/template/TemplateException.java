package com.vepi.template;

/**
 * Base class for template-level failures (parse / structural), kept separate from
 * SDE-resolution failures so the two layers do not mask each other.
 */
public class TemplateException extends RuntimeException {
    public TemplateException(String message) { super(message); }
    public TemplateException(String message, Throwable cause) { super(message, cause); }

    /** The template file could not be parsed (malformed JSON / missing required structure). */
    public static final class InvalidTemplate extends TemplateException {
        public InvalidTemplate(String message, Throwable cause) { super(message, cause); }
        public InvalidTemplate(String message) { super(message); }
    }

    /** The template parsed, but contains a structure Phase 0 does not yet support. */
    public static final class UnsupportedTemplate extends TemplateException {
        public UnsupportedTemplate(String message) { super(message); }
    }
}

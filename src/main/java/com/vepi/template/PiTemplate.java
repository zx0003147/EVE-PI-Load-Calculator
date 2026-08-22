package com.vepi.template;

import java.util.List;

/**
 * Raw, minimally-parsed PI template model.
 *
 * <p>Field names mirror the CCP template export format exactly. Semantics were
 * verified against the SDE + internal route references (NOT assumed from letters):
 *
 * <ul>
 *   <li>{@code P} = pins. Each pin {@code T} = facility typeID, {@code S} = output
 *       commodity typeID when the pin is a production facility (null otherwise, e.g.
 *       launchpads). Note: {@code S} is the <b>output typeID</b>, not the schematicID —
 *       the schematic is resolved later from the SDE via planetSchematicsTypeMap.</li>
 *   <li>{@code L} = links, {@code R} = routes. Both ignored for Phase 0 calculation
 *       (route quantities are never used as recipe data).</li>
 * </ul>
 */
public final class PiTemplate {
    public final String Cmt;          // comment / template name
    public final List<Pin> P;         // pins
    public final Long Pln;            // planet type id (informational)

    public PiTemplate(String cmt, List<Pin> pins, Long pln) {
        this.Cmt = cmt;
        this.P = pins == null ? List.of() : List.copyOf(pins);
        this.Pln = pln;
    }

    /** A pin in the {@code P} array. Only the fields needed for production calculation. */
    public static final class Pin {
        public final long T;          // facility typeID
        public final Long S;          // output commodity typeID (null = not a producer)

        public Pin(long t, Long s) {
            this.T = t;
            this.S = s;
        }

        /** True if this pin is a production facility (has an assigned output). */
        public boolean isProducer() { return S != null; }
    }
}

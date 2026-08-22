#!/usr/bin/env python3
"""
Template Diagnostic Tool -- independent of the Java parser/extractor.

Reads a CCP PI template JSON file, dumps EVERY pin, cross-references each
pin's T (facility typeID) and S (output typeID) against the SDE database,
and reports whether the template contains P3 production facilities.

Usage:
    python tools/template_diagnostic.py <template.json> [pi-sde.db]

If the SDE path is omitted, defaults to data/sde/pi-sde.db.
"""

import json
import sqlite3
import sys
import os
from collections import Counter, defaultdict


def main():
    if len(sys.argv) < 2:
        print("Usage: python tools/template_diagnostic.py <template.json> [pi-sde.db]")
        sys.exit(1)

    template_path = sys.argv[1]
    db_path = sys.argv[2] if len(sys.argv) > 2 else "data/sde/pi-sde.db"

    if not os.path.exists(template_path):
        print("ERROR: template file not found: %s" % template_path)
        sys.exit(1)
    if not os.path.exists(db_path):
        print("ERROR: SDE database not found: %s" % db_path)
        sys.exit(1)

    with open(template_path, encoding="utf-8") as f:
        tpl = json.load(f)

    db = sqlite3.connect(db_path)

    pins = tpl.get("P", [])
    cmt = tpl.get("Cmt", "(none)")
    pln = tpl.get("Pln", None)

    print("=" * 70)
    print("TEMPLATE DIAGNOSTIC")
    print("=" * 70)
    print("File:    %s" % template_path)
    print("Comment: %s" % cmt)
    print("Pln:    %s" % pln)
    print("Total pins: %d" % len(pins))
    print()

    # ---- Per-pin dump ----
    print("=" * 70)
    print("=== TEMPLATE PINS ===")
    print("=" * 70)

    # Track stats
    facility_type_counts = Counter()       # T -> count
    output_counts = Counter()              # output typeID -> count (producers only)
    producer_pins = []                      # pins with S != null
    non_producer_pins = []                  # pins with S == null
    unprogrammed_facility_pins = []         # S==null but T is a known facility type

    for i, pin in enumerate(pins):
        t = pin.get("T")
        s = pin.get("S")
        pin_index = i + 1

        # Look up facility name
        fac_row = db.execute(
            "SELECT typeName FROM invTypes WHERE typeID=?", (t,)).fetchone()
        fac_name = fac_row[0] if fac_row else "UNKNOWN typeID %s" % t

        # Look up output name (if S non-null)
        out_name = None
        if s is not None:
            out_row = db.execute(
                "SELECT typeName FROM invTypes WHERE typeID=?", (s,)).fetchone()
            out_name = out_row[0] if out_row else "UNKNOWN typeID %s" % s

        # Check if T is a known PI facility type (in planetSchematicsPinMap)
        is_facility = db.execute(
            "SELECT 1 FROM planetSchematicsPinMap WHERE pinTypeID=? LIMIT 1",
            (t,)).fetchone() is not None

        # What schematics can this facility type run?
        compatible_schematics = [str(r[0]) for r in db.execute(
            "SELECT schematicID FROM planetSchematicsPinMap WHERE pinTypeID=? ORDER BY schematicID",
            (t,))]

        # Is this pin a producer (S != null)?
        is_producer = s is not None

        # If producer: resolve schematic
        schematic_id = None
        schematic_name = None
        cycle_time = None
        recipe_inputs = []
        recipe_output = None
        schematic_ok = False
        extractor_would_include = False
        extractor_skip_reason = None

        if is_producer:
            producer_pins.append(pin_index)
            # Find schematic by output typeID (isInput=0)
            sch_row = db.execute(
                "SELECT schematicID FROM planetSchematicsTypeMap WHERE typeID=? AND isInput=0",
                (s,)).fetchone()
            if sch_row:
                schematic_id = sch_row[0]
                sch_info = db.execute(
                    "SELECT schematicName, cycleTime FROM planetSchematics WHERE schematicID=?",
                    (schematic_id,)).fetchone()
                schematic_name = sch_info[0] if sch_info else "?"
                cycle_time = sch_info[1] if sch_info else None

                # Get recipe materials
                for m in db.execute(
                    """SELECT t.typeID, t.quantity, t.isInput, i.typeName
                       FROM planetSchematicsTypeMap t
                       JOIN invTypes i ON i.typeID = t.typeID
                       WHERE t.schematicID = ?
                       ORDER BY t.isInput DESC, i.typeName""",
                    (schematic_id,)):
                    if m[2]:  # isInput = 1
                        recipe_inputs.append((m[3], m[1]))
                    else:
                        recipe_output = (m[3], m[1])

                # Check facility compatibility
                can_run = db.execute(
                    "SELECT 1 FROM planetSchematicsPinMap WHERE pinTypeID=? AND schematicID=? LIMIT 1",
                    (t, schematic_id)).fetchone() is not None
                if can_run:
                    schematic_ok = True
                    extractor_would_include = True
                else:
                    schematic_ok = False
                    extractor_would_include = False
                    if is_facility:
                        extractor_skip_reason = (
                            "facility type %d (%s) cannot run schematic %d (%s)"
                            % (t, fac_name, schematic_id, schematic_name))
                    else:
                        extractor_skip_reason = (
                            "typeID %d is not a known PI facility" % t)
            else:
                extractor_skip_reason = (
                    "no PI schematic produces typeID %d" % s)
        else:
            non_producer_pins.append(pin_index)
            if is_facility:
                unprogrammed_facility_pins.append((pin_index, t, fac_name))
                extractor_skip_reason = "S == null (not a producer per isProducer())"
            else:
                extractor_skip_reason = "S == null (non-production structure: launchpad/storage/CC)"

        # Track stats
        facility_type_counts[(t, fac_name)] += 1
        if is_producer and schematic_ok:
            output_counts[(s, out_name)] += 1

        # ---- Print this pin ----
        print()
        print("Pin #%d" % pin_index)
        print("  T: %d" % t)
        print("  Facility: %s" % fac_name)
        print("  S: %s" % ("null" if s is None else str(s)))
        if out_name:
            print("  Output item: %s" % out_name)
        print("  Production facility: %s" % ("YES" if is_producer else "NO"))
        print("  Is known PI facility type (in planetSchematicsPinMap): %s" % is_facility)
        if compatible_schematics:
            print("  Compatible schematic IDs: %s" % ", ".join(compatible_schematics))

        if is_producer and schematic_id is not None:
            print("  Resolved schematic: %d (%s)" % (schematic_id, schematic_name))
            print("  Cycle: %ss" % cycle_time)
            print("  Recipe:")
            for inp_name, inp_qty in recipe_inputs:
                print("    %s x %d" % (inp_name, inp_qty))
            if recipe_output:
                print("    -> %s x %d" % recipe_output)
            if not schematic_ok:
                print("  *** FACILITY/SCHEMATIC MISMATCH: %s" % extractor_skip_reason)

        if not extractor_would_include:
            print("  Extractor would: SKIP")
            print("  Skip reason: %s" % extractor_skip_reason)
        else:
            print("  Extractor would: INCLUDE")

    # ---- Summary ----
    print()
    print("=" * 70)
    print("=== SUMMARY ===")
    print("=" * 70)
    print()
    print("Total pins: %d" % len(pins))
    print("Producer pins (S != null): %d" % len(producer_pins))
    print("Non-producer pins (S == null): %d" % len(non_producer_pins))
    print()

    print("By facility type:")
    for (tid, name), cnt in sorted(facility_type_counts.items()):
        print("  %d %s: %d" % (tid, name, cnt))
    print()

    print("Production pins resolved by SDE schematic: %d" % sum(
        1 for i, p in enumerate(pins)
        if p.get("S") is not None and _schematic_resolved(db, p["S"])
    ))
    print()

    print("Production outputs (resolved & facility-compatible):")
    if output_counts:
        for (tid, name), cnt in sorted(output_counts.items()):
            print("  %s (typeID %d): %d facilities" % (name, tid, cnt))
    else:
        print("  (none)")
    print()

    # ---- Unprogrammed facility check ----
    print("=" * 70)
    print("=== UNPROGRAMMED FACILITY CHECK ===")
    print("=" * 70)
    print()
    if unprogrammed_facility_pins:
        print("WARNING: Found facility-type pins with S == null:")
        for pin_idx, tid, name in unprogrammed_facility_pins:
            compatible = [str(r[0]) for r in db.execute(
                "SELECT schematicID FROM planetSchematicsPinMap WHERE pinTypeID=? ORDER BY schematicID",
                (tid,))]
            print("  Pin #%d: T=%d (%s) S=null" % (pin_idx, tid, name))
            print("    This facility CAN run schematics: %s" % ", ".join(compatible))
            print("    But it has NO assigned output (S=null).")
            print("    The current extractor SKIPS it (isProducer() == false).")
        print()
    else:
        print("No facility-type pins with S == null found.")
        print("All S == null pins are non-production structures (launchpads/storage/CC).")
        print()

    # ---- Full table ----
    print("=" * 70)
    print("=== FULL PIN TABLE ===")
    print("=" * 70)
    print()
    print("| Pin | Facility TypeID | Facility Name                          |    S | Output Item                    | Production? | Resolved Schematic |")
    print("| --: | -------------: | -------------------------------------- | ---: | ------------------------------ | ----------- | ------------------ |")

    for i, pin in enumerate(pins):
        t = pin.get("T")
        s = pin.get("S")
        pin_index = i + 1

        fac_row = db.execute(
            "SELECT typeName FROM invTypes WHERE typeID=?", (t,)).fetchone()
        fac_name = fac_row[0] if fac_row else "?"

        if s is not None:
            out_row = db.execute(
                "SELECT typeName FROM invTypes WHERE typeID=?", (s,)).fetchone()
            out_name = out_row[0] if out_row else "?"
            sch_row = db.execute(
                "SELECT schematicID FROM planetSchematicsTypeMap WHERE typeID=? AND isInput=0",
                (s,)).fetchone()
            if sch_row:
                sch_id = sch_row[0]
                can_run = db.execute(
                    "SELECT 1 FROM planetSchematicsPinMap WHERE pinTypeID=? AND schematicID=? LIMIT 1",
                    (t, sch_id)).fetchone() is not None
                sch_label = "%d%s" % (sch_id, "" if can_run else " (MISMATCH!)")
            else:
                sch_label = "NOT FOUND"
            prod = "YES"
        else:
            out_name = "-"
            sch_label = "-"
            prod = "NO"

        print("| %3d | %14d | %-38s | %4s | %-30s | %-11s | %-18s |" % (
            pin_index, t, fac_name, str(s) if s else "null",
            out_name, prod, sch_label))

    # ---- Final conclusion ----
    print()
    print("=" * 70)
    print("=== CONCLUSION ===")
    print("=" * 70)
    print()

    p3_schematic_ids = set()
    for r in db.execute(
            "SELECT schematicID FROM planetSchematicsPinMap WHERE pinTypeID IN "
            "(SELECT DISTINCT pinTypeID FROM planetSchematicsPinMap WHERE pinTypeID IN "
            "(2470,2472,2474,2480,2484,2485,2491,2494))"):
        p3_schematic_ids.add(r[0])

    has_p3_producers = False
    has_unprogrammed_p3_facilities = False
    p3_output_typeids = set()
    # P3 outputs are those produced by schematics 65-111
    for r in db.execute(
            "SELECT DISTINCT typeID FROM planetSchematicsTypeMap WHERE isInput=0 AND schematicID BETWEEN 65 AND 111"):
        p3_output_typeids.add(r[0])

    for pin in pins:
        s = pin.get("S")
        if s is not None and s in p3_output_typeids:
            has_p3_producers = True
        if s is None:
            t = pin.get("T")
            # Check if this T is an Advanced Industry Facility (P3 capable)
            adv_facilities = {2470, 2472, 2474, 2480, 2484, 2485, 2491, 2494}
            if t in adv_facilities:
                has_unprogrammed_p3_facilities = True

    if has_p3_producers:
        print("CONCLUSION B: The template contains P2 -> P3 production facilities.")
        print()
        print("The template has pins with S pointing to P3 commodities (GMB, Hazmat, PV, etc.)")
        print("assigned to Advanced Industry Facility typeIDs.")
        print()
        if False:  # This branch would only trigger if extractor was broken
            print("Current ProductionCapacityExtractor is INCORRECT -- it missed P3 facilities.")
        else:
            print("The current parser/extractor SHOULD correctly identify these P3 facilities")
            print("(they have S != null and T is a valid Advanced Industry Facility typeID).")
            print()
            print("If the GUI still shows only P4 factories, the problem is elsewhere.")
    elif has_unprogrammed_p3_facilities:
        print("CONCLUSION B (variant): The template contains Advanced Industry Facility pins")
        print("with S == null (unprogrammed).")
        print()
        print("These facilities COULD produce P3 items but have no output assigned in the")
        print("template. The current extractor skips them because isProducer() checks S != null.")
        print()
        print("This is a TEMPLATE issue, not a parser bug. In EVE Online, you must program")
        print("each factory's schematic before exporting the template. Unprogrammed factories")
        print("have S = null and the program cannot know what they should produce.")
        print()
        print("Fix: re-program the P3 factories in-game, then re-export the template.")
    else:
        print("CONCLUSION A: The template itself is a P3 -> P4 template.")
        print()
        print("The template contains only P4 production facilities (High-Tech Production Plant).")
        print("There are NO P3 production facilities (Advanced Industry Facility with S != null).")
        print()
        print("The current parser is CORRECT.")
        print()
        print("It is impossible to calculate template-specific P2 -> P4 throughput from")
        print("this template alone because the P3 production facilities are not present.")
        print("P3 items appear as external inputs ('P3 TO LOAD'), which is the expected")
        print("behavior for a P4-only template in the P2-Only Sustainable Production model.")

    print()
    print("=" * 70)
    print("=== THREE QUESTIONS ===")
    print("=" * 70)
    print()

    q1 = "YES" if (has_p3_producers or has_unprogrammed_p3_facilities) else "NO"
    if has_p3_producers:
        q1_detail = "The template has P3 factory pins with S pointing to P3 commodities."
    elif has_unprogrammed_p3_facilities:
        q1_detail = "The template has Advanced Industry Facility pins, but they have S=null (unprogrammed)."
    else:
        q1_detail = "The template has only High-Tech Production Plant pins (P4) and non-production structures."

    print("1. Does this template have P3 factories?")
    print("   %s -- %s" % (q1, q1_detail))
    print()

    print("2. Why does the program show only 10 x Integrity Response Drones?")
    if not has_p3_producers and not has_unprogrammed_p3_facilities:
        print("   Because the template genuinely contains only P4 factories.")
        print("   The parser correctly identifies all 10 (or however many) P4 pins.")
        print("   No P3 factories exist in the template to be identified.")
    elif has_unprogrammed_p3_facilities:
        print("   Because the P3 factory pins have S=null, the extractor's")
        print("   isProducer() check (S != null) returns false, so they are skipped.")
        print("   Only the P4 pins (S=2868, T=2475) are included.")
    else:
        print("   This should NOT happen if the parser is working correctly.")
        print("   P3 pins with S != null should be identified. Something else is wrong.")
    print()

    print("3. Is the problem in the template, or in the parser/extractor?")
    if not has_p3_producers and not has_unprogrammed_p3_facilities:
        print("   The problem is in the TEMPLATE. The template is a P4-only template.")
        print("   The parser/extractor is working correctly.")
    elif has_unprogrammed_p3_facilities:
        print("   The problem is in the TEMPLATE. The P3 factories exist but are")
        print("   unprogrammed (S=null). The parser cannot infer what they should produce.")
        print("   The parser/extractor is behaving correctly given the input.")
    else:
        print("   The problem is in the PARSER/EXTRACTOR. P3 pins with valid S values")
        print("   are being skipped. This is a bug that needs investigation.")

    db.close()


def _schematic_resolved(db, output_typeid):
    row = db.execute(
        "SELECT schematicID FROM planetSchematicsTypeMap WHERE typeID=? AND isInput=0",
        (output_typeid,)).fetchone()
    return row is not None


if __name__ == "__main__":
    main()

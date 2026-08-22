#!/usr/bin/env python3
"""
Build a compact PI-focused SQLite database from the Fuzzwork SDE CSV files.

This is a one-time data-prep step. The resulting pi-sde.db is the authoritative
SDE source for the tool at runtime (queried via JDBC from Kotlin).

Source: Fuzzwork SDE conversion (https://www.fuzzwork.co.uk/dump/latest/csv/)
        version 3475087, dated 2026-08-20 (post-2025-rework, legacy table names).

Tables written:
  planetSchematics        (schematicID, schematicName, cycleTime)
  planetSchematicsPinMap  (schematicID, pinTypeID)
  planetSchematicsTypeMap (schematicID, typeID, quantity, isInput)
  invTypes                (typeID, groupID, typeName, volume)   -- filtered to published=1

No external dependencies: uses only the Python stdlib (csv, sqlite3).
"""
import csv
import os
import sqlite3
import sys

CSV_DIR = os.path.join(os.path.dirname(__file__), "..", "data", "sde", "csv")
OUT_DB = os.path.join(os.path.dirname(__file__), "..", "data", "sde", "pi-sde.db")


def read_csv(name):
    path = os.path.join(CSV_DIR, name)
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def main():
    if os.path.exists(OUT_DB):
        os.remove(OUT_DB)
    os.makedirs(os.path.dirname(OUT_DB), exist_ok=True)

    conn = sqlite3.connect(OUT_DB)
    cur = conn.cursor()

    # --- planetSchematics ---
    cur.execute(
        "CREATE TABLE planetSchematics ("
        "schematicID INTEGER PRIMARY KEY, "
        "schematicName TEXT, "
        "cycleTime INTEGER)"
    )
    rows = read_csv("planetSchematics.csv")
    cur.executemany(
        "INSERT INTO planetSchematics VALUES (?,?,?)",
        [(int(r["schematicID"]), r["schematicName"], int(r["cycleTime"])) for r in rows],
    )
    print(f"planetSchematics: {len(rows)} rows")

    # --- planetSchematicsPinMap ---
    cur.execute(
        "CREATE TABLE planetSchematicsPinMap ("
        "schematicID INTEGER, pinTypeID INTEGER, "
        "PRIMARY KEY (schematicID, pinTypeID))"
    )
    rows = read_csv("planetSchematicsPinMap.csv")
    cur.executemany(
        "INSERT INTO planetSchematicsPinMap VALUES (?,?)",
        [(int(r["schematicID"]), int(r["pinTypeID"])) for r in rows],
    )
    print(f"planetSchematicsPinMap: {len(rows)} rows")

    # --- planetSchematicsTypeMap ---
    cur.execute(
        "CREATE TABLE planetSchematicsTypeMap ("
        "schematicID INTEGER, typeID INTEGER, quantity INTEGER, isInput INTEGER, "
        "PRIMARY KEY (schematicID, typeID))"
    )
    rows = read_csv("planetSchematicsTypeMap.csv")
    cur.executemany(
        "INSERT INTO planetSchematicsTypeMap VALUES (?,?,?,?)",
        [
            (int(r["schematicID"]), int(r["typeID"]), int(r["quantity"]), int(r["isInput"]))
            for r in rows
        ],
    )
    print(f"planetSchematicsTypeMap: {len(rows)} rows")

    # --- invTypes (filtered: published=1, only needed columns) ---
    cur.execute(
        "CREATE TABLE invTypes ("
        "typeID INTEGER PRIMARY KEY, groupID INTEGER, typeName TEXT, volume REAL)"
    )
    rows = read_csv("invTypes.csv")
    kept = 0
    batch = []
    for r in rows:
        if r.get("published") != "1":
            continue
        type_id = int(r["typeID"])
        group_id = int(r["groupID"]) if r["groupID"] else None
        name = r["typeName"]
        vol_raw = r.get("volume") or ""
        try:
            vol = float(vol_raw) if vol_raw != "" else 0.0
        except ValueError:
            vol = 0.0
        batch.append((type_id, group_id, name, vol))
        kept += 1
    cur.executemany("INSERT INTO invTypes VALUES (?,?,?,?)", batch)
    print(f"invTypes: {kept} rows (published=1, of {len(rows)} total)")

    conn.commit()

    # sanity check on the IRD schematic
    cur.execute(
        "SELECT schematicID, schematicName, cycleTime FROM planetSchematics "
        "WHERE schematicID = 118"
    )
    print("IRD schematic:", cur.fetchone())
    cur.execute(
        "SELECT typeID, quantity, isInput FROM planetSchematicsTypeMap "
        "WHERE schematicID = 118 ORDER BY isInput DESC"
    )
    print("IRD typemap:", cur.fetchall())
    cur.execute(
        "SELECT pinTypeID FROM planetSchematicsPinMap WHERE schematicID = 118"
    )
    print("IRD pinmap:", cur.fetchall())
    cur.execute(
        "SELECT typeID, typeName, volume FROM invTypes WHERE typeID IN "
        "(2475, 2544, 2868, 2366, 2348, 9846)"
    )
    print("template typeIDs:", cur.fetchall())

    conn.close()
    print(f"\nBuilt {OUT_DB} ({os.path.getsize(OUT_DB)} bytes)")


if __name__ == "__main__":
    sys.exit(main())

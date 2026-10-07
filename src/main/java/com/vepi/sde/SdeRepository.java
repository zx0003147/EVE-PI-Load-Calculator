package com.vepi.sde;

import com.vepi.domain.PiCommodity;
import com.vepi.domain.PiSchematic;
import com.vepi.domain.PiSchematicMaterial;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only access to the EVE SDE (Fuzzwork SQLite conversion, PI subset).
 *
 * <p>The SDE is the <b>single authoritative source</b> for PI recipes:
 * schematics, inputs/outputs, cycle time, commodity volumes and which facility
 * types can run each schematic. Template route quantities are never used here.
 *
 * <p>Backed by {@code data/sde/pi-sde.db} built by {@code tools/build_sde_db.py}
 * from the Fuzzwork SDE CSVs (version 3475087, 2026-08-20).
 */
public final class SdeRepository implements AutoCloseable {

    private final Connection connection;
    // Simple request-level caches: both domain types are immutable, and Phase 1
    // resolution loops over commodity/schematic typeIDs repeatedly.
    private final Map<Long, PiCommodity> commodityCache = new HashMap<>();
    private final Map<Long, PiSchematic> schematicCache = new HashMap<>();

    public SdeRepository(String dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (SQLException e) {
            throw new SdeException("Cannot open SDE database at " + dbPath, e);
        }
    }

    @Override
    public void close() {
        try { connection.close(); }
        catch (SQLException ignored) { }
    }

    /**
     * Resolve a schematic by its ID, fully loading cycle time, materials and the
     * facility types that may run it.
     */
    public PiSchematic getSchematic(long schematicId) {
        PiSchematic cached = schematicCache.get(schematicId);
        if (cached != null) return cached;
        PiSchematic loaded = loadSchematic(schematicId);
        schematicCache.put(schematicId, loaded);
        return loaded;
    }

    private PiSchematic loadSchematic(long schematicId) {
        Long cycleTime = queryLong(
                "SELECT cycleTime FROM planetSchematics WHERE schematicID = ?", schematicId);
        if (cycleTime == null) {
            throw new SdeException.MissingSchematic(schematicId);
        }
        String name = queryString(
                "SELECT schematicName FROM planetSchematics WHERE schematicID = ?", schematicId);

        List<PiSchematicMaterial> materials = loadMaterials(schematicId);
        if (materials.isEmpty()) {
            throw new SdeException("Schematic " + schematicId
                    + " has no materials in planetSchematicsTypeMap (invalid schematic)");
        }
        List<Long> facilityTypes = loadPinTypes(schematicId);
        return new PiSchematic(schematicId, name == null ? String.valueOf(schematicId) : name,
                cycleTime, materials, facilityTypes);
    }

    /**
     * Resolve the schematic that produces a given output commodity, by looking up the
     * planetSchematicsTypeMap row with {@code isInput = 0}. Each commodity is produced
     * by exactly one schematic in the SDE.
     */
    public Optional<Long> findSchematicByOutput(long outputTypeId) {
        Long id = queryLong(
                "SELECT schematicID FROM planetSchematicsTypeMap WHERE typeID = ? AND isInput = 0",
                outputTypeId);
        return Optional.ofNullable(id);
    }

    /**
     * All commodities produced by a planetary schematic.  Tier filtering is
     * deliberately left to {@link PiTierResolver}; this query only exposes the
     * SDE graph and never relies on a hand-maintained product-name list.
     */
    public List<PiCommodity> findAllProducedCommodities() {
        List<PiCommodity> out = new ArrayList<>();
        String sql = "SELECT DISTINCT i.typeID, i.typeName, i.volume "
                + "FROM planetSchematicsTypeMap m JOIN invTypes i ON i.typeID = m.typeID "
                + "WHERE m.isInput = 0 ORDER BY i.typeName COLLATE NOCASE, i.typeID";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PiCommodity commodity = new PiCommodity(rs.getLong("typeID"),
                        rs.getString("typeName"), readBigDecimal(rs, "volume"));
                commodityCache.putIfAbsent(commodity.typeId(), commodity);
                out.add(commodity);
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new SdeException("Failed listing produced PI commodities", e);
        }
    }

    /** Look up a commodity (name + volume) by typeID. */
    public PiCommodity getCommodity(long typeId) {
        PiCommodity cached = commodityCache.get(typeId);
        if (cached != null) return cached;
        PiCommodity loaded = loadCommodity(typeId);
        commodityCache.put(typeId, loaded);
        return loaded;
    }

    private PiCommodity loadCommodity(long typeId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT typeName, volume FROM invTypes WHERE typeID = ?")) {
            ps.setLong(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SdeException.MissingSdeType(typeId);
                }
                String name = rs.getString("typeName");
                BigDecimal vol = readBigDecimal(rs, "volume");
                return new PiCommodity(typeId, name, vol);
            }
        } catch (SQLException e) {
            throw new SdeException("Failed reading invTypes typeID " + typeId, e);
        }
    }

    /**
     * Resolve a commodity by its exact SDE type name (case-sensitive), e.g. the
     * lines of a pasted inventory list. Only PI-relevant types (those appearing in
     * planetSchematicsTypeMap) are matched — the SDE contains many unrelated types
     * with identical names, so a plain invTypes lookup would be ambiguous.
     *
     * @return empty if no PI commodity carries this exact name
     */
    public Optional<PiCommodity> findCommodityByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT i.typeID, i.typeName, i.volume FROM invTypes i "
                        + "WHERE i.typeName = ? AND i.typeID IN "
                        + "(SELECT typeID FROM planetSchematicsTypeMap)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                PiCommodity c = new PiCommodity(rs.getLong("typeID"), rs.getString("typeName"),
                        readBigDecimal(rs, "volume"));
                commodityCache.putIfAbsent(c.typeId(), c);
                return Optional.of(c);
            }
        } catch (SQLException e) {
            throw new SdeException("Failed name lookup for '" + name + "'", e);
        }
    }

    /** True if some schematic consumes this typeID (i.e. raw resources count as PI). */
    public boolean isConsumedCommodity(long typeId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM planetSchematicsTypeMap WHERE typeID = ? AND isInput = 1 LIMIT 1")) {
            ps.setLong(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new SdeException("Failed checking consumed typeID " + typeId, e);
        }
    }

    /** Facility typeIDs that can run the given schematic (planetSchematicsPinMap). */
    public List<Long> facilityTypesForSchematic(long schematicId) {
        return loadPinTypes(schematicId);
    }

    /** True if this typeID appears as a pinTypeID in planetSchematicsPinMap (i.e. a PI facility). */
    public boolean isFacilityType(long typeId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM planetSchematicsPinMap WHERE pinTypeID = ? LIMIT 1")) {
            ps.setLong(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new SdeException("Failed checking facility typeID " + typeId, e);
        }
    }

    private List<PiSchematicMaterial> loadMaterials(long schematicId) {
        List<PiSchematicMaterial> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT typeID, quantity, isInput FROM planetSchematicsTypeMap "
                        + "WHERE schematicID = ? ORDER BY isInput DESC, typeID")) {
            ps.setLong(1, schematicId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long typeId = rs.getLong("typeID");
                    int qty = rs.getInt("quantity");
                    boolean input = rs.getInt("isInput") != 0;
                    out.add(new PiSchematicMaterial(typeId, qty, input));
                }
            }
        } catch (SQLException e) {
            throw new SdeException("Failed reading materials for schematic " + schematicId, e);
        }
        return out;
    }

    private List<Long> loadPinTypes(long schematicId) {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pinTypeID FROM planetSchematicsPinMap WHERE schematicID = ?")) {
            ps.setLong(1, schematicId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong("pinTypeID"));
            }
        } catch (SQLException e) {
            throw new SdeException("Failed reading pin map for schematic " + schematicId, e);
        }
        return out;
    }

    private Long queryLong(String sql, long arg) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw new SdeException("Query failed: " + sql, e);
        }
    }

    private String queryString(String sql, long arg) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new SdeException("Query failed: " + sql, e);
        }
    }

    private static BigDecimal readBigDecimal(ResultSet rs, String col) throws SQLException {
        String raw = rs.getString(col);
        if (raw == null || raw.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}

[简体中文](README.md) | **English**

# EVE PI Calculator

EVE PI Calculator is a Windows desktop tool for EVE Online Planetary Industry (PI), focused on production-planet load allocation, inventory balancing, and P4/P3/P2 recipe analysis.

Current stable version: [`v1.0.0`](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/tag/v1.0.0)

## Download

Most users should download the latest Windows x64 portable package from [GitHub Releases](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/latest):

[`EVE-PI-Calculator-v1.0.0-Windows-x64.zip`](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/download/v1.0.0/EVE-PI-Calculator-v1.0.0-Windows-x64.zip)

1. Download the ZIP.
2. Extract the entire folder.
3. Double-click `EVE PI Calculator.exe`.

The package supports Windows 10/11 x64 and includes both the Java Runtime and the PI SDE data required by the application. No separate Java installation or command line is required.

## Features

### Load Allocation

Calculate sustainable production and material loads from your actual inventory, EVE PI Planet Templates, and the input capacity available on each production planet.

- Paste and parse the current inventory.
- Import EVE PI Planet Templates and work with multiple production planets.
- Set the available material capacity for each planet.
- Calculate sustainable production from the factories in each template and their SDE recipes.
- Allocate shared P2 inventory across multiple planets.
- See Runtime, Blocks, and Expected Output.
- See the P2 and P3 materials to load onto each planet.
- Use Duplicate Planet to copy an identical production-planet setup quickly.
- Copy a single planet's material load list.

This feature calculates from existing planet templates; it does not design planet layouts automatically.

### Balance Inventory

After you paste an inventory and select a P4 Product, the application expands its recipe directly from the SDE:

```text
Inventory + P4 Product
          ↓
      P4 → P3
      P4 → P3 → P2
          ↓
 P2 Balance / P3 Balance
```

P2 and P3 inventories are balanced independently. P2 stock is balanced only against P2 ratios, and P3 stock only against P3 ratios. A P3 shortage is never converted into an additional P2 purchase requirement.

Each balance result reports:

- Target Blocks
- Per Block
- Current
- Target
- Need To Add
- Additional Volume
- Equivalent P4 Output

### Recipe Hierarchy and executable production blocks

Recipe Summary shows the P4-to-P3-to-P2 hierarchy directly, making the complete recipe easy to verify:

```text
P4
├─ P3
│  ├─ P2
│  └─ P2
└─ P3
   ├─ P2
   └─ P2
```

Balance Inventory uses complete, executable production blocks instead of merely reducing materials to the smallest mathematical ratio:

- A P3 Balance Block corresponds to a complete P4 production cycle.
- A P2 Balance Block satisfies both a complete P4 cycle and complete P3 production batches.
- Equivalent P4 Output is always calculated in whole units.

This avoids a ratio that looks balanced mathematically but cannot be executed by the factories as a complete P4 run.

## Usage

### Load Allocation

1. Paste the inventory and click Parse.
2. Add a Planet.
3. Paste or load its Planet Template.
4. Enter the material capacity available on that planet.
5. Use Duplicate when you have identical production planets.
6. Click Calculate Allocation.
7. Review each planet's material load, runtime, and expected output.

### Balance Inventory

1. Paste the inventory and click Parse.
2. Select a product from the P4 Product menu.
3. Review Recipe Summary.
4. Click Calculate Balance.
5. Review P2 Balance and P3 Balance separately.
6. Use Copy Shopping List to copy the purchase list.

## Data

PI product and schematic data comes from the EVE Static Data Export (SDE). The repository's data-preparation tool builds a compact PI-focused SQLite database from the [Fuzzwork SDE CSV conversion](https://www.fuzzwork.co.uk/dump/latest/csv/), and the Windows portable package bundles the database it needs at runtime. Recipe and product lookups use EVE Type IDs rather than hard-coded product names.

## Not currently included

- Automatic ECU / Extractor planning
- Automatic planet CPU, Powergrid, or Layout design
- Market price or profit calculations
- POCO tax calculations
- Automatic selection of the number of planets required

## Building from source

The project uses Java + Swing without Gradle or Maven. A full JDK 21 or newer is required, with `java`, `javac`, `jar`, and `jpackage` available. The official `v1.0.0` portable package was built with JDK 25.

Compile and run the test suite from Windows Bash / Git Bash:

```bash
bash build.sh
```

Build and verify the portable package from Windows PowerShell:

```powershell
.\build-portable.ps1
```

You can also run `build-portable.bat`. The release script runs the full test suite, creates an app-image with a bundled Java Runtime, packages the ZIP, and writes its SHA-256 checksum file.

## Project status

The current stable version is [`v1.0.0`](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/tag/v1.0.0). Use [GitHub Releases](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases) for official builds and checksums.

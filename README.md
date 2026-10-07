**简体中文** | [English](README_EN.md)

# EVE PI Calculator

一个用于 EVE Online 行星工业（Planetary Industry，PI）的 Windows 桌面计算工具，专注于加工星装载分配、库存配平和 P4/P3/P2 配方分析。

当前稳定版本：[`v1.0.0`](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/tag/v1.0.0)

## 下载

普通用户建议直接从 [GitHub Releases](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/latest) 下载最新版 Windows x64 便携包：

[`EVE-PI-Calculator-v1.0.0-Windows-x64.zip`](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/download/v1.0.0/EVE-PI-Calculator-v1.0.0-Windows-x64.zip)

1. 下载 ZIP。
2. 将整个文件夹完整解压。
3. 双击 `EVE PI Calculator.exe`。

支持 Windows 10/11 x64。发布包已包含 Java Runtime 和运行所需的 PI SDE 数据，无需另外安装 Java，也无需使用命令行。

## 功能

### Load Allocation

根据实际库存、EVE PI Planet Template 和每颗加工星的输入容量，计算可持续生产及材料装载分配。

- 粘贴并解析当前库存。
- 导入 EVE PI Planet Template，支持多颗加工星。
- 为每颗星单独设置可用材料容量。
- 根据模板中的工厂及 SDE 配方计算可持续生产。
- 在多颗行星之间分配共享 P2 库存。
- 显示 Runtime、Blocks 和 Expected Output。
- 显示每颗星应装载的 P2 与 P3 材料。
- 使用 Duplicate Planet 快速复制相同的加工星。
- 复制单颗行星的装载清单。

该功能使用已有行星模板进行计算，不会自动设计行星布局。

### Balance Inventory

粘贴库存并选择一个 P4 Product 后，程序会从 SDE 自动展开配方：

```text
Inventory + P4 Product
          ↓
      P4 → P3
      P4 → P3 → P2
          ↓
 P2 Balance / P3 Balance
```

P2 和 P3 库存完全独立配平：P2 只按 P2 比例计算，P3 只按 P3 比例计算；P3 缺口不会转换为额外的 P2 采购量。

每个配平结果会显示：

- Target Blocks
- Per Block
- Current
- Target
- Need To Add
- Additional Volume
- Equivalent P4 Output

### Recipe Hierarchy 与完整生产块

Recipe Summary 会直接展示 P4 到 P3、再到 P2 的层级，便于核对完整配方：

```text
P4
├─ P3
│  ├─ P2
│  └─ P2
└─ P3
   ├─ P2
   └─ P2
```

Balance Inventory 使用可实际执行的完整生产块，而不只是把材料约成最简比例：

- P3 Balance Block 对应完整的 P4 生产周期。
- P2 Balance Block 同时满足完整 P4 周期和完整 P3 生产批次。
- Equivalent P4 Output 始终按完整整数单位计算。

因此不会出现“材料比例看似正确，但实际工厂只能生产半个 P4”的结果。

## 使用方法

### Load Allocation

1. 粘贴库存并点击 Parse。
2. 添加 Planet。
3. 粘贴或加载 Planet Template。
4. 输入该行星可用的材料容量。
5. 如有相同加工星，可使用 Duplicate。
6. 点击 Calculate Allocation。
7. 查看每颗星的装载量、运行时间和预计产出。

### Balance Inventory

1. 粘贴库存并点击 Parse。
2. 从 P4 Product 下拉菜单选择产品。
3. 查看 Recipe Summary。
4. 点击 Calculate Balance。
5. 分别查看 P2 Balance 和 P3 Balance。
6. 使用 Copy Shopping List 复制采购清单。

## 数据

PI 产品与 schematic 数据来自 EVE Static Data Export（SDE）。仓库中的数据准备工具使用 [Fuzzwork SDE CSV conversion](https://www.fuzzwork.co.uk/dump/latest/csv/) 生成精简的 PI SQLite 数据库；Windows 便携包已内置运行所需的数据库。配方和产品查询以 EVE Type ID 为标识，不依赖硬编码产品名称。

## 当前不包含

- ECU / Extractor 自动规划
- 行星 CPU、Powergrid 或 Layout 自动设计
- 市场价格与利润计算
- POCO 税费计算
- 自动选择所需行星数量

## 从源码构建

项目使用 Java + Swing，不使用 Gradle 或 Maven。需要完整 JDK 21 或更高版本，并确保 `java`、`javac`、`jar` 和 `jpackage` 可用；正式 `v1.0.0` 便携包使用 JDK 25 构建。

运行编译与测试（Windows Bash / Git Bash）：

```bash
bash build.sh
```

在 Windows PowerShell 中构建并验证便携包：

```powershell
.\build-portable.ps1
```

也可以双击或运行 `build-portable.bat`。发布脚本会执行完整测试、生成自带 Java Runtime 的 app-image、打包 ZIP，并生成 SHA-256 校验文件。

## 项目状态

当前稳定版本为 [`v1.0.0`](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases/tag/v1.0.0)。正式构建和校验文件请以 [GitHub Releases](https://github.com/zx0003147/EVE-PI-Load-Calculator/releases) 为准。

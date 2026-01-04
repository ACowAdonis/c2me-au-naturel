# C2ME Au Naturel - Project Context

## Overview

This is a fork of C2ME-fabric (Concurrent Chunk Management Engine), a Fabric mod for Minecraft that improves chunk generation, I/O, and loading performance through parallel processing.

## Project Structure

```
c2me-au-naturel/
├── c2me-base/                    # Core module with common utilities and config system
├── c2me-opts-*/                  # Optimization modules
│   ├── c2me-opts-allocs/         # Allocation optimizations
│   ├── c2me-opts-chunkio/        # Chunk I/O optimizations
│   ├── c2me-opts-math/           # Math optimizations (Perlin noise, etc.)
│   ├── c2me-opts-scheduling/     # Scheduling optimizations
│   ├── c2me-opts-spawning/       # Spawn blacklist functionality
│   ├── c2me-opts-worldgen-*/     # World generation optimizations
│   └── c2me-opts-chunk-access/   # Chunk access optimizations
├── c2me-fixes-*/                 # Bug fix modules
├── c2me-rewrites-*/              # Rewritten subsystems
├── c2me-threading-*/             # Threading modules
├── c2me-notickvd/                # No-tick view distance
├── c2me-client-uncapvd/          # Uncapped client view distance
├── c2me-server-utils/            # Server utilities
├── buildSrc/                     # Gradle build utilities
└── tests/                        # Test modules
```

## Key Technologies

- **Fabric Mod Loader** - Minecraft modding framework
- **Mixin** - Bytecode manipulation for modifying Minecraft code
- **MixinExtras** - Extended mixin functionality
- **Yarn Mappings** - Deobfuscation mappings for Minecraft

## Configuration System

Configuration is handled via TOML files using night-config library.
- Main config: `c2me.toml` (generated at runtime)
- Module configs are defined in each module's `Config.java`

## Module System

Each module has:
- `ModuleEntryPoint.java` - Controls whether module is enabled
- `Config.java` - Module-specific configuration
- `mixin/` directory - Mixin classes for bytecode modification

Modules can be conditionally loaded based on config via `ModuleMixinPlugin`.

## Build Commands

```shell
./gradlew clean build          # Full build
./gradlew remapJar             # Build mod JAR
./gradlew test                 # Run tests
```

## Versioning

Version format: `{mod_version}.{commits_since_tag}[-dirty]`
- Base version in `gradle.properties`
- Commit count from `git describe --tags`

## Minecraft Version

Currently targeting Minecraft 1.20.1

## Upstream

- Original: https://github.com/RelativityMC/C2ME-fabric
- Author: ishland
- License: MIT

## Development Notes

- JDK 17+ required
- Uses Fabric Loom for development environment
- Access wideners in `src/main/resources/*.accesswidener`

# ctor-kotlin in Kotlin

[![GitHub link](https://img.shields.io/badge/GitHub-KotlinMania%2Fctor--kotlin-blue.svg)](https://github.com/KotlinMania/ctor-kotlin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/ctor-kotlin)](https://central.sonatype.com/artifact/io.github.kotlinmania/ctor-kotlin)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/ctor-kotlin/ci.yml?branch=main)](https://github.com/KotlinMania/ctor-kotlin/actions)

This is a Kotlin Multiplatform line-by-line transliteration port of [`mmastrac/linktime`](https://github.com/mmastrac/linktime).

**Original Project:** This port is based on [`mmastrac/linktime`](https://github.com/mmastrac/linktime). All design credit and project intent belong to the upstream authors; this repository is a faithful port to Kotlin Multiplatform with no behavioural changes intended.

### Porting status

This is an **in-progress port**. The goal is feature parity with the upstream Rust crate while providing a native Kotlin Multiplatform API. Every Kotlin file carries a `// port-lint: source <path>` header naming its upstream Rust counterpart so the AST-distance tool can track provenance.

---

## Upstream README — `mmastrac/linktime`

> The text below is reproduced and lightly edited from [`https://github.com/mmastrac/linktime`](https://github.com/mmastrac/linktime). It is the upstream project's own description and remains under the upstream authors' authorship; links have been rewritten to absolute upstream URLs so they continue to resolve from this repository.

## linktime

Cross-platform libraries for link-time initialization, finalization and collection in Rust.

![Build Status](https://github.com/mmastrac/linktime/actions/workflows/rust.yml/badge.svg)

| crate          | docs                                                                               | version                                                                                                 |
| -------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `linktime`     | [![docs.rs](https://docs.rs/linktime/badge.svg)](https://docs.rs/linktime)           | [![crates.io](https://img.shields.io/crates/v/linktime.svg)](https://crates.io/crates/linktime)       |
| `ctor`         | [![docs.rs](https://docs.rs/ctor/badge.svg)](https://docs.rs/ctor)                 | [![crates.io](https://img.shields.io/crates/v/ctor.svg)](https://crates.io/crates/ctor)                 |
| `dtor`         | [![docs.rs](https://docs.rs/dtor/badge.svg)](https://docs.rs/dtor)                 | [![crates.io](https://img.shields.io/crates/v/dtor.svg)](https://crates.io/crates/dtor)                 |
| `link-section` | [![docs.rs](https://docs.rs/link-section/badge.svg)](https://docs.rs/link-section) | [![crates.io](https://img.shields.io/crates/v/link-section.svg)](https://crates.io/crates/link-section) |

## Crates

The `linktime` project comprises three crates, and the top-level `linktime`
crate aggregates them all.

Pick-and-choose, or import the top-level crate to get all three.

## [`ctor`](https://github.com/mmastrac/linktime/blob/HEAD/ctor/)

Module initialization functions for Rust (like `__attribute__((constructor))` in C/C++).

Run code before `main` to initialize data, external resources, or other state.

```toml
[dependencies]
linktime = { version = "...", features = ["ctor"] }  # note: already enabled by default
# or
ctor = "..."
```

```rust
use linktime::ctor; // or ctor::ctor
use libc_print::*;

#[ctor(unsafe)]
fn foo() {
    libc_println!("Life before main!");
}
```

## [`dtor`](https://github.com/mmastrac/linktime/blob/HEAD/dtor/)

Module shutdown functions for Rust (like `__attribute__((destructor))`).

Run code after `main` to clean up resources, or perform other final operations.

```toml
[dependencies]
linktime = { version = "...", features = ["dtor"] }  # note: already enabled by default
# or
dtor = "..."
```

```rust
use linktime::dtor; // or dtor::dtor
use libc_print::*;

#[dtor(unsafe)]
fn foo() {
    libc_println!("Life after main!");
}
```

## [`link-section`](https://github.com/mmastrac/linktime/blob/HEAD/link-section/)

Typed and untyped link section support for Rust.

Collect related items from an entire linked binary into a single link section.

```toml
[dependencies]
linktime = { version = "...", features = ["link-section"] }  # note: already enabled by default
# or
link-section = "..."
```

```rust
use linktime::link_section::{section, in_section, TypedSection};
use linktime::ctor;
use libc_print::*;

#[section]
static FOO: TypedSection<fn()>;

#[in_section(FOO)]
fn foo() {
    libc_println!("Hello, world!");
}

#[ctor(unsafe)]
fn print_numbers() {
    for f in FOO {
        f();
    }
}
```

## Contributing

Contributions are welcome! 

## License

These projects are dual-licensed under the Apache License, Version 2.0 and the MIT License.

---

## About this Kotlin port

### Installation

```kotlin
dependencies {
    implementation("io.github.kotlinmania:ctor-kotlin:0.1.0")
}
```

### Building

```bash
./gradlew build
./gradlew test
```

### Targets

- macOS arm64
- Linux x64
- Windows mingw-x64
- iOS arm64 / simulator-arm64 (Swift export + XCFramework)
- JS (browser + Node.js)
- Wasm-JS (browser + Node.js)
- Android (API 24+)

### Porting guidelines

See [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) for translator discipline, port-lint header convention, and Rust → Kotlin idiom mapping.

### License

This Kotlin port is distributed under the same Apache-2.0 license as the upstream [`mmastrac/linktime`](https://github.com/mmastrac/linktime). See [LICENSE](LICENSE) (and any sibling `LICENSE-*` / `NOTICE` files mirrored from upstream) for the full text.

Original work copyrighted by the linktime authors.  
Kotlin port: Copyright (c) 2026 Sydney Renee and The Solace Project.

### Acknowledgments

Thanks to the [`mmastrac/linktime`](https://github.com/mmastrac/linktime) maintainers and contributors for the original Rust implementation. This port reproduces their work in Kotlin Multiplatform; bug reports about upstream design or behavior should go to the upstream repository.

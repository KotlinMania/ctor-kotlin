// port-lint: ignore
// Tracking file for the upstream `pub mod declarative` in src/lib.rs. The
// upstream submodule re-exports two macro_rules entry points under shorter
// names:
//
//   pub use crate::__support::ctor_parse as ctor;
//   #[cfg(feature = "dtor")]
//   pub use crate::__support::dtor_parse as dtor;
//
// Per the kotlinmania re-export discipline these are documented in prose, not
// reproduced as Kotlin `typealias` bindings. The destinations are the public
// runtime functions in the parent package:
//
//   io.github.kotlinmania.ctor.ctor
//   io.github.kotlinmania.ctor.dtor
//
// Translated upstream KDoc for the `declarative` submodule:
//
// Declarative forms of the `#[ctor]` and `#[dtor]` macros. The declarative
// forms wrap and parse a proc-macro-like syntax and are identical in
// expansion to the undecorated procedural macros; the declarative forms
// support the same attribute parameters as the procedural macros. The Kotlin
// port has no proc-macro/declarative-macro distinction because both forms
// collapse to the same runtime function call, so a caller that would write
// `ctor::declarative::ctor! { #[ctor] fn foo() { ... } }` in Rust simply
// writes `ctor { ... }` in Kotlin and imports the function from the parent
// package.
//
// Callers migrated:
//   (none yet — downstream `*-kotlin` consumers will be listed here as they
//   migrate off any future hypothetical declarative shim.)

package io.github.kotlinmania.ctor.declarative

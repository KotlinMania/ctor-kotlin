// port-lint: ignore
// Tracking file for the upstream declarative submodule in src/lib.rs. The
// upstream submodule re-exports two declarative-macro entry points under
// shorter names: the constructor parser is re-exported as the constructor
// short name, and (gated behind the destructor Cargo feature) the
// destructor parser is re-exported as the destructor short name.
//
// Per the kotlinmania re-export discipline these are documented in prose,
// not reproduced as Kotlin typealias bindings. The destinations are the
// public runtime functions in the parent package:
//
//   io.github.kotlinmania.ctor.ctor
//   io.github.kotlinmania.ctor.dtor
//
// Translated upstream KDoc for the declarative submodule:
//
// Declarative forms of the constructor and destructor attribute macros.
// The declarative forms wrap and parse a procedural-macro-like syntax and
// are identical in expansion to the undecorated procedural macros; the
// declarative forms support the same attribute parameters as the
// procedural macros. The Kotlin port has no procedural-macro and
// declarative-macro distinction because both forms collapse to the same
// runtime function call, so a caller that would write the declarative
// form in Rust simply writes the runtime call in Kotlin and imports the
// function from the parent package.
//
// Callers migrated:
//   (none yet — downstream Kotlin consumers will be listed here as they
//   migrate off any future hypothetical declarative shim.)

package io.github.kotlinmania.ctor.declarative

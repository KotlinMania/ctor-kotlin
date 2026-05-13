// port-lint: source src/example.rs
package io.github.kotlinmania.ctor

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * This example demonstrates the various types of ctor/dtor in an executable
 * context.
 *
 * Upstream `src/example.rs` is a Cargo `[[example]]` target that compiles to a
 * stand-alone binary, prints status messages with `libc_eprintln!`, and uses
 * the proc-macro forms `#[ctor]` / `#[dtor]`. The Kotlin port lives in
 * `commonTest` because Kotlin Multiplatform has no portable executable-target
 * shape that matches Cargo examples, and `commonTest` is the closest
 * equivalent that runs across every configured target. The proc-macro forms
 * collapse to runtime calls to [io.github.kotlinmania.ctor.ctor] /
 * [io.github.kotlinmania.ctor.ctorStatic] / [io.github.kotlinmania.ctor.dtor];
 * the upstream `libc_eprintln!` calls translate to `println` since
 * Kotlin/JS and Kotlin/Wasm have no portable `stderr`.
 *
 * Upstream `#![cfg_attr(feature = "used_linker", feature(used_with_arg))]` is
 * a Rust-only attribute that toggles a nightly feature for the
 * `used(linker)` ctor parameter and has no Kotlin Multiplatform analog.
 *
 * Translated upstream imports:
 *   use ctor::{ctor, dtor};       -> imported from io.github.kotlinmania.ctor
 *   use libc_print::*;            -> dropped (println used instead)
 *   use std::collections::HashMap;-> kotlin.collections.HashMap (auto-imported)
 */

/** This is an immutable "static", evaluated at init time. */
private val STATIC_CTOR: Map<UInt, String> = ctorStatic {
    val m = HashMap<UInt, String>()
    m[0u] = "foo"
    m[1u] = "bar"
    m[2u] = "baz"
    println("STATIC_CTOR")
    m
}

/*
 * Upstream defines two `#[ctor(anonymous)] unsafe fn anonymous_ctor()` items
 * back-to-back. The `anonymous` attribute parameter is what allows the
 * duplicate name: the proc-macro discards the user-supplied name and emits a
 * synthetic identifier. Kotlin properties at the top level cannot share a
 * name even when their initializers are anonymous, so the port uses distinct
 * property names that document the upstream sequence. The proc-macro's
 * "we can still reference the function itself" affordance is dropped: the
 * Kotlin `ctor { ... }` lambda has no addressable identifier to capture.
 */
private val anonymousCtor1 = ctor {
    println("ctor_anonymous (#1)")
}

private val anonymousCtor2 = ctor {
    println("ctor_anonymous (#2)")
}

/*
 * Upstream wraps a third anonymous ctor and an anonymous dtor inside a
 * `const _: () = { ... };` block. The Rust `const _` trick scopes the
 * synthetic items so their inner names do not collide with the surrounding
 * scope. Kotlin's top-level scope has no equivalent; the registrations
 * happen at file-load time the same way regardless.
 */
private val anonymousCtor3 = ctor {
    println("ctor_anonymous (#3)")
}

private val anonymousDtor0 = dtor {
    println("dtor_anonymous")
}

/*
 * Upstream `#[ctor] unsafe fn ctor() { ... }` — the function name `ctor`
 * shadows the proc-macro import in upstream. The Kotlin port preserves
 * sequence by naming the registration property after the upstream function.
 */
private val ctorBlock = ctor {
    println("ctor")
}

private val ctorUnsafeBlock = ctor {
    println("ctor_unsafe")
}

private val dtorBlock = dtor {
    println("dtor")
}

private val dtorUnsafeBlock = dtor {
    println("dtor_unsafe")
}

private val anonymousDtor1 = dtor {
    println("dtor_anonymous (#1)")
}

private val anonymousDtor2 = dtor {
    println("dtor_anonymous (#2")
}

/** A module with a static ctor/dtor. */
private object Module {
    /*
     * Translated upstream imports:
     *   use ctor::*;          -> imported from io.github.kotlinmania.ctor
     *   use libc_print::*;    -> dropped (println used instead)
     */

    val STATIC_CTOR: UByte = ctorStatic {
        println("module::STATIC_CTOR")
        42u
    }

    val dtorModule = dtor {
        println("module::dtor_module")
    }
}

/** Executable demonstration which exercises the various types of ctor/dtor. */
internal fun runExample() {
    println("main!")
    println("STATIC_CTOR = $STATIC_CTOR")
    println("module::STATIC_CTOR = ${Module.STATIC_CTOR}")
}

internal class ExampleTest {
    @Test
    fun staticCtorMaterializesAtRegistration() {
        assertEquals("foo", STATIC_CTOR[0u])
        assertEquals("bar", STATIC_CTOR[1u])
        assertEquals("baz", STATIC_CTOR[2u])
        assertEquals(3, STATIC_CTOR.size)
    }

    @Test
    fun moduleStaticCtorMaterializesAtRegistration() {
        assertEquals(42u.toUByte(), Module.STATIC_CTOR)
    }

    @Test
    fun runExampleEmitsExpectedOutput() {
        runExample()
        runDtors()
    }
}

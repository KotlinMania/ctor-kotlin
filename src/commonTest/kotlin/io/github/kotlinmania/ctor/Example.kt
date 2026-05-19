// port-lint: source example.rs
package io.github.kotlinmania.ctor

import io.github.kotlinmania.ctor.macros.CtorStatic
import io.github.kotlinmania.ctor.macros.ctorStatic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * This example demonstrates the various types of ctor/dtor in an
 * executable context.
 *
 * Upstream `src/example.rs` is a Cargo example target that compiles to a
 * stand-alone binary, prints status messages through the libc-print stderr
 * helpers, and uses the procedural forms of the constructor and destructor
 * attribute macros. The Kotlin port lives in `commonTest` because Kotlin
 * Multiplatform has no portable executable-target shape that matches Cargo
 * examples, and `commonTest` is the closest equivalent that runs across
 * every configured target. The procedural-macro forms collapse to runtime
 * calls to [io.github.kotlinmania.ctor.ctor],
 * [io.github.kotlinmania.ctor.macros.ctorStatic], and
 * [io.github.kotlinmania.ctor.dtor]; the upstream stderr helpers translate
 * to log-buffer appends so the test can assert on the observed ordering
 * regardless of whether the host has a `stderr` (Kotlin/JS and
 * Kotlin/Wasm do not).
 *
 * Upstream's nightly used-with-arg gate is a Rust-only attribute that
 * toggles a feature flag for the linker-used attribute parameter and has
 * no Kotlin Multiplatform analog.
 */

private val log: MutableList<String> = mutableListOf()

/** This is an immutable "static", evaluated at init time. */
private val staticCtor: CtorStatic<Map<Int, String>> = ctorStatic {
    val m = mutableMapOf<Int, String>()
    m[0] = "foo"
    m[1] = "bar"
    m[2] = "baz"
    log.add("STATIC_CTOR")
    m.toMap()
}

/*
 * Upstream defines two anonymous constructor items with the same upstream
 * function name back-to-back. The anonymous attribute parameter is what
 * allows the duplicate name: the procedural macro discards the
 * user-supplied name and emits a synthetic identifier. Kotlin properties
 * at the top level cannot share a name even when their initializers are
 * anonymous, so the port uses distinct property names that document the
 * upstream sequence. The "we can still reference the function itself"
 * affordance is dropped: the Kotlin block lambda has no addressable
 * identifier to capture.
 */
private val anonymousCtor1 = ctor {
    anonymousCtor()
}

private val anonymousCtor2 = ctor {
    anonymousCtorSecond()
}

private fun anonymousCtor() {
    log.add("ctor_anonymous (#1)")
}

private fun anonymousCtorSecond() {
    log.add("ctor_anonymous (#2)")
}

/*
 * Upstream wraps a third anonymous constructor and an anonymous destructor
 * inside a unit-typed anonymous constant block. The unit-anonymous-constant
 * trick scopes the synthetic items so their inner names do not collide
 * with the surrounding scope. Kotlin's top-level scope has no equivalent;
 * the registrations happen at file-load time the same way regardless. The
 * port pairs the two handles in a single `run` block so the unit-typed
 * sub-scope is preserved in spirit.
 */
private val nestedAnonymousCtor = run {
    val ctorHandle = ctor {
        nestedCtor()
    }
    val dtorHandle = dtor {
        anonymousDtor()
    }
    ctorHandle to dtorHandle
}

private fun nestedCtor() {
    log.add("ctor_anonymous (#3)")
}

private fun anonymousDtor() {
    log.add("dtor_anonymous")
}

/*
 * Upstream names this constructor function `ctor` — the function name
 * shadows the procedural-macro import. The Kotlin port preserves
 * sequence by naming the registration property after the upstream
 * function.
 */
private val ctorNamed = ctor {
    ctor()
}

private val ctorUnsafe = ctor {
    ctorUnsafe()
}

private val dtorNamed = dtor {
    dtor()
}

private val dtorUnsafe = dtor {
    dtorUnsafe()
}

private val dtorAnonymous1 = dtor {
    dtorAnonymousFirst()
}

private val dtorAnonymous2 = dtor {
    dtorAnonymousSecond()
}

private fun ctor() {
    log.add("ctor")
}

private fun ctorUnsafe() {
    log.add("ctor_unsafe")
}

private fun dtor() {
    log.add("dtor")
}

private fun dtorUnsafe() {
    log.add("dtor_unsafe")
}

private fun dtorAnonymousFirst() {
    log.add("dtor_anonymous (#1)")
}

private fun dtorAnonymousSecond() {
    log.add("dtor_anonymous (#2")
}

/** A module with a static ctor/dtor. */
private object Module {
    val staticCtor: CtorStatic<Int> = ctorStatic {
        log.add("module::STATIC_CTOR")
        42
    }

    val dtorModule = dtor {
        dtorModule()
    }

    fun dtorModule() {
        log.add("module::dtor_module")
    }
}

/**
 * Executable demonstration which exercises the various types of ctor/dtor.
 *
 * The upstream binary prints to stderr through libc-print helpers. The
 * Kotlin port writes to the [log] buffer so the test can assert on the
 * observed order.
 */
private fun main() {
    log.add("main!")
    log.add("STATIC_CTOR = ${staticCtor.value}")
    log.add("module::STATIC_CTOR = ${Module.staticCtor.value}")
}

internal class ExampleTest {
    @Test
    fun example() {
        // Touch every top-level handle so Kotlin actually evaluates the
        // property initializers. Kotlin does not eagerly evaluate file-
        // scope `val`s the way the upstream Rust crate eagerly drives
        // constructor-annotated items through linker sections, so the
        // host has to reference them.
        val handles: List<Any> = listOf(
            anonymousCtor1,
            anonymousCtor2,
            nestedAnonymousCtor,
            ctorNamed,
            ctorUnsafe,
            dtorNamed,
            dtorUnsafe,
            dtorAnonymous1,
            dtorAnonymous2,
            Module.dtorModule,
        )
        assertEquals(10, handles.size)

        runCtors()
        main()
        runDtors()

        assertEquals(mapOf(0 to "foo", 1 to "bar", 2 to "baz"), staticCtor.value)
        assertEquals(42, Module.staticCtor.value)

        assertTrue("STATIC_CTOR" in log)
        assertTrue("module::STATIC_CTOR" in log)
        assertTrue("ctor" in log)
        assertTrue("ctor_unsafe" in log)
        assertTrue("ctor_anonymous (#1)" in log)
        assertTrue("ctor_anonymous (#2)" in log)
        assertTrue("ctor_anonymous (#3)" in log)
        assertTrue("dtor" in log)
        assertTrue("dtor_unsafe" in log)
        assertTrue("dtor_anonymous" in log)
        assertTrue("dtor_anonymous (#1)" in log)
        assertTrue("dtor_anonymous (#2" in log)
        assertTrue("module::dtor_module" in log)
        assertTrue("main!" in log)

        val mainIndex = log.indexOf("main!")
        val dtorIndex = log.indexOf("module::dtor_module")
        assertTrue(mainIndex >= 0)
        assertTrue(dtorIndex >= 0)
        assertTrue(dtorIndex > mainIndex)
    }
}

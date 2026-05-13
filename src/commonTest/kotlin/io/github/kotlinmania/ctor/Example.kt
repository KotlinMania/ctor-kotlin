// port-lint: source src/example.rs
package io.github.kotlinmania.ctor

import io.github.kotlinmania.ctor.macros.CtorStatic
import io.github.kotlinmania.ctor.macros.ctorStatic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This example demonstrates the various types of ctor/dtor in an
 * executable context.
 */

private val log: MutableList<String> = mutableListOf()

/** This is an immutable static, evaluated at init time. */
private val staticCtor: CtorStatic<Map<Int, String>> = ctorStatic {
    val m = mutableMapOf<Int, String>()
    m[0] = "foo"
    m[1] = "bar"
    m[2] = "baz"
    log.add("STATIC_CTOR")
    m.toMap()
}

private val anonymousCtor1 = ctor {
    log.add("ctor_anonymous (#1)")
}

private val anonymousCtor2 = ctor {
    log.add("ctor_anonymous (#2)")
}

private val nestedAnonymousCtor = run {
    val ctorHandle = ctor {
        log.add("ctor_anonymous (#3)")
    }
    val dtorHandle = dtor {
        log.add("dtor_anonymous")
    }
    ctorHandle to dtorHandle
}

private val ctorNamed = ctor {
    log.add("ctor")
}

private val ctorUnsafe = ctor {
    log.add("ctor_unsafe")
}

private val dtorNamed = dtor {
    log.add("dtor")
}

private val dtorUnsafe = dtor {
    log.add("dtor_unsafe")
}

private val dtorAnonymous1 = dtor {
    log.add("dtor_anonymous (#1)")
}

private val dtorAnonymous2 = dtor {
    log.add("dtor_anonymous (#2")
}

/** A module with a static ctor/dtor. */
private object Module {
    val staticCtor: CtorStatic<Int> = ctorStatic {
        log.add("module::STATIC_CTOR")
        42
    }

    val dtorModule = dtor {
        log.add("module::dtor_module")
    }
}

/**
 * Executable main which demonstrates the various types of ctor/dtor.
 *
 * The upstream binary prints to stderr through `libc_println!`. The
 * Kotlin port writes to the [log] buffer so the test can assert on the
 * observed order.
 */
private fun runExample() {
    log.add("main!")
    log.add("STATIC_CTOR = ${staticCtor.value}")
    log.add("module::STATIC_CTOR = ${Module.staticCtor.value}")
}

internal class ExampleTest {
    @Test
    fun example() {
        // Touch every top-level handle so Kotlin actually evaluates the
        // property initializers. Kotlin does not eagerly evaluate file-
        // scope `val`s the way Rust eagerly drives `#[ctor]` items
        // through linker sections, so the host has to reference them.
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
        runExample()
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

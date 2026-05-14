// port-lint: source src/lib.rs
package io.github.kotlinmania.ctor

import io.github.kotlinmania.ctor.macros.CtorBlock
import io.github.kotlinmania.ctor.macros.DtorBlock
import io.github.kotlinmania.ctor.macros.Support

/**
 * Runtime forms of the constructor and destructor registration helpers.
 *
 * The upstream Rust crate exposes module initialization and teardown hooks
 * (analogous to constructor and destructor attributes in C/C++) for Linux,
 * OSX, and Windows via the [ctor] and [dtor] attribute macros.
 *
 * The upstream crate works and is regularly tested on Linux, OSX, and
 * Windows, with both statically and dynamically linked C runtime variants.
 * Other platforms are supported but not tested as part of the upstream
 * automatic builds. Upstream also works as expected in both executable and
 * shared library outputs: the constructor and destructor run at executable
 * or library startup and shutdown respectively.
 *
 * Kotlin Multiplatform has no equivalent of linker-section-driven pre-main
 * hooks on any target this repo ships. The Kotlin port therefore exposes
 * the same surface as a runtime registry: register a block with [ctor] (or
 * [dtor]) and trigger the registered queue with [runCtors] (or [runDtors]).
 * Consumers can register during normal top-level initialization (Kotlin
 * runs `val` and `init` expressions when the enclosing object is first
 * referenced), and trigger as early as the host wants — typically from the
 * top of `main` or from a test fixture's setup hook.
 *
 * Upstream sets a macro-expansion recursion limit of 256. The Kotlin port
 * has no macro expansion to limit.
 *
 * Upstream re-exports the support module at the crate root so that nested
 * macro expansions can reference helpers through a single path. The Kotlin
 * port has no nested macros; callers import members of
 * [io.github.kotlinmania.ctor.macros.Support] directly. The
 * [io.github.kotlinmania.ctor.declarative] subpackage is preserved as a
 * tracking ledger only, per the workspace re-export discipline.
 */

/**
 * Marks a function or static block as a library/executable constructor.
 *
 * Upstream uses platform-specific linker sections to call a specific
 * function at load time. The Kotlin port registers the block with the
 * global constructor registry and runs it from [runCtors].
 *
 * # Important notes
 *
 * The Rust standard library makes no guarantees about life-before or
 * life-after main. This means that the upstream crate may not work as
 * expected in some cases, such as when used in an asynchronous runtime or
 * making use of standard-library services. In the Kotlin port there is no
 * life-before-main at all: registered blocks only run when [runCtors] is
 * called, so the order of effects is fully under the host's control.
 *
 * Multiple startup blocks are supported, but the invocation order is not
 * guaranteed by upstream. The Kotlin port runs them in registration order
 * to match the order in which the registry was populated, but consumers
 * should not rely on it.
 *
 * The upstream crate assumes it is available as a direct dependency. If a
 * caller re-exports its items as part of its own crate, the upstream macro
 * exposes a crate-path parameter that redirects the macro's output to the
 * correct crate. The Kotlin port has no macro expansion to redirect.
 *
 * # Attribute parameters
 *
 *  - A crate-path override: the path to the support module containing the
 *    helpers. Useful in Rust when re-exporting items; not applicable in
 *    Kotlin.
 *  - A linker-used flag: marks the function as being used in the link
 *    phase. Not applicable in Kotlin.
 *  - A link-section override: the section to place the constructor in.
 *    Not applicable in Kotlin.
 *  - An anonymous flag: omits the constructor's name in the generated
 *    code (allows multiple constructors with the same name). In Kotlin
 *    every registration is anonymous to the registry; the returned
 *    [CtorBlock] handle is the only identity.
 *
 * # Examples
 *
 * Print a startup message:
 *
 *     val helloCtor = ctor {
 *         println("Hello, world!")
 *     }
 *
 *     fun main() {
 *         runCtors()
 *         println("main()")
 *     }
 *
 * Make changes to a shared variable:
 *
 *     val inited = AtomicReference(false)
 *
 *     val setInited = ctor {
 *         inited.store(true)
 *     }
 *
 * Initialize a Map at startup time:
 *
 *     val staticCtor: CtorStatic<Map<Int, String>> = ctorStatic {
 *         buildMap {
 *             for (i in 0 until 100) {
 *                 put(i, "x*100=${i * 100}")
 *             }
 *         }
 *     }
 *
 * # Details
 *
 * The upstream constructor macro makes use of linker sections to ensure
 * that a function is run at startup time, with the section name varying
 * by target operating system (init-array on Linux, the mod-init-func data
 * section on Apple, the C runtime init section on Windows, the ctors
 * section on Xtensa, and so on).
 *
 * The Kotlin port has no linker section. Each [ctor] call appends a block
 * to a process-wide registry, and [runCtors] iterates it.
 *
 * For static items, upstream generates a lock-once container that is
 * initialized at startup time. The Kotlin port models that shape with
 * [io.github.kotlinmania.ctor.macros.CtorStatic]: constructing the handle
 * registers a synthetic constructor that forces the lock, and the value
 * accessor lazily forces the initializer on first read.
 */
public fun ctor(block: () -> Unit): CtorBlock = Support.ctorEntry(block)

/**
 * Marks a function as a library/executable destructor.
 *
 * Upstream re-exports the destructor attribute macro from a separate
 * companion crate. The Kotlin port owns the destructor surface directly;
 * there is no separate destructor sibling repository because the upstream
 * companion crate provides only the attribute macro and re-exports the
 * same support module already covered by [Support].
 *
 * The Kotlin port runs destructors in last-in-first-out order from
 * [runDtors] to mirror the C runtime at-exit contract upstream binds
 * against.
 */
public fun dtor(block: () -> Unit): DtorBlock = Support.dtorEntry(block)

/**
 * Run every registered constructor that has not already fired.
 *
 * Kotlin's runtime has no pre-main loader hook on any target this repo
 * ships, so the host has to trigger the registry explicitly. Typical
 * placement is the first line of `main`, or the setup hook of a test
 * fixture.
 */
public fun runCtors(): Unit = Support.runCtors()

/**
 * Run every registered destructor that has not already fired, in reverse
 * registration order.
 *
 * Equivalent to invoking at-exit-registered callbacks at shutdown time.
 * The Kotlin port surfaces this as an explicit teardown entrypoint; there
 * is no shutdown hook on most Kotlin Multiplatform targets.
 */
public fun runDtors(): Unit = Support.runDtors()

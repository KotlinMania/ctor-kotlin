// port-lint: source src/lib.rs
package io.github.kotlinmania.ctor

import io.github.kotlinmania.ctor.macros.CtorBlock
import io.github.kotlinmania.ctor.macros.DtorBlock
import io.github.kotlinmania.ctor.macros.Support

/**
 * Runtime forms of the constructor and destructor registration helpers.
 *
 * The upstream Rust crate exposes module initialization/teardown hooks
 * (like `__attribute__((constructor))` in C/C++) for Linux, OSX, and
 * Windows via the [ctor] and [dtor] attribute macros.
 *
 * The upstream crate works and is regularly tested on Linux, OSX, and
 * Windows, with both `+crt-static` and `-crt-static`. Other platforms are
 * supported but not tested as part of the upstream automatic builds.
 * Upstream also works as expected in both `bin` and `cdylib` outputs:
 * the ctor and dtor run at executable or library startup/shutdown
 * respectively.
 *
 * Kotlin Multiplatform has no equivalent of linker-section-driven
 * pre-main hooks on any target this repo ships. The Kotlin port
 * therefore exposes the same surface as a runtime registry: register a
 * block with [ctor] (or [dtor]), and trigger the registered queue with
 * [runCtors] (or [runDtors]). Consumers can register during normal
 * top-level initialization (Kotlin runs `val`/`init` expressions when
 * the enclosing object is first referenced), and trigger as early as
 * the host wants — typically from the top of `main` or from a test
 * fixture's setup hook.
 *
 * Upstream sets a recursion limit of `256` on the macro expansion. The
 * Kotlin port has no macro expansion to limit.
 */

// Re-export tracking: `pub use macros::__support;`
//
// Upstream re-exports the support module so that nested macro expansions
// can reference helpers through a single `crate::__support` path. Kotlin
// callers should import members of [io.github.kotlinmania.ctor.macros.Support]
// directly instead of routing through this file.
//
// Callers migrated:

/**
 * Declarative forms of the [ctor] and [dtor] macros.
 *
 * The declarative forms wrap and parse a proc-macro-like syntax and are
 * identical in expansion to the undecorated procedural macros. The
 * declarative forms support the same attribute parameters as the
 * procedural macros.
 *
 *     io.github.kotlinmania.ctor.Declarative.ctor {
 *         println("Hello, world!")
 *     }
 *
 *     // ... the above is identical to:
 *
 *     io.github.kotlinmania.ctor.ctor {
 *         println("Hello, world!")
 *     }
 */
public object Declarative {
    /**
     * Declarative-form constructor registration. Equivalent to the
     * top-level [ctor] function.
     *
     * The Kotlin port collapses the macro parse step in [Support.ctorParse]
     * directly onto the runtime [Support.ctorEntry] call.
     */
    public fun ctor(block: () -> Unit): CtorBlock = Support.ctorParse(block)

    /**
     * Declarative-form destructor registration. Equivalent to the
     * top-level [dtor] function.
     *
     * Available unconditionally in the Kotlin port; upstream gates this
     * behind the `dtor` Cargo feature.
     */
    public fun dtor(block: () -> Unit): DtorBlock = Support.dtorParse(block)
}

/**
 * Marks a function or static block as a library/executable constructor.
 *
 * Upstream uses platform-specific linker sections to call a specific
 * function at load time. The Kotlin port registers the block with the
 * global constructor registry and runs it from [runCtors].
 *
 * # Important notes
 *
 * The Rust stdlib makes no guarantees about library support for
 * life-before or life-after main. This means that the ctor crate may
 * not work as expected in some cases, such as when used in an async
 * runtime or making use of stdlib services. In the Kotlin port there is
 * no life-before-main at all: registered blocks only run when
 * [runCtors] is called, so the order of effects is fully under the
 * host's control.
 *
 * Multiple startup blocks are supported, but the invocation order is
 * not guaranteed by upstream. The Kotlin port runs them in registration
 * order to match the order in which the registry was populated, but
 * consumers should not rely on it.
 *
 * The upstream ctor crate assumes it is available as a direct
 * dependency, with `extern crate ctor`. If a caller re-exports ctor
 * items as part of its crate, the `crate_path` parameter redirects the
 * macro's output to the correct crate. The Kotlin port has no macro
 * expansion to redirect.
 *
 * # Attribute parameters
 *
 *  - `crate_path = ::path::to::ctor::crate`: the path to the ctor crate
 *    containing the support macros. Useful in Rust when re-exporting
 *    ctor items; not applicable in Kotlin.
 *  - `used(linker)`: (Advanced) mark the function as being used in the
 *    link phase. Not applicable in Kotlin.
 *  - `link_section = "section"`: the section to place the constructor
 *    in. Not applicable in Kotlin.
 *  - `anonymous`: do not give the constructor a name in the generated
 *    code (allows multiple constructors with the same name). In Kotlin
 *    every registration is anonymous to the registry; the returned
 *    handle is the only identity.
 *
 * # Examples
 *
 * Print a startup message:
 *
 *     io.github.kotlinmania.ctor.ctor {
 *         println("Hello, world!")
 *     }
 *
 *     fun main() {
 *         io.github.kotlinmania.ctor.runCtors()
 *         println("main()")
 *     }
 *
 * Make changes to a shared variable:
 *
 *     val inited: kotlin.concurrent.atomics.AtomicReference<Boolean> =
 *         kotlin.concurrent.atomics.AtomicReference(false)
 *
 *     io.github.kotlinmania.ctor.ctor {
 *         inited.store(true)
 *     }
 *
 * Initialize a Map at startup time:
 *
 *     val staticCtor: io.github.kotlinmania.ctor.macros.CtorStatic<Map<Int, String>> =
 *         io.github.kotlinmania.ctor.macros.ctorStatic {
 *             buildMap {
 *                 for (i in 0 until 100) {
 *                     put(i, "x*100=${i * 100}")
 *                 }
 *             }
 *         }
 *
 * # Details
 *
 * The upstream `#[ctor]` macro makes use of linker sections to ensure
 * that a function is run at startup time:
 *
 *     io.github.kotlinmania.ctor.ctor {
 *         /* ... */
 *     }
 *
 * The above example translates into the following Rust code
 * (approximately):
 *
 *     // #[used]
 *     // #[cfg_attr(target_os = "linux", link_section = ".init_array")]
 *     // #[cfg_attr(target_vendor = "apple", link_section = "__DATA,__mod_init_func,mod_init_funcs")]
 *     // #[cfg_attr(target_os = "windows", link_section = ".CRT$XCU")]
 *     // /* ... other platforms elided ... */
 *     // static INIT_FN: extern fn() = {
 *     //     extern fn init_fn() { my_init_fn(); }
 *     //     init_fn
 *     // };
 *
 * The Kotlin port has no linker section. Each [ctor] call appends a
 * block to a process-wide registry, and [runCtors] iterates it.
 *
 * For static items, upstream generates a `OnceLock` that is initialized
 * at startup time. The Kotlin port models that shape with
 * [io.github.kotlinmania.ctor.macros.CtorStatic]:
 *
 *     val foo: io.github.kotlinmania.ctor.macros.CtorStatic<Map<Int, String>> =
 *         io.github.kotlinmania.ctor.macros.ctorStatic {
 *             buildMap {
 *                 for (i in 0 until 100) {
 *                     put(i, "x*100=${i * 100}")
 *                 }
 *             }
 *         }
 *
 * The above example translates into the following Rust code
 * (approximately), which eagerly initializes the Map inside a
 * `OnceLock` at startup time:
 *
 *     // static FOO: FooStatic = FooStatic { value: ::std::sync::OnceLock::new() };
 *     // struct FooStatic { value: ::std::sync::OnceLock<HashMap<u32, String>> }
 *     // impl ::std::ops::Deref for FooStatic {
 *     //     type Target = HashMap<u32, String>;
 *     //     fn deref(&self) -> &Self::Target {
 *     //         self.value.get_or_init(|| unsafe { /* init body */ })
 *     //     }
 *     // }
 *     //
 *     // #[ctor]
 *     // unsafe fn init_foo_ctor() { _ = &*FOO; }
 *
 * In the Kotlin port the [io.github.kotlinmania.ctor.macros.CtorStatic]
 * handle owns its own OnceLock-equivalent and registers a poke into the
 * shared constructor registry from its constructor.
 */
public fun ctor(block: () -> Unit): CtorBlock = Support.ctorEntry(block)

/**
 * Marks a function as a library/executable destructor.
 *
 * Upstream re-exports the `#[dtor]` proc-macro from the separate `dtor`
 * crate. The Kotlin port owns the destructor surface directly; there is
 * no separate `dtor-kotlin` repository because the `dtor` crate
 * provides only the proc-macro and re-exports the same support module
 * already covered by [Support].
 *
 * The Kotlin port runs destructors in LIFO order from [runDtors] to
 * mirror the C `atexit` contract upstream binds against.
 */
public fun dtor(block: () -> Unit): DtorBlock = Support.dtorEntry(block)

/**
 * Run every registered constructor that has not already fired.
 *
 * Kotlin's runtime has no `__attribute__((constructor))`-equivalent
 * pre-main hook on any target this repo ships, so the host has to
 * trigger the registry explicitly. Typical placement is the first line
 * of `main`, or the setup hook of a test fixture.
 */
public fun runCtors(): Unit = Support.runCtors()

/**
 * Run every registered destructor that has not already fired, in
 * reverse registration order.
 *
 * Equivalent to invoking `atexit`-registered callbacks at shutdown
 * time. The Kotlin port surfaces this as an explicit teardown
 * entrypoint; there is no shutdown hook on most KMP targets.
 */
public fun runDtors(): Unit = Support.runDtors()

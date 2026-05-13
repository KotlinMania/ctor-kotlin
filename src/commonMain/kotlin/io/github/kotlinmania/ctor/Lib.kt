// port-lint: source src/lib.rs
package io.github.kotlinmania.ctor

import io.github.kotlinmania.ctor.macros.Support

/*
 * Runtime port of the upstream `ctor` crate's module-level documentation:
 *
 * Procedural macro for defining global constructor/destructor functions.
 *
 * Upstream provides module initialization/teardown functions for Rust (like
 * `__attribute__((constructor))` in C/C++) for Linux, OSX, and Windows via
 * the `#[ctor]` and `#[dtor]` macros. Upstream works and is regularly tested
 * on Linux, OSX and Windows, with both `+crt-static` and `-crt-static`. Other
 * platforms are supported but not tested as part of upstream's automatic
 * builds. The upstream crate also works as expected in both `bin` and
 * `cdylib` outputs: the `ctor` and `dtor` run at executable or library
 * startup/shutdown respectively.
 *
 * Upstream currently requires Rust > `1.31.0` at a minimum for the
 * procedural macro support; the upstream sets `recursion_limit = "256"` to
 * accommodate the macro_rules-driven attribute parsing.
 *
 * Kotlin Multiplatform has no loader-level hook portable across the supported
 * targets (JVM, Kotlin/Native, Kotlin/JS, Kotlin/Wasm), so this port replaces
 * the proc-macro/macro_rules surface with a runtime registry. The public API
 * is two functions, [ctor] and [dtor], plus the manual drain entry point
 * [runDtors]. The internal registry lives in [io.github.kotlinmania.ctor.macros.Support].
 *
 * Upstream re-exports `macros::__support` at the crate root (`pub use
 * macros::__support`); per the kotlinmania re-export discipline that
 * re-export is documented here in prose rather than reproduced as a Kotlin
 * `typealias`.
 */

/**
 * Marks a block as a library/executable constructor.
 *
 * Upstream Rust uses OS-specific linker sections to call a specific function
 * at load time. In Kotlin Multiplatform there is no portable equivalent, so
 * this function invokes [block] synchronously on the calling thread and also
 * records it in the global ctor registry. Callers that want lazy-on-first-use
 * semantics for a static value should place the [ctor] call inside a
 * top-level `val` initializer; the Kotlin runtime will then defer execution
 * until the property is first read on JS and Wasm, and eagerly run it on
 * Kotlin/Native and Android initialization on the JVM.
 *
 * Important notes (translated upstream):
 *
 * Rust does not make any guarantees about stdlib support for life-before or
 * life-after main. This means that the upstream `ctor` crate may not work as
 * expected in some cases, such as when used in an `async` runtime or making
 * use of stdlib services. The Kotlin port has the same caveat in spirit:
 * blocks registered with [ctor] should be minimal and free of coroutine
 * dispatchers or platform services that may not yet be initialized.
 *
 * Multiple startup blocks are supported, but the invocation order is not
 * guaranteed.
 *
 * Upstream supports a `crate_path` attribute parameter that redirects the
 * proc-macro's generated code to a re-exported crate. The Kotlin port is a
 * plain function call, so that knob has no analog.
 *
 * Attribute parameters supported by the upstream `#[ctor]` proc-macro:
 *
 *  - `crate_path = ::path::to::ctor::crate`: redirects the macro's output to
 *    a re-exported crate (no Kotlin analog; the function call is direct).
 *  - `used(linker)`: (advanced) marks the function as being used in the link
 *    phase (no Kotlin analog).
 *  - `link_section = "section"`: pins the constructor to a specific linker
 *    section (no Kotlin analog).
 *  - `anonymous`: omits the constructor's name in the generated code, which
 *    allows multiple constructors with the same name. Kotlin allows multiple
 *    `val` initializers in the same file without naming conflicts, so the
 *    `anonymous` form has no separate Kotlin shape.
 *
 * Examples (translated from upstream KDoc):
 *
 * Print a startup message:
 *
 * ```kotlin
 * val helloCtor = ctor {
 *     println("Hello, world!")
 * }
 * ```
 *
 * Make changes to "static" values:
 *
 * ```kotlin
 * val inited = atomic(false)
 *
 * val setInited = ctor {
 *     inited.store(true)
 * }
 * ```
 *
 * Initialize a `Map` at startup time:
 *
 * ```kotlin
 * val staticCtor: Map<UInt, String> = ctorStatic {
 *     val m = HashMap<UInt, String>()
 *     for (i in 0u..99u) {
 *         m[i] = "x*100=${i * 100u}"
 *     }
 *     m
 * }
 * ```
 *
 * Upstream details: the `#[ctor]` macro makes use of linker sections to
 * ensure that a function is run at startup time. Approximately:
 *
 * ```text
 * #[used]
 * #[cfg_attr(target_os = "linux", link_section = ".init_array")]
 * #[cfg_attr(target_vendor = "apple", link_section = "__DATA,__mod_init_func,mod_init_funcs")]
 * #[cfg_attr(target_os = "windows", link_section = ".CRT$XCU")]
 * /* ... other platforms elided ... */
 * static INIT_FN: extern fn() = {
 *     extern fn init_fn() { myInitFn() }
 *     init_fn
 * }
 * ```
 *
 * For `static` items, the upstream macro generates a
 * `std::sync::OnceLock` that is initialized at startup time. The Kotlin
 * equivalent of `OnceLock` is `kotlin.lazy { ... }`, which [ctorStatic]
 * wraps with an eager invocation so that initialization happens at the
 * registration call site instead of on first access.
 */
public fun ctor(block: () -> Unit) {
    Support.ctorParse(block)
}

/**
 * Eagerly initialize a "static" value and record the initializer in the ctor
 * registry, mirroring upstream's `#[ctor] static FOO: T = unsafe { ... };`
 * pattern.
 *
 * Upstream's macro generates a `std::sync::OnceLock<T>` plus a
 * `Deref` impl that invokes the initializer on first access, then registers
 * a synthetic ctor that forces the lock to populate at startup time so the
 * value is effectively eagerly initialized. The Kotlin equivalent is simpler:
 * the initializer runs immediately when [ctorStatic] is called, the value is
 * returned for direct assignment to a `val`, and the same block is also
 * recorded with [Support.ctorParse] so the global ctor list reflects it.
 *
 * Example:
 *
 * ```kotlin
 * val staticCtor: Map<UInt, String> = ctorStatic {
 *     val m = HashMap<UInt, String>()
 *     for (i in 0u..99u) {
 *         m[i] = "x*100=${i * 100u}"
 *     }
 *     m
 * }
 * ```
 */
public fun <T> ctorStatic(initializer: () -> T): T {
    val value = initializer()
    Support.recordCtorOnly { /* already invoked above */ }
    return value
}

/**
 * Marks a block as a library/executable destructor, mirroring upstream's
 * `#[dtor]` proc-macro that is re-exported from the `dtor` crate as
 * `dtor::__dtor_from_ctor`.
 *
 * Upstream registers the destructor with the platform's at-exit machinery —
 * `atexit` on most platforms, `__cxa_atexit` scoped to the DSO on Apple
 * targets — so the block runs after `main()` returns. There is no portable
 * at-exit hook across the Kotlin Multiplatform target set, so this port
 * records the block in a LIFO queue that callers drain explicitly via
 * [runDtors] (typically from a test-harness shutdown step or an explicit
 * teardown function).
 *
 * Example:
 *
 * ```kotlin
 * val cleanup = dtor {
 *     println("goodbye")
 * }
 *
 * // Later, when the test harness or main() is winding down:
 * runDtors()
 * ```
 */
public fun dtor(block: () -> Unit) {
    Support.dtorParse(block)
}

/**
 * Drain the registered dtor queue in last-in-first-out order.
 *
 * This is the Kotlin port's manual replacement for `atexit`. Upstream Rust
 * has no equivalent public function because dtors are wired into the
 * platform loader's shutdown sequence; in Kotlin Multiplatform the caller
 * owns the shutdown sequence and decides when destructors fire.
 *
 * The drain clears the registry as a side effect; a second call is a no-op
 * until new dtors have been registered. LIFO ordering matches upstream
 * because `atexit` runs registered callbacks in reverse registration order:
 * destructors for statics initialized later tear down first.
 */
public fun runDtors() {
    Support.runDtors()
}

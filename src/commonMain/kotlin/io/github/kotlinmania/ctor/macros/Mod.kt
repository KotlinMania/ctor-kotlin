// port-lint: source src/macros/mod.rs
package io.github.kotlinmania.ctor.macros

import kotlin.concurrent.atomics.AtomicReference

/**
 * Internal support surface for `ctor-kotlin`.
 *
 * Upstream Rust exposes this as `pub mod __support` and re-exports a bundle of
 * `macro_rules!` helpers: `ctor_call`, `ctor_entry`, `ctor_link_section`,
 * `ctor_link_section_attr`, `ctor_parse`, `dtor_entry`, `dtor_parse`,
 * `if_has_feature`, `if_unsafe`, `include_no_warn_on_missing_unsafe_feature`,
 * `include_used_linker_feature`, `unify_features`.
 *
 * Every one of those Rust items is a declarative macro whose entire job is
 * compile-time code generation: pattern-match a `#[ctor]`/`#[dtor]` annotated
 * item, synthesise an `extern "C" fn` wrapper, drop it into the right
 * platform-specific linker section, and on Windows ensure that wrapper returns
 * `usize`. None of this has a Kotlin Multiplatform equivalent: the JVM,
 * Kotlin/Native, Kotlin/JS and Kotlin/Wasm targets have no portable
 * "function lives in `.init_array` / `__mod_init_func` / `.CRT$XCU` and gets
 * invoked by the loader" mechanism. So the macro_rules collapse, in Kotlin,
 * to a single runtime registry that the public [io.github.kotlinmania.ctor.ctor]
 * and [io.github.kotlinmania.ctor.dtor] functions delegate to.
 *
 * The Rust `CtorRetType` alias (`usize` on Windows outside Miri, `()`
 * elsewhere) exists for the same reason: a Windows `.CRT$XIA..XIZ`
 * constructor must return `usize`. Kotlin's runtime registry hands off to a
 * `() -> Unit` lambda directly, so the alias has no Kotlin counterpart.
 *
 * See <https://learn.microsoft.com/en-us/cpp/c-runtime-library/reference/initterm-initterm-e?view=msvc-170>
 * for the upstream Windows-init reference, and the upstream `macros/mod.rs`
 * for the original macro_rules patterns.
 *
 * Code note (translated from the upstream `macros/mod.rs`): you might wonder
 * why upstream Rust doesn't use `__attribute__((destructor))` for `dtor`.
 * Unfortunately mingw doesn't appear to properly support section-based hooks
 * for shutdown (see
 * <https://github.com/Alexpux/mingw-w64/blob/d0d7f784833bbb0b2d279310ddc6afb52fe47a46/mingw-w64-crt/crt/crtdll.c>),
 * and OSX has removed support for section-based shutdown hooks after warning
 * about it for a number of years (see <https://reviews.llvm.org/D45578>).
 * The Kotlin port sidesteps the entire question because it has no loader-level
 * hooks at all; [io.github.kotlinmania.ctor.dtor] callbacks accumulate in a
 * LIFO queue that callers drain by invoking
 * [io.github.kotlinmania.ctor.runDtors] explicitly.
 */
internal object Support {
    private val ctors: AtomicReference<List<() -> Unit>> = AtomicReference(emptyList())
    private val dtors: AtomicReference<List<() -> Unit>> = AtomicReference(emptyList())

    /**
     * Parse a `#[ctor]`-annotated item as if it were a proc-macro. Upstream
     * `ctor_parse!` (and the `ctor!` re-export in
     * [io.github.kotlinmania.ctor.declarative]) walks the attribute parameters,
     * unifies them with any active crate features via `unify_features!`, and
     * forwards to `ctor_entry!`, which emits an `extern "C" fn` wrapper, drops
     * it into the platform's loader-init section via
     * `ctor_link_section!`/`ctor_link_section_attr!`, and on Windows guards the
     * return type with `CtorRetType`.
     *
     * In Kotlin all of that collapses to a single registration call: append
     * the block to the global ctor list and invoke it eagerly. Kotlin has no
     * load-time hook portable across the supported targets, so eager
     * invocation at the call site (typically a top-level `val` initializer or
     * an `init` block in an object) is the closest approximation to Rust's
     * "run at executable/library startup."
     */
    fun ctorParse(block: () -> Unit) {
        record(ctors, block)
        block()
    }

    /**
     * Record a block in the ctor registry without invoking it. Used by the
     * static-form helper [io.github.kotlinmania.ctor.ctorStatic], where the
     * initializer has already been evaluated to materialise the static's
     * value before the registration happens.
     *
     * Upstream's `#[ctor] static FOO` lowers to a synthetic
     * `extern "C" fn` whose body forces the `OnceLock` to initialise. After
     * eager evaluation in Kotlin the synthetic ctor would be a no-op, but
     * registering it preserves upstream's invariant that every `#[ctor]`
     * item is enumerable in the ctor list.
     */
    fun recordCtorOnly(block: () -> Unit) {
        record(ctors, block)
    }

    /**
     * Parse a `#[dtor]`-annotated item as if it were a proc-macro. Upstream
     * `dtor_parse!` is symmetric with `ctor_parse!`: it walks attribute
     * parameters, unifies features, forwards to `dtor_entry!`, and registers
     * the callback with the platform's at-exit machinery (`atexit` on most
     * platforms, `__cxa_atexit` scoped to the DSO on Apple targets).
     *
     * In Kotlin the registry merely appends the block to the dtor list. There
     * is no portable at-exit hook across JVM, Kotlin/Native, JS and Wasm, so
     * callers invoke [io.github.kotlinmania.ctor.runDtors] explicitly when
     * teardown is appropriate (typically from a test-harness shutdown step).
     */
    fun dtorParse(block: () -> Unit) {
        record(dtors, block)
    }

    /**
     * Drain and invoke every registered dtor in last-in-first-out order. The
     * registry is cleared as a side effect; a second call is a no-op until
     * new dtors have been registered.
     *
     * LIFO matches the upstream behavior: Rust's `atexit` runs registered
     * callbacks in reverse registration order, so destructors of statics
     * initialized later tear down first.
     */
    fun runDtors() {
        val drained = dtors.exchange(emptyList())
        drained.asReversed().forEach { it() }
    }

    private fun record(slot: AtomicReference<List<() -> Unit>>, block: () -> Unit) {
        while (true) {
            val current = slot.load()
            if (slot.compareAndSet(current, current + block)) return
        }
    }
}

// port-lint: source src/macros/mod.rs
package io.github.kotlinmania.ctor.macros

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * Doc-hidden support module backing the [ctor] and [dtor] runtime registration
 * APIs.
 *
 * The upstream Rust crate emits inline expansions of the [ctor]/[dtor]
 * attribute macros that wire a function into the target platform's linker
 * sections (`.init_array` on Linux, `__DATA,__mod_init_func,mod_init_funcs`
 * on Apple, `.CRT$XCU` on Windows, `.text.startup`/`.text.exit` for
 * shutdown). Kotlin Multiplatform has no equivalent linker hook: code that
 * runs strictly before main is unsupported across every target this repo
 * ships, and most stdlib services are not safe to call from such a hook
 * even where the platform exposes one. The Kotlin port therefore translates
 * each attribute macro into a runtime registration call. Consumers register
 * blocks during normal module initialization (or eagerly at the top of
 * `main`) and trigger them with [runCtors]. Destructors mirror the C
 * `atexit` contract and are released in LIFO order from [runDtors].
 *
 * `pub use crate::__ctor_call as ctor_call;`
 * `pub use crate::__ctor_entry as ctor_entry;`
 * `pub use crate::__ctor_link_section as ctor_link_section;`
 * `pub use crate::__ctor_link_section_attr as ctor_link_section_attr;`
 * `pub use crate::__ctor_parse as ctor_parse;`
 * `pub use crate::__dtor_entry as dtor_entry;`
 * `pub use crate::__dtor_parse as dtor_parse;`
 * `pub use crate::__if_has_feature as if_has_feature;`
 * `pub use crate::__if_unsafe as if_unsafe;`
 * `pub use crate::__include_no_warn_on_missing_unsafe_feature as include_no_warn_on_missing_unsafe_feature;`
 * `pub use crate::__include_used_linker_feature as include_used_linker_feature;`
 * `pub use crate::__unify_features as unify_features;`
 */
public object Support {
    private val ctorBlocks: AtomicReference<List<CtorBlock>> = AtomicReference(emptyList())
    private val dtorBlocks: AtomicReference<List<DtorBlock>> = AtomicReference(emptyList())

    /**
     * Return type for the constructor. Why is this needed?
     *
     * On Windows, `.CRT$XIA` … `.CRT$XIZ` constructors are required to return
     * an unsigned integer value. The Rust crate cannot know whether the user
     * is putting this function into a retval-requiring section or a
     * non-retval section, so it just returns an unsigned integer value which
     * is always valid and just ignored if not needed.
     *
     * Miri is pedantic about this, so the upstream crate returns `Unit` if
     * running under miri. The Kotlin port has no linker section to satisfy,
     * so the return type collapses to [Unit] on every target.
     *
     * See [initterm](https://learn.microsoft.com/en-us/cpp/c-runtime-library/reference/initterm-initterm-e?view=msvc-170)
     */
    public fun ctorRetType(): Unit = Unit

    /**
     * Parse a `ctor`-annotated item as if it were a proc-macro.
     *
     * In Rust this is the declarative form of the `ctor` attribute macro
     * that supports both function and static-item shapes. In Kotlin the
     * declarative form has no syntactic counterpart: every registration —
     * whether of an init function or of a lazily computed static — funnels
     * through [ctorEntry]. Static-item registrations therefore take a
     * computation lambda whose result is observable through the returned
     * [CtorStatic] handle once [runCtors] has fired.
     */
    public fun ctorParse(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * Parse a `dtor`-annotated item as if it were a proc-macro.
     *
     * As with [ctorParse] the declarative and attribute forms collapse to a
     * single runtime registration in Kotlin.
     */
    public fun dtorParse(block: () -> Unit): DtorBlock = dtorEntry(block)

    /**
     * Register a constructor block with the global registry.
     *
     * Upstream wraps the user function in an `extern "C"` thunk that the
     * platform's startup machinery invokes through one of the
     * `.init_array`/`__mod_init_func`/`.CRT$XCU` table entries. The
     * `target_family = "wasm"` branch additionally guards the body with an
     * `AtomicBool` so repeat invocations from the wasm entrypoint do not
     * run the user body more than once. The Kotlin port owns the same
     * guarantee directly: [runCtors] is idempotent and each registered
     * [CtorBlock] tracks whether it has already fired.
     */
    public fun ctorEntry(block: () -> Unit): CtorBlock {
        val handle = CtorBlock(block)
        appendCtor(handle)
        return handle
    }

    /**
     * Register a destructor block with the global registry.
     *
     * Upstream binds the destructor through `atexit` on most platforms and
     * `__cxa_atexit` on Apple, scoped to the `__dso_handle`. The Kotlin
     * port instead returns a [DtorBlock] handle that callers thread into
     * [runDtors] when the host is ready to tear down. Destructors run in
     * LIFO order to mirror the upstream contract.
     */
    public fun dtorEntry(block: () -> Unit): DtorBlock {
        val handle = DtorBlock(block)
        appendDtor(handle)
        return handle
    }

    /**
     * Invoke every registered constructor that has not already fired.
     *
     * Code note: upstream guards repeat invocation on wasm with an
     * `AtomicBool` swap. The Kotlin port honors the same invariant on every
     * target — once a constructor has run, subsequent calls to [runCtors]
     * skip it. New constructors registered after a prior [runCtors] call
     * are picked up by the next invocation.
     */
    public fun runCtors() {
        val snapshot = ctorBlocks.load()
        for (block in snapshot) {
            block.fire()
        }
    }

    /**
     * Invoke every registered destructor that has not already fired, in the
     * reverse order of registration.
     *
     * You might wonder why upstream does not use
     * `__attribute__((destructor))` for `dtor`. Unfortunately mingw does
     * not appear to properly support section-based hooks for shutdown
     * (see the mingw-w64 `crtdll.c` reference), and Apple has removed
     * support for section-based shutdown hooks after warning about it for a
     * number of years. The Kotlin port has no shutdown hook at all and
     * exposes [runDtors] as the explicit teardown entrypoint.
     */
    public fun runDtors() {
        val snapshot = dtorBlocks.load()
        for (i in snapshot.indices.reversed()) {
            snapshot[i].fire()
        }
    }

    /**
     * Annotate a block with its appropriate link section.
     *
     * Upstream branches on `features=[..]` to decide between the
     * `used(linker)` and bare `used` attribute forms, then expands a
     * platform-specific `link_section` chain covering
     * `.init_array`/`.ctors`/`__DATA,__mod_init_func,mod_init_funcs`/
     * `.CRT$XCU`. The Kotlin port has no link section, so [ctorCall]
     * collapses to a direct dispatch through [ctorEntry].
     */
    public fun ctorCall(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * Apply either the default platform-based link section attributes, or
     * the overridden `link_section` attribute, depending on whether the
     * `(link_section = ...)` feature is present in the features array.
     *
     * The Kotlin port preserves the entrypoint for caller compatibility
     * but has no link section to apply.
     */
    public fun ctorLinkSection(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * Apply either the default link section attributes, or the overridden
     * `link_section` attribute. Equivalent collapse to [ctorLinkSection].
     */
    public fun ctorLinkSectionAttr(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * If the features array contains the requested feature, return
     * [ifTrue], otherwise return [ifFalse].
     *
     * Upstream is a macro that walks a recursively spelled feature list
     * such as `[(link_section = ".ctors"), used_linker, __warn_on_missing_unsafe, ]`.
     * The Kotlin port keeps the feature list runtime-shaped — see
     * [Feature] — and dispatches on a literal value match.
     */
    public fun <T> ifHasFeature(feature: Feature, features: List<Feature>, ifTrue: () -> T, ifFalse: () -> T): T =
        if (features.any { it == feature }) ifTrue() else ifFalse()

    /**
     * Choose the `unsafe` branch if the `unsafe` marker is present in the
     * upstream declaration, otherwise the safe branch.
     *
     * Kotlin has no `unsafe` keyword. The marker is preserved as a runtime
     * flag so callers translating the declarative form can keep the same
     * conditional shape.
     */
    public fun <T> ifUnsafe(isUnsafe: Boolean, ifUnsafe: () -> T, ifSafe: () -> T): T =
        if (isUnsafe) ifUnsafe() else ifSafe()

    /**
     * If the `used_linker` cargo feature is active, return [ifTrue],
     * otherwise [ifFalse].
     *
     * The Kotlin port has no equivalent of the `used_linker` Cargo feature.
     * The entrypoint is retained for caller compatibility and always
     * returns the [ifFalse] branch.
     */
    public fun <T> includeUsedLinkerFeature(ifTrue: () -> T, ifFalse: () -> T): T = ifFalse()

    /**
     * If the `__no_warn_on_missing_unsafe` cargo feature is active, return
     * [ifTrue], otherwise [ifFalse].
     *
     * The Kotlin port carries no missing-unsafe deprecation warning so the
     * entrypoint always returns the [ifFalse] branch.
     */
    public fun <T> includeNoWarnOnMissingUnsafeFeature(ifTrue: () -> T, ifFalse: () -> T): T = ifFalse()

    /**
     * Extract `ctor`/`dtor` attribute parameters and crate features and
     * turn them into a unified feature array.
     *
     * Supported attributes:
     *
     *  - `used(linker)` → feature: `used_linker`
     *  - `link_section = ...` → feature: `(link_section = ...)`
     *  - `crate_path = ...` → feature: `(crate_path = ...)`
     */
    public fun unifyFeatures(meta: List<Meta>): List<Feature> {
        val features = mutableListOf<Feature>()
        if (includeUsedLinkerFeature({ true }, { false })) {
            features.add(Feature.UsedLinker)
        }
        if (includeNoWarnOnMissingUnsafeFeature({ true }, { false })) {
            features.add(Feature.NoWarnOnMissingUnsafe)
        }
        for (entry in meta) {
            when (entry) {
                Meta.UsedLinker -> features.add(Feature.UsedLinker)
                is Meta.LinkSection -> features.add(Feature.LinkSection(entry.section))
                is Meta.CratePath -> features.add(Feature.CratePath(entry.path))
                Meta.Anonymous -> features.add(Feature.Anonymous)
            }
        }
        return features.toList()
    }

    private fun appendCtor(handle: CtorBlock) {
        while (true) {
            val current = ctorBlocks.load()
            val next = current + handle
            if (ctorBlocks.compareAndSet(current, next)) return
        }
    }

    private fun appendDtor(handle: DtorBlock) {
        while (true) {
            val current = dtorBlocks.load()
            val next = current + handle
            if (dtorBlocks.compareAndSet(current, next)) return
        }
    }
}

/**
 * Handle representing a constructor block registered with [Support.ctorEntry].
 *
 * Each block tracks whether it has already fired so that [Support.runCtors]
 * can honor the upstream wasm idempotency guarantee on every target.
 */
public class CtorBlock internal constructor(private val block: () -> Unit) {
    private val invoked: AtomicBoolean = AtomicBoolean(false)

    /**
     * Run the registered constructor body if it has not already run.
     *
     * Mirrors the upstream `__CTOR__INITILIZED.swap(true, Relaxed)` guard
     * but applies on every target instead of only on wasm.
     */
    public fun fire() {
        if (invoked.compareAndSet(false, true)) {
            block()
        }
    }
}

/**
 * Handle representing a destructor block registered with [Support.dtorEntry].
 *
 * Destructors fire at most once and are otherwise indistinguishable from
 * constructors at runtime; the upstream distinction is which linker
 * section the thunk lands in.
 */
public class DtorBlock internal constructor(private val block: () -> Unit) {
    private val invoked: AtomicBoolean = AtomicBoolean(false)

    /**
     * Run the registered destructor body if it has not already run.
     */
    public fun fire() {
        if (invoked.compareAndSet(false, true)) {
            block()
        }
    }
}

/**
 * Static-item form of a [CtorBlock] registration.
 *
 * Upstream expands a `static FOO: TYPE = unsafe { ... }` annotated with
 * `#[ctor]` into a `OnceLock<TYPE>`-backed wrapper that derefs to the
 * computed value, plus a synthesized `init_foo_ctor` function that pokes
 * the `OnceLock` from a constructor section. The Kotlin port models the
 * shape directly: the [value] accessor lazily forces the initializer on
 * first read, and constructing the handle registers a poke into the
 * global constructor registry so [Support.runCtors] forces it eagerly.
 */
public class CtorStatic<T : Any> internal constructor(private val init: () -> T) {
    private val storage: AtomicReference<T?> = AtomicReference(null)

    init {
        Support.ctorEntry { force() }
    }

    /**
     * The lazily computed value backing this static. Forces the
     * initializer on first read and returns the cached value on every
     * subsequent read.
     */
    public val value: T
        get() = force()

    private fun force(): T {
        val cached = storage.load()
        if (cached != null) return cached
        val computed = init()
        if (storage.compareAndSet(null, computed)) {
            return computed
        }
        return storage.load() ?: computed
    }
}

/**
 * Construct a [CtorStatic] handle for a `#[ctor]`-annotated static item.
 *
 * Mirrors the `static FOO: TYPE = unsafe { ... }` expansion.
 */
public fun <T : Any> ctorStatic(init: () -> T): CtorStatic<T> = CtorStatic(init)

/**
 * Block of code to run as a constructor.
 */
public typealias CtorBlockFn = () -> Unit

/**
 * Block of code to run as a destructor.
 */
public typealias DtorBlockFn = () -> Unit

/**
 * Unified feature array entry. Upstream walks a token-tree representation
 * of the same set in [Support.ifHasFeature]; the Kotlin port carries the
 * data as a sealed hierarchy.
 */
public sealed class Feature {
    /** Equivalent of the `used_linker` Cargo feature. */
    public object UsedLinker : Feature()

    /** Equivalent of the `__no_warn_on_missing_unsafe` Cargo feature. */
    public object NoWarnOnMissingUnsafe : Feature()

    /** Equivalent of the `anonymous` attribute parameter. */
    public object Anonymous : Feature()

    /** Equivalent of `link_section = "section"`. */
    public data class LinkSection(public val section: String) : Feature()

    /** Equivalent of `crate_path = ::path::to::ctor::crate`. */
    public data class CratePath(public val path: String) : Feature()
}

/**
 * Raw attribute meta entry as parsed from a declarative-form invocation.
 *
 * Lifted from upstream token-tree matches into a sealed hierarchy so the
 * Kotlin port can dispatch on it without macro pattern matching.
 */
public sealed class Meta {
    public object UsedLinker : Meta()
    public object Anonymous : Meta()
    public data class LinkSection(public val section: String) : Meta()
    public data class CratePath(public val path: String) : Meta()
}

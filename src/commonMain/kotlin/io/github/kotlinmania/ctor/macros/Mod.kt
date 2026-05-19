// port-lint: source macros/mod.rs
package io.github.kotlinmania.ctor.macros

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * Support module backing the [io.github.kotlinmania.ctor.ctor] and
 * [io.github.kotlinmania.ctor.dtor] runtime registration APIs.
 *
 * The upstream Rust crate emits inline expansions of the constructor and
 * destructor attribute macros that wire a function into the target
 * platform's linker sections — the init-array on Linux, the mod-init-func
 * data section on Apple, the C runtime init section on Windows, the
 * startup and exit text sections for shutdown. Kotlin Multiplatform has no
 * equivalent linker hook: code that runs strictly before main is not
 * available across every target this repo ships, and most standard
 * library services are not safe to call from such a hook even where the
 * platform exposes one. The Kotlin port therefore translates each
 * attribute macro into a runtime registration call. Consumers register
 * blocks during normal module initialization (or eagerly at the top of
 * `main`) and trigger them with [runCtors]. Destructors mirror the C
 * runtime at-exit contract and are released in last-in-first-out order
 * from [runDtors].
 *
 * Upstream re-exports the following macro-rules helpers from this module:
 * the constructor call expander, the constructor entry shim, the
 * link-section selector, the link-section attribute applier, the
 * declarative-form constructor parser, the destructor entry shim, the
 * declarative-form destructor parser, the feature-array dispatcher, the
 * safety-marker dispatcher, the missing-safety-warning feature gate, the
 * used-linker feature gate, and the feature unifier. Every one of
 * those re-exports is a declarative macro whose entire job is compile-time
 * code generation; the Kotlin port collapses each into a runtime function
 * on this object so callers that translated the upstream declarative form
 * keep the same call shape.
 */
public object Support {
    private val ctorBlocks: AtomicReference<List<CtorBlock>> = AtomicReference(emptyList())
    private val dtorBlocks: AtomicReference<List<DtorBlock>> = AtomicReference(emptyList())
    private val compileFeatures: AtomicReference<List<Feature>> = AtomicReference(emptyList())

    /**
     * Return type for the constructor. Why is this needed?
     *
     * On Windows, init-term constructors in the C runtime init section are
     * required to return an unsigned integer value. The upstream Rust
     * crate cannot know whether the user is putting this function into a
     * retval-requiring section or a non-retval section, so it just
     * returns an unsigned integer value which is always valid and just
     * ignored if not needed.
     *
     * Miri is pedantic about this, so the upstream crate returns the unit
     * type if running under Miri. The Kotlin port has no linker section
     * to satisfy, so the return type collapses to [Unit] on every target.
     *
     * See the Microsoft init-term reference for the upstream Windows-init
     * contract.
     */
    public fun ctorRetType(): CtorRetType = Unit

    /**
     * Parse a constructor-annotated item as if it were a procedural macro.
     *
     * In Rust this is the declarative form of the constructor attribute
     * macro that supports both function and static-item shapes. In Kotlin
     * the declarative form has no syntactic counterpart: every
     * registration — whether of an init function or of a lazily computed
     * static — funnels through [ctorEntry]. Static-item registrations
     * therefore take a computation lambda whose result is observable
     * through the returned [CtorStatic] handle once [runCtors] has fired.
     */
    public fun ctorParse(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * Parse a destructor-annotated item as if it were a procedural macro.
     *
     * As with [ctorParse] the declarative and attribute forms collapse to
     * a single runtime registration in Kotlin.
     */
    public fun dtorParse(block: () -> Unit): DtorBlock = dtorEntry(block)

    /**
     * Register a constructor block with the global registry.
     *
     * Upstream wraps the user function in an external-linkage thunk that
     * the platform's startup machinery invokes through one of the
     * platform-specific table entries (the init-array on Linux, the
     * mod-init-func data section on Apple, the C runtime init section on
     * Windows). The wasm branch additionally guards the body with an
     * atomic boolean so repeat invocations from the wasm entrypoint do
     * not run the user body more than once. The Kotlin port owns the
     * same guarantee directly: [runCtors] is idempotent and each
     * registered [CtorBlock] tracks whether it has already fired.
     */
    public fun ctorEntry(block: () -> Unit): CtorBlock {
        val handle = CtorBlock(block)
        appendCtor(handle)
        return handle
    }

    /**
     * Register a destructor block with the global registry.
     *
     * Upstream binds the destructor through the C runtime at-exit hook on
     * most platforms and a scoped at-exit hook tied to the dynamic shared
     * object handle on Apple. The Kotlin port instead returns a
     * [DtorBlock] handle that callers thread into [runDtors] when the
     * host is ready to tear down. Destructors run in last-in-first-out
     * order to mirror the upstream contract.
     */
    public fun dtorEntry(block: () -> Unit): DtorBlock {
        val handle = DtorBlock(block)
        appendDtor(handle)
        return handle
    }

    /**
     * Invoke every registered constructor that has not already fired.
     *
     * Code note: upstream guards repeat invocation on wasm with an atomic
     * boolean swap. The Kotlin port honors the same invariant on every
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
     * Invoke every registered destructor that has not already fired, in
     * the reverse order of registration.
     *
     * You might wonder why upstream does not use a destructor attribute
     * via the platform linker for the destructor side. Unfortunately
     * mingw does not appear to properly support section-based hooks for
     * shutdown (see the mingw-w64 C runtime DLL implementation), and
     * Apple has removed support for section-based shutdown hooks after
     * warning about it for a number of years. The Kotlin port has no
     * shutdown hook at all and exposes [runDtors] as the explicit
     * teardown entrypoint.
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
     * Upstream branches on the active feature list to decide between two
     * forms of the linker-used attribute, then expands a platform-specific
     * link-section chain covering each supported target. The Kotlin port
     * has no link section, so [ctorCall] collapses to a direct dispatch
     * through [ctorEntry].
     */
    public fun ctorCall(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * Apply either the default platform-based link section attributes, or
     * the overridden link-section attribute, depending on whether the
     * link-section feature is present in the features list.
     *
     * The Kotlin port preserves the entrypoint for caller compatibility
     * but has no link section to apply.
     */
    public fun ctorLinkSection(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * Apply either the default link section attributes, or the overridden
     * link-section attribute. Equivalent collapse to [ctorLinkSection].
     */
    public fun ctorLinkSectionAttr(block: () -> Unit): CtorBlock = ctorEntry(block)

    /**
     * If the features list contains the requested feature, return
     * [ifTrue], otherwise return [ifFalse].
     *
     * Upstream is a macro that walks a recursively spelled feature list
     * that mixes plain identifiers with key-value pairs such as a
     * link-section override or a crate-path override. The Kotlin port
     * keeps the feature list runtime-shaped — see [Feature] — and
     * dispatches on a literal value match.
     */
    public fun <T> ifHasFeature(feature: Feature, features: List<Feature>, ifTrue: () -> T, ifFalse: () -> T): T =
        if (features.any { it == feature }) ifTrue() else ifFalse()

    /**
     * Choose the risk-acknowledged branch if the upstream declaration
     * carries the safety marker, otherwise choose the regular branch.
     *
     * Kotlin has no equivalent keyword. The marker is preserved as a
     * runtime flag so callers translating the declarative form can keep
     * the same conditional shape.
     */
    public fun <T> ifUnsafe(isUnsafe: Boolean, ifUnsafe: () -> T, ifSafe: () -> T): T =
        if (isUnsafe) ifUnsafe() else ifSafe()

    /**
     * If the used-linker Cargo feature is active, return [ifTrue],
     * otherwise [ifFalse].
     *
     * The Kotlin port models compile-time feature gates as runtime support
     * features. By default no feature is active, but translated callers can
     * install a feature list through [setCompileFeatures].
     */
    public fun <T> includeUsedLinkerFeature(ifTrue: () -> T, ifFalse: () -> T): T =
        ifHasFeature(Feature.UsedLinker, compileFeatures.load(), ifTrue, ifFalse)

    /**
     * If the feature that silences missing safety-marker warnings is
     * active, return [ifTrue], otherwise [ifFalse].
     *
     * This shares the same runtime feature list as [includeUsedLinkerFeature].
     */
    public fun <T> includeNoWarnOnMissingUnsafeFeature(ifTrue: () -> T, ifFalse: () -> T): T =
        ifHasFeature(Feature.NoWarnOnMissingUnsafe, compileFeatures.load(), ifTrue, ifFalse)

    /**
     * Replace the active runtime feature list used by the compile-feature
     * gate helpers.
     */
    public fun setCompileFeatures(features: List<Feature>) {
        compileFeatures.store(features.toList())
    }

    /**
     * Extract constructor and destructor attribute parameters and crate
     * features and turn them into a unified feature list.
     *
     * Supported attributes:
     *
     *  - The linker-used flag becomes the used-linker feature.
     *  - The link-section override becomes a link-section feature entry
     *    carrying the section name.
     *  - The crate-path override becomes a crate-path feature entry
     *    carrying the path.
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

/** Return type used by constructor startup entries on every Kotlin target. */
public typealias CtorRetType = Unit

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
     * Mirrors the upstream wasm initialized-flag swap guard but applies on
     * every target instead of only on wasm.
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
 * Upstream expands a static item annotated with the constructor attribute
 * into a lock-once-backed wrapper that derefs to the computed value, plus
 * a synthesized initializer function that pokes the lock from a
 * constructor section. The Kotlin port models the shape directly: the
 * [value] accessor lazily forces the initializer on first read, and
 * constructing the handle registers a poke into the global constructor
 * registry so [Support.runCtors] forces it eagerly.
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
 * Construct a [CtorStatic] handle for a constructor-annotated static item.
 *
 * Mirrors the upstream static-item expansion.
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
 * Unified feature list entry. Upstream walks a token-tree representation
 * of the same set in [Support.ifHasFeature]; the Kotlin port carries the
 * data as a sealed hierarchy.
 */
public sealed class Feature {
    /** Equivalent of the used-linker Cargo feature. */
    public object UsedLinker : Feature()

    /** Equivalent of the feature that silences missing safety-marker warnings. */
    public object NoWarnOnMissingUnsafe : Feature()

    /** Equivalent of the anonymous attribute parameter. */
    public object Anonymous : Feature()

    /** Equivalent of a link-section override carrying a section name. */
    public data class LinkSection(public val section: String) : Feature()

    /** Equivalent of a crate-path override carrying a path. */
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

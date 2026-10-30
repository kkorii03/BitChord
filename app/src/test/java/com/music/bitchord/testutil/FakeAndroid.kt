package com.music.bitchord.testutil

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy

/**
 * An in-memory [SharedPreferences] for the stores that only need a map.
 *
 * The Android unit-test android.jar has no real preference file, so a store
 * that reads and writes its own small format is otherwise untestable without a
 * device. A dynamic proxy keeps this independent of the exact nullability of
 * every platform method, which is what the test is not about.
 */
class FakePreferences(initial: Map<String, Any?> = emptyMap()) {

    val values = LinkedHashMap<String, Any?>(initial)

    /** Makes the next `commit`/`apply` report the write did not reach disk. */
    var failCommits: Boolean = false

    private val pending = LinkedHashMap<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearRequested = false

    private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "putString", "putBoolean", "putInt", "putLong", "putFloat" -> {
                pending[args[0] as String] = args[1]
                proxy
            }
            "putStringSet" -> {
                @Suppress("UNCHECKED_CAST")
                val values = args[1] as? Set<String>
                pending[args[0] as String] = values.orEmpty()
                proxy
            }
            "remove" -> {
                removals += args[0] as String
                proxy
            }
            "clear" -> {
                clearRequested = true
                proxy
            }
            "commit", "apply" -> {
                if (!failCommits) {
                    if (clearRequested) values.clear()
                    removals.forEach { values.remove(it) }
                    values.putAll(pending)
                }
                pending.clear()
                removals.clear()
                clearRequested = false
                !failCommits
            }
            else -> null
        }
    } as SharedPreferences.Editor

    val prefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getAll" -> LinkedHashMap(values)
            "contains" -> values.containsKey(args[0] as String)
            "getString" -> values[args[0] as String] as? String ?: args[1]
            "getStringSet" -> {
                @Suppress("UNCHECKED_CAST")
                (values[args[0] as String] as? Set<String>)?.toMutableSet() ?: args[1]
            }
            "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1]
            "getInt" -> values[args[0] as String] as? Int ?: args[1]
            "getLong" -> values[args[0] as String] as? Long ?: args[1]
            "getFloat" -> values[args[0] as String] as? Float ?: args[1]
            "edit" -> editor
            "registerOnSharedPreferenceChangeListener",
            "unregisterOnSharedPreferenceChangeListener",
            -> Unit
            "equals" -> proxy === args[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "FakePreferences"
            else -> null
        }
    } as SharedPreferences
}

/**
 * A [Context] whose only real answers are the preference file and files dir.
 *
 * [ContextWrapper]'s constructor is a throwing stub in the unit-test android.jar,
 * so the instance is allocated without running it. The two overrides below use
 * the companion's current values, which means no field of the uninitialised
 * instance is ever read.
 */
class FakeContext private constructor() : ContextWrapper(null) {

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        currentPreferences.prefs

    override fun getFilesDir(): File = currentFilesDir

    companion object {
        private var currentPreferences: FakePreferences = FakePreferences()
        private var currentFilesDir: File = File(System.getProperty("java.io.tmpdir"))

        fun create(
            preferences: FakePreferences = FakePreferences(),
            filesDir: File = File(
                System.getProperty("java.io.tmpdir"),
                "bitchord-test-${System.nanoTime()}",
            ).apply { mkdirs() },
        ): Context {
            currentPreferences = preferences
            currentFilesDir = filesDir
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, FakeContext::class.java) as Context
        }
    }
}

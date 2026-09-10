package org.junit

/**
 * Origin: written for this repository, not extracted from JUnit.
 *
 * The unit tests target the JUnit 4 API because that is what the Gradle build in
 * CI runs them with. Gradle, however, cannot run in the development VM, and the
 * local dependency set (`~/.local/m2`, resolved by `tools/fetch-deps.py`) is
 * built from the *app's* graph, which has no JUnit in it.
 *
 * So this file supplies just the API surface the tests use, and
 * `tools/run-jvm-tests.sh` compiles it ahead of the test sources. It is never
 * part of the APK and never part of CI: Gradle resolves the real
 * `junit:junit` for `testDebugUnitTest`, and the classpath order in the local
 * script puts this stub first, which is the only reason both can coexist.
 *
 * Behaviour is the contract that matters: `assertEquals` fails on inequality and
 * on a null/non-null mismatch, and every failure throws [AssertionError] with a
 * message naming the values -- so the reflection runner reports the same failure
 * a JUnit runner would.
 */
annotation class Test

object Assert {
    @JvmStatic
    fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionError(message ?: "expected true")
    }

    @JvmStatic
    fun assertTrue(condition: Boolean) = assertTrue(null, condition)

    @JvmStatic
    fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionError(message ?: "expected false")
    }

    @JvmStatic
    fun assertFalse(condition: Boolean) = assertFalse(null, condition)

    @JvmStatic
    fun assertNull(message: String?, actual: Any?) {
        if (actual != null) throw AssertionError((message ?: "expected null") + " but was <$actual>")
    }

    @JvmStatic
    fun assertNull(actual: Any?) = assertNull(null, actual)

    @JvmStatic
    fun assertNotNull(message: String?, actual: Any?) {
        if (actual == null) throw AssertionError(message ?: "expected not null")
    }

    @JvmStatic
    fun assertNotNull(actual: Any?) = assertNotNull(null, actual)

    @JvmStatic
    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        // Arrays would need deep equality; no test here compares one, and a
        // silent pass on reference equality would be worse than this message.
        if (expected is Array<*> || actual is Array<*>) {
            throw AssertionError("assertEquals does not support arrays in this stub")
        }
        if (expected != actual) {
            throw AssertionError((message ?: "values differ") + "\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

    @JvmStatic
    fun assertSame(message: String?, expected: Any?, actual: Any?) {
        if (expected !== actual) {
            throw AssertionError((message ?: "not the same instance") + "\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertSame(expected: Any?, actual: Any?) = assertSame(null, expected, actual)

    @JvmStatic
    fun fail(message: String?): Nothing = throw AssertionError(message ?: "failed")

    @JvmStatic
    fun fail(): Nothing = fail(null)
}

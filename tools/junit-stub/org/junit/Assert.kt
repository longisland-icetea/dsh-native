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
        // Numeric types compare by value, as the real JUnit does:
        // `assertEquals(100_000, metrics.totalTokens)` with a Long on the right is
        // one assertion, not a type error. A stub that failed it would push tests
        // into writing `100_000L` to satisfy the harness rather than the assertion.
        val same = if (expected is Number && actual is Number) {
            if (expected is Double || actual is Double || expected is Float || actual is Float) {
                expected.toDouble() == actual.toDouble()
            } else {
                expected.toLong() == actual.toLong()
            }
        } else {
            expected == actual
        }
        if (!same) {
            throw AssertionError((message ?: "values differ") + "\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

    /**
     * Floating-point comparison, matching JUnit's `(expected, actual, delta)`.
     * Kotlin's `Float` widens to this overload, which is why a test can write
     * `assertEquals(1000f, sum, 0.5f)`.
     */
    @JvmStatic
    fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) {
            throw AssertionError("values differ by more than $delta\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    @JvmStatic
    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) {
            throw AssertionError((message ?: "values differ") + "\n  expected: <$expected>\n  actual:   <$actual>")
        }
    }

    /**
     * The `Float` form, because Kotlin does not widen `Float` to `Double` when
     * picking an overload -- without this, `assertEquals(1f, x, 0.1f)` does not
     * compile against the stub even though it does against real JUnit.
     */
    @JvmStatic
    fun assertEquals(expected: Float, actual: Float, delta: Float) =
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())

    @JvmStatic
    fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) =
        assertEquals(message, expected.toDouble(), actual.toDouble(), delta.toDouble())

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

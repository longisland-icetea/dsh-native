import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * A stand-in for the JUnit runner, so the unit tests can be executed without
 * Gradle or a JUnit jar.
 *
 * It finds compiled classes under the given directory, instantiates each one,
 * and calls every zero-argument function annotated `@Test`. That covers what
 * this project's tests use. The `org.junit` API the tests compile against comes
 * from `tools/junit-stub`, compiled ahead of them by `tools/run-jvm-tests.sh`,
 * which also builds the classpath this runs on.
 *
 * Failures are reported with the test name and the assertion message, and the
 * process exits non-zero, which is what makes this usable in a build.
 */

/** Discover class files in a stable order. */
private fun classesUnder(root: File): List<String> =
    root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        // Nested classes hold lambdas and synthetic bodies, never tests.
        .filterNot { it.name.contains('$') }
        .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        .sorted()
        .toList()

private fun isTest(function: KFunction<*>): Boolean =
    function.annotations.any { it.annotationClass.qualifiedName == "org.junit.Test" }

fun main(args: Array<String>) = runBlocking {
    val root = File(args.firstOrNull() ?: "build/jvm-test/test")
    if (!root.isDirectory) {
        System.err.println("no test classes at ${root.absolutePath}")
        kotlin.system.exitProcess(2)
    }

    var passed = 0
    val failures = mutableListOf<String>()

    for (name in classesUnder(root)) {
        val type = runCatching { Class.forName(name).kotlin }.getOrNull() ?: continue
        val tests = type.memberFunctions.filter(::isTest)
        if (tests.isEmpty()) continue
        println("== $name")
        for (test in tests) {
            val constructor = type.constructors.firstOrNull { it.parameters.isEmpty() }
            val instance = runCatching { constructor?.call() }.getOrNull()
            if (instance == null) {
                failures += "$name.${test.name}: no no-argument constructor"
                println("FAIL ${test.name} (no no-argument constructor)")
                continue
            }
            val outcome = runCatching {
                test.isAccessible = true
                // Blocking here is the point: a runner executes each suspend test
                // to completion before starting the next one.
                if (test.isSuspend) test.callSuspend(instance) else test.call(instance)
            }
            // Reflection wraps whatever the test threw; unwrap to the real cause
            // or every failure reads as "InvocationTargetException".
            var error = outcome.exceptionOrNull()
            while (error is java.lang.reflect.InvocationTargetException && error.cause != null) {
                error = error.cause
            }
            if (error == null) {
                passed++
                println("ok   ${test.name}")
            } else {
                // Keep the whole message: the stub puts expected/actual on the
                // following lines, and those are what a reader needs.
                val reason = error.message?.take(400)?.replace("\n", "\n     ")
                    ?: error::class.simpleName
                failures += "$name.${test.name}: $reason"
                println("FAIL ${test.name}")
                println("     $reason")
            }
        }
    }

    println()
    println(if (failures.isEmpty()) "ALL PASS ($passed tests)" else "${failures.size} FAILURES of ${passed + failures.size}")
    failures.forEach { println("  - $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}

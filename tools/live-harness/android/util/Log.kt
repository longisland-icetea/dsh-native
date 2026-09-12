package android.util

/**
 * The one Android API the app's non-UI code calls, stood in for on the JVM.
 *
 * `android.jar` ships stubs that throw `RuntimeException("Stub!")`, so anything
 * that logs cannot run outside a device. This file is compiled into its own
 * output directory and put *first* on the classpath, which is what lets the live
 * harness drive the real `DshClient` and `AppStateHolder` -- the exact classes
 * the phone runs -- against a real Host.
 */
object Log {
    @JvmStatic
    fun v(tag: String, msg: String): Int = println("V/$tag: $msg").let { 0 }

    @JvmStatic
    fun d(tag: String, msg: String): Int = println("D/$tag: $msg").let { 0 }

    @JvmStatic
    fun i(tag: String, msg: String): Int = println("I/$tag: $msg").let { 0 }

    @JvmStatic
    fun w(tag: String, msg: String): Int = println("W/$tag: $msg").let { 0 }

    @JvmStatic
    fun e(tag: String, msg: String): Int = println("E/$tag: $msg").let { 0 }
}

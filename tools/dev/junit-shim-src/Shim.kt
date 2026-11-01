package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

class AssertionFailedError(msg: String) : AssertionError(msg)

object Assert {
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) throw AssertionFailedError("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: Any?, actual: Any?) { if (expected != actual) throw AssertionFailedError("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) { if (kotlin.math.abs(expected - actual) > delta) throw AssertionFailedError("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) { if (kotlin.math.abs(expected - actual) > delta) throw AssertionFailedError("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) { if (expected != actual) throw AssertionFailedError("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertTrue(c: Boolean) { if (!c) throw AssertionFailedError("expected true") }
    @JvmStatic fun assertTrue(m: String, c: Boolean) { if (!c) throw AssertionFailedError(m) }
    @JvmStatic fun assertFalse(c: Boolean) { if (c) throw AssertionFailedError("expected false") }
    @JvmStatic fun assertFalse(m: String, c: Boolean) { if (c) throw AssertionFailedError(m) }
    @JvmStatic fun assertNull(o: Any?) { if (o != null) throw AssertionFailedError("expected null but was:<$o>") }
    @JvmStatic fun assertNotNull(o: Any?) { if (o == null) throw AssertionFailedError("expected not null") }
    @JvmStatic fun assertArrayEquals(e: ShortArray, a: ShortArray) { if (!e.contentEquals(a)) throw AssertionFailedError("arrays differ") }
    @JvmStatic fun assertArrayEquals(e: FloatArray, a: FloatArray, d: Float) { if (e.size != a.size || e.indices.any { kotlin.math.abs(e[it]-a[it]) > d }) throw AssertionFailedError("arrays differ") }
    @JvmStatic fun fail(m: String) { throw AssertionFailedError(m) }
}

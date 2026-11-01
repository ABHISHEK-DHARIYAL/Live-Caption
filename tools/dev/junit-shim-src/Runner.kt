import java.io.File
import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    val dir = File(args[0])
    val loader = java.net.URLClassLoader(arrayOf(dir.toURI().toURL()), Thread.currentThread().contextClassLoader)
    val names = dir.walkTopDown().filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
        .map { it.relativeTo(dir).path.removeSuffix(".class").replace('/', '.') }.sorted().toList()
    var pass = 0; var fail = 0
    val failures = ArrayList<String>()
    val testAnn = Class.forName("org.junit.Test")
    @Suppress("UNCHECKED_CAST")
    for (n in names) {
        val c = loader.loadClass(n)
        for (m in c.declaredMethods.sortedBy { it.name }) {
            if (m.annotations.none { it.annotationClass.java == testAnn }) continue
            try {
                m.invoke(c.getDeclaredConstructor().newInstance()); pass++
            } catch (e: InvocationTargetException) {
                fail++; failures.add("${c.simpleName}.${m.name}: ${e.targetException}")
            }
        }
    }
    failures.forEach { println("FAIL $it") }
    println("RESULT: $pass passed, $fail failed, ${names.size} test classes")
    if (fail > 0) System.exit(1)
}

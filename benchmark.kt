fun main() {
    val processStart = System.nanoTime()
    for (i in 1..100) {
        try {
            val process = ProcessBuilder("echo", "test")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        } catch (e: Exception) {}
    }
    val processEnd = System.nanoTime()

    val reflectStart = System.nanoTime()
    val clazz = Class.forName("java.lang.System")
    val method = clazz.getMethod("getProperty", String::class.java)
    for (i in 1..100) {
        try {
            method.invoke(null, "os.name")
        } catch (e: Exception) {}
    }
    val reflectEnd = System.nanoTime()

    println("ProcessBuilder 100 times: ${(processEnd - processStart) / 1000000} ms")
    println("Reflection 100 times: ${(reflectEnd - reflectStart) / 1000000} ms")
}

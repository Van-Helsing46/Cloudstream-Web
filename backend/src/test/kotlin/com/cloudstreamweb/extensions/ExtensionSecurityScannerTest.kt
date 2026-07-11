package com.cloudstreamweb.extensions

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionSecurityScannerTest {

    // Compiled into the test classpath; we scan their real .class bytes.
    @Suppress("unused")
    private class Evil {
        fun sabotage(): Process = Runtime.getRuntime().exec("rm -rf /")
        fun kill() = System.exit(1)
    }

    @Suppress("unused")
    private class CleanScraper {
        fun fetch(url: String): Int = url.length + listOf(1, 2, 3).sum()
    }

    private fun classBytes(c: Class<*>): ByteArray =
        c.getResourceAsStream("/" + c.name.replace('.', '/') + ".class")!!.readBytes()

    private fun scan(c: Class<*>): List<ExtensionSecurityScanner.Finding> {
        val out = mutableListOf<ExtensionSecurityScanner.Finding>()
        // scanClass is private; go through scanClassesDir by writing the bytes to a temp dir.
        val dir = kotlin.io.path.createTempDirectory("scan").toFile()
        try {
            java.io.File(dir, "X.class").writeBytes(classBytes(c))
            out += ExtensionSecurityScanner.scanClassesDir(dir)
        } finally {
            dir.deleteRecursively()
        }
        return out
    }

    @Test
    fun `blocks process execution and System exit`() {
        val findings = scan(Evil::class.java)
        val blocked = findings.filter { it.severity == ExtensionSecurityScanner.Severity.BLOCK }
        assertTrue(blocked.any { it.owner == "java/lang/Runtime" && it.member == "exec" }, "should flag Runtime.exec")
        assertTrue(blocked.any { it.owner == "java/lang/System" && it.member == "exit" }, "should flag System.exit")
        assertFalse(ExtensionSecurityScanner.isSafeToLoad(findings, "Evil"), "Evil must be refused")
    }

    @Test
    fun `allows a clean scraper`() {
        val findings = scan(CleanScraper::class.java)
        assertTrue(findings.none { it.severity == ExtensionSecurityScanner.Severity.BLOCK })
        assertTrue(ExtensionSecurityScanner.isSafeToLoad(findings, "CleanScraper"))
    }
}

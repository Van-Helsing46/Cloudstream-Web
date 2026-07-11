package com.cloudstreamweb.extensions

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.io.File
import java.util.jar.JarFile

/**
 * Static defense-in-depth check on third-party extension bytecode before it is loaded.
 *
 * The real isolation boundary is the container the app runs in (see `docs`/deploy). This scanner is
 * a cheap, no-false-positive layer on top: it inspects the compiled/converted classes for API calls
 * that a content scraper never legitimately needs and that a malicious extension would use to break
 * out — spawning OS processes, killing the JVM, or loading native code. Those are hard-**blocked**.
 * Reflection (the obvious way to bypass a static check) is **warned** but not blocked, since some
 * legitimate providers use it; it makes tampering visible in the logs without breaking real ones.
 *
 * Not a sandbox: a determined attacker can defeat any static check. It raises the bar and catches
 * naive sabotage; container hardening remains the boundary that actually contains a hostile extension.
 */
object ExtensionSecurityScanner {

    private val log = LoggerFactory.getLogger(javaClass)

    enum class Severity { BLOCK, WARN }

    data class Finding(val severity: Severity, val owner: String, val member: String, val inClass: String)

    /** owner (internal name) → members that trigger; null members = any use of the type. */
    private class Rule(val owner: String, val members: Set<String>?, val severity: Severity)

    private val RULES = listOf(
        Rule("java/lang/Runtime", setOf("exec", "halt", "load", "loadLibrary"), Severity.BLOCK),
        Rule("java/lang/System", setOf("exit", "load", "loadLibrary"), Severity.BLOCK),
        Rule("java/lang/ProcessBuilder", null, Severity.BLOCK),
        Rule("java/lang/ProcessImpl", null, Severity.BLOCK),
        Rule("java/lang/reflect/Method", setOf("invoke"), Severity.WARN),
        Rule("java/lang/reflect/AccessibleObject", setOf("setAccessible"), Severity.WARN),
        Rule("java/lang/Class", setOf("forName"), Severity.WARN),
    )

    /**
     * Scans an extension's classes; returns true if it is safe to load (no BLOCK findings). Logs a
     * blocked summary or reflection warnings under [extensionName].
     */
    fun isSafeToLoad(findings: List<Finding>, extensionName: String): Boolean {
        val blocks = findings.filter { it.severity == Severity.BLOCK }
        findings.filter { it.severity == Severity.WARN }.map { "${it.owner}.${it.member}" }.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { log.warn("Extension '{}' uses reflection ({}) — allowed but noted", extensionName, it.joinToString()) }
        if (blocks.isNotEmpty()) {
            log.warn(
                "BLOCKED extension '{}': prohibited API usage {}",
                extensionName,
                blocks.map { "${it.owner}.${it.member} (in ${it.inClass})" }.distinct().joinToString(),
            )
            return false
        }
        return true
    }

    fun scanJar(jar: File): List<Finding> = buildList {
        JarFile(jar).use { jf ->
            jf.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                jf.getInputStream(e).use { scanClass(it.readBytes(), this) }
            }
        }
    }

    fun scanClassesDir(dir: File): List<Finding> = buildList {
        dir.walkTopDown().filter { it.isFile && it.extension == "class" }
            .forEach { scanClass(it.readBytes(), this) }
    }

    private fun scanClass(bytes: ByteArray, out: MutableList<Finding>) {
        var current = "?"
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, name: String, sig: String?, sup: String?, ifs: Array<out String>?) {
                current = name
            }

            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String?, itf: Boolean) {
                        RULES.firstOrNull { it.owner == owner && (it.members == null || name in it.members) }
                            ?.let { out += Finding(it.severity, owner, name, current) }
                    }

                    override fun visitTypeInsn(op: Int, type: String) {
                        if (op == Opcodes.NEW) {
                            RULES.firstOrNull { it.owner == type && it.members == null }
                                ?.let { out += Finding(it.severity, type, "<init>", current) }
                        }
                    }
                }
        }
        runCatching { ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG) }
    }
}

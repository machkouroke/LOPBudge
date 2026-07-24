package com.lop.budget.reports

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Reporter Senior : Génère des rapports Markdown avec logs intégrés.
 * Les rapports sont placés dans un sous-dossier 'reports' relatif à la classe de test.
 */
class MarkdownReporter : TestWatcher() {
    
    companion object {
        // État partagé pour toute la classe de test
        private val results = mutableListOf<TestResult>()
        private val currentLogs = mutableListOf<String>()
        private var startTime = System.currentTimeMillis()

        data class TestResult(
            val name: String, 
            val status: String, 
            val logs: List<String>,
            val error: String? = null
        )

        fun log(message: String) {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
            val formatted = "[$timestamp] $message"
            currentLogs.add(formatted)
            println(formatted)
        }

        fun reset() {
            results.clear()
            currentLogs.clear()
            startTime = System.currentTimeMillis()
        }
    }

    override fun starting(description: Description) {
        currentLogs.clear()
        log("Début du test : ${description.methodName}")
    }

    override fun succeeded(description: Description) {
        log("Test réussi")
        results.add(TestResult(description.methodName, "✅ SUCCÈS", currentLogs.toList()))
    }

    override fun failed(e: Throwable, description: Description) {
        log("ERREUR : ${e.message}")
        results.add(TestResult(description.methodName, "❌ ÉCHEC", currentLogs.toList(), e.message?.take(200)))
    }

    /**
     * Génère le rapport final.
     */
    fun generateFinalReport(testClass: Any) {
        val clazz = testClass::class.java
        val className = clazz.simpleName
        val packageName = clazz.`package`?.name ?: ""
        
        val packagePath = packageName.replace(".", "/")
        val reportDir = File("src/test/java/$packagePath/reports")
        if (!reportDir.exists()) reportDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val fileName = "Rapport_${className}_$timestamp.md"
        val file = File(reportDir, fileName)

        val content = StringBuilder()
        content.append("# 🧪 Rapport de Test : $className\n\n")
        content.append("**Date** : ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(Date())}\n")
        content.append("**Package** : `$packageName`\n")
        content.append("**Durée totale** : ${System.currentTimeMillis() - startTime}ms\n\n")
        
        content.append("## 📋 Résumé\n\n")
        val successCount = results.count { it.status.contains("SUCCÈS") }
        content.append("- **Total** : ${results.size}\n")
        content.append("- **Succès** : $successCount\n")
        content.append("- **Échecs** : ${results.size - successCount}\n\n")

        content.append("## 🔍 Détails par Test\n\n")
        
        results.forEach { res ->
            content.append("### `${res.name}` - ${res.status}\n")
            if (res.error != null) {
                content.append("> [!CAUTION]\n")
                content.append("> **Erreur** : ${res.error}\n\n")
            }
            
            content.append("#### 📝 Logs du test\n")
            content.append("```text\n")
            res.logs.forEach { content.append(it).append("\n") }
            content.append("```\n\n")
            content.append("---\n\n")
        }

        file.writeText(content.toString())
        println("\n[REPORTER-SENIOR] Rapport généré : ${file.absolutePath}\n")
    }
}

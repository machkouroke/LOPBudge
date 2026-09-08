package com.lop.budget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * TC-86 — Centralisation du calcul de récurrence (US LOP-49, CA-13 second volet).
 *
 * ## Pourquoi un test statique
 * CA-13 énonce deux choses. Que les écritures Room actualisent les observations ouvertes est un
 * comportement observable, couvert par `ObserveTransactionsRoomTest` (variante L-09). Que le
 * calcul de récurrence ne soit **pas dupliqué chez les consommateurs** est une propriété du reste
 * du code : un test de résultat peut être vert alors qu'un ViewModel recalcule la grille dans son
 * coin. L'US le dit explicitement — « la centralisation de CA-13 nécessite également une revue
 * ciblée des points d'appel ; un test de résultat ne prouve pas à lui seul l'absence de logique
 * dupliquée ». Ce fichier automatise cette revue.
 *
 * ## Niveau
 * JUnit 4 JVM pur : ni Android, ni Robolectric, ni Room. Le test lit les sources de `app/src/main`.
 *
 * ## Correspondance cas → CA → cible
 * ```
 * S-01  CA-13  aucun référencement de RecurrenceEngine sous ui/ ni data/
 * S-02  CA-13  l'ensemble des points d'appel du moteur est exactement la liste blanche déclarée
 * ```
 *
 * ## Plafond assumé
 * ponytail: analyse de texte source. Elle attrape la régression réelle — quelqu'un importe le
 * moteur dans un ViewModel ou un repository — mais pas une réimplémentation manuelle de la grille
 * (un `plusMonths` recopié à la main dans un consommateur). Passer à de l'analyse sémantique
 * (Konsist, ArchUnit, ou une règle Detekt) si cette réimplémentation devient un risque constaté.
 *
 * ## Hors périmètre
 * Ce fichier ne dit rien du contenu du moteur ni des résultats d'observation : voir TC-84, TC-85
 * et `ObserveTransactionsRoomTest`.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*RecurrenceCentralizationTest"`
 */
class RecurrenceCentralizationTest {

    /**
     * S-01 — Given les sources de production, When on cherche le moteur de récurrence sous les
     * couches consommatrices, Then aucune n'y fait référence (CA-13).
     */
    @Test
    fun `S-01 - aucune couche consommatrice ne reference le moteur de recurrence`() {
        val offenders = referencingFiles()
            .filter { path -> CONSUMER_LAYERS.any { path.startsWith(it) } }
            .sorted()

        assertEquals(
            "CA-13 : le calcul de récurrence doit rester centralisé dans le domaine. " +
                "Ces fichiers de couche consommatrice référencent $ENGINE_SYMBOLS : $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * S-02 — Given les sources de production, When on relève tous les points d'appel du moteur,
     * Then ils correspondent exactement à la liste blanche justifiée ci-dessous (CA-13).
     *
     * Un nouveau point d'appel fait échouer ce test : c'est le déclencheur de la revue. L'ajouter
     * ici est une décision explicite, pas un effet de bord.
     */
    @Test
    fun `S-02 - les points d'appel du moteur sont exactement ceux declares`() {
        assertEquals(
            "CA-13 : la liste des fichiers référençant $ENGINE_SYMBOLS a changé. " +
                "Vérifier que le nouveau point d'appel ne duplique pas le calcul de récurrence, " +
                "puis mettre à jour la liste blanche de ce test en justifiant l'ajout.",
            ALLOWED_CALL_SITES.sorted(),
            referencingFiles().sorted(),
        )
    }

    // --- Outillage ----------------------------------------------------------------------------

    /** Chemins, relatifs à `com/lop/budget`, des fichiers de production citant le moteur. */
    private fun referencingFiles(): List<String> {
        val root = sourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { ENGINE_REFERENCE.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }

    /**
     * Remonte depuis le répertoire de travail jusqu'au package de production. Gradle exécute les
     * tests unitaires depuis le répertoire du module, mais on ne s'appuie pas sur cette convention.
     */
    private fun sourceRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(SOURCE_PATH, "app/$SOURCE_PATH")) {
                val resolved = File(directory, candidate)
                if (resolved.isDirectory) return resolved
            }
            directory = directory.parentFile
        }
        fail("Sources de production introuvables : aucun '$SOURCE_PATH' depuis ${File("").absolutePath}")
        error("unreachable")
    }

    private companion object {
        const val SOURCE_PATH = "src/main/java/com/lop/budget"
        const val ENGINE_SYMBOLS = "RecurrenceEngine / generateOccurrences / calculateVirtualId"

        val ENGINE_REFERENCE = Regex("""\b(RecurrenceEngine|generateOccurrences|calculateVirtualId)\b""")

        /** Couches qui consomment la liste d'occurrences et ne doivent jamais la recalculer. */
        val CONSUMER_LAYERS = listOf("ui/", "data/", "notifications/", "widget/")

        /**
         * Points d'appel légitimes, relevés et justifiés le 8 septembre 2026.
         *
         * - `domain/RecurrenceEngine.kt` — la définition elle-même.
         * - `domain/usecase/ObserveTransactionsUseCase.kt` — la liste d'une période (CA-13).
         * - `domain/usecase/ObserveTransactionUseCase.kt` — l'occurrence individuelle (CA-13/CA-14).
         * - `domain/usecase/CreateTransactionUseCase.kt` — `calculateVirtualId` seul, pour rendre
         *   l'ID de l'occurrence créée ; aucune génération de grille.
         * - `domain/usecase/EditTransactionWithScopeUseCase.kt` — `calculateVirtualId` seul, pour
         *   retrouver l'ID d'affichage après une édition ALL ; aucune génération de grille.
         */
        val ALLOWED_CALL_SITES = listOf(
            "domain/RecurrenceEngine.kt",
            "domain/usecase/ObserveTransactionsUseCase.kt",
            "domain/usecase/ObserveTransactionUseCase.kt",
            "domain/usecase/CreateTransactionUseCase.kt",
            "domain/usecase/EditTransactionWithScopeUseCase.kt",
        )
    }
}

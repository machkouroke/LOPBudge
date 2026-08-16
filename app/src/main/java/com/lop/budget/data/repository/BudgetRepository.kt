package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Point d'accès unique aux données. Les ViewModels dépendent de ce repository,
 * jamais directement des DAO. Cela facilite les tests et la reprise du projet.
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val recurringSeriesDao: RecurringSeriesDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val goalDao: GoalDao,
    private val debtDao: DebtDao,
) {
    // État local des suppressions en cours (période "Undo") pour filtrage temps réel à la source
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    private val _pendingSeriesDeletes = MutableStateFlow<Map<String, SeriesDeletionMode>>(emptyMap())
    private val _pendingSeriesFromDates = MutableStateFlow<Map<String, Long>>(emptyMap())


    fun setPendingSeriesDelete(seriesId: String, mode: SeriesDeletionMode?, fromDate: Long? = null) {
        if (mode == null) {
            _pendingSeriesDeletes.value -= seriesId
            _pendingSeriesFromDates.value -= seriesId
        } else {
            _pendingSeriesDeletes.value += (seriesId to mode)
            if (fromDate != null) _pendingSeriesFromDates.value += (seriesId to fromDate)
        }
    }

    // Transactions
    fun observeTransactions(): Flow<List<TransactionWithRelations>> = transactionDao.observeAll()
    fun observeTransactionsByAccount(accountId: Long) = transactionDao.observeByAccount(accountId)
    fun observePaidTransactionsByAccount(accountId: Long) = transactionDao.observePaidByAccount(accountId)
    fun observePlannedTransactionsByAccount(accountId: Long) = transactionDao.observePlannedByAccount(accountId)

    /** Observe les transactions "métier" (exclut les ajustements de solde). */
    fun observeBusinessTransactions(): Flow<List<TransactionWithRelations>> =
        transactionDao.observeAll().map { list ->
            list.filter { it.transaction.kind == TransactionKind.STANDARD }
        }

    /** Ajuste le solde d'un compte en créant une transaction compensatoire technique. */
    suspend fun adjustAccountBalance(accountId: Long, newTargetBalance: Double) {
        val account = accountDao.getById(accountId) ?: return
        val allTransactions = transactionDao.observeAll().first().map { it.transaction }
        
        // Calcul du solde actuel via le moteur
        val currentBalances = BalanceEngine.calculateBalances(listOf(account), allTransactions)
        val currentBalance = currentBalances[accountId] ?: account.initialBalance
        
        val delta = newTargetBalance - currentBalance
        if (delta == 0.0) return
        
        val type = if (delta > 0) TransactionType.INCOME else TransactionType.EXPENSE
        val absAmount = abs(delta)
        
        val adjustmentTx = TransactionEntity(
            title = "Ajustement de solde",
            amount = absAmount,
            type = type,
            status = TransactionStatus.PAID,
            kind = TransactionKind.BALANCE_ADJUSTMENT,
            date = System.currentTimeMillis(),
            paidAt = System.currentTimeMillis(),
            accountId = accountId,
            categoryId = 0L, // Catégorie technique ou racine
            note = "Ajustement automatique du solde",
        )
        
        transactionDao.upsert(adjustmentTx)
    }

    fun searchTransactions(query: String) = transactionDao.search(query)

    /**
     * Recherche Universelle : combine les transactions réelles et les occurrences virtuelles
     * générées par les séries récurrentes.
     */
    fun searchTransactionsAdvanced(
        query: String,
        accountId: Long?,
        categoryId: Long?,
        startDate: Long?,
        endDate: Long?
    ): Flow<List<TransactionWithRelations>> {
        // 1. Filtrer les transactions réelles via DAO
        val realTxsFlow = transactionDao.searchAdvanced(query, accountId, categoryId, startDate, endDate)
        
        // 2. Observer les séries actives pour le virtuel
        val seriesFlow = recurringSeriesDao.observeActiveSeries()
        val accountsFlow = accountDao.observeAll()
        val categoriesFlow = categoryDao.observeAll()

        return combine(
            realTxsFlow,
            seriesFlow,
            accountsFlow,
            categoriesFlow,
            _pendingDeletes,
            _pendingSeriesDeletes,
            _pendingSeriesFromDates
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val realTxs = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST")
            val seriesList = args[1] as List<RecurringSeriesEntity>
            @Suppress("UNCHECKED_CAST")
            val allAccounts = args[2] as List<AccountEntity>
            @Suppress("UNCHECKED_CAST")
            val allCategories = args[3] as List<CategoryEntity>
            @Suppress("UNCHECKED_CAST")
            val pending = args[4] as Set<Long>
            @Suppress("UNCHECKED_CAST")
            val pendingSeries = args[5] as Map<String, SeriesDeletionMode>
            @Suppress("UNCHECKED_CAST")
            val pendingDates = args[6] as Map<String, Long>

            val zone = ZoneId.systemDefault()
            
            // Fenêtre de recherche par défaut pour le virtuel : M-1 à M+6
            val searchStart = startDate ?: (LocalDate.now().minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val searchEnd = endDate ?: (LocalDate.now().plusMonths(6).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli())

            // Résultat final : commencer par les réels déjà filtrés par DAO
            val finalResult = realTxs.filter { twr ->
                isTransactionVisible(
                    twr.transaction,
                    pending,
                    pendingSeries,
                    pendingDates
                )
            }.toMutableList()

            // 3. Ajouter les occurrences virtuelles des séries qui matchent
            for (series in seriesList) {
                val sIdStr = series.id.toString()
                
                // Filtre sur les critères de recherche (Texte, Compte, Catégorie)
                val matchesText = query.isBlank() || series.title.contains(query, ignoreCase = true) || series.note?.contains(query, ignoreCase = true) == true
                val matchesAccount = accountId == null || series.accountId == accountId
                val matchesCategory = categoryId == null || series.categoryId == categoryId
                
                if (matchesText && matchesAccount && matchesCategory) {
                    // Barrière de sécurité : on ne génère rien pour une série annulée ou en cours de suppression ALL
                    if (series.isCancelled || pendingSeries[sIdStr] == SeriesDeletionMode.ALL) continue

                    val occurrences = RecurrenceEngine.generateOccurrences(series, searchStart, searchEnd)
                    for (virtualTx in occurrences) {
                        val occEpoch = virtualTx.date

                        // Vérifier si cette occurrence virtuelle spécifique est en cours de suppression FUTURE
                        if (pendingSeries[sIdStr] == SeriesDeletionMode.FUTURE) {
                            val fromDate = pendingDates[sIdStr]
                            if (fromDate != null && occEpoch >= fromDate) continue
                        }

                        // Vérifier si une exception existe déjà (matérialisée ou supprimée)
                        val hasExisting = realTxs.any { it.transaction.seriesId == sIdStr && it.transaction.seriesDate == occEpoch }
                        if (!hasExisting) {
                            // Sécurité finale : ne pas afficher si le virtuel vient d'être supprimé (même si pas encore en base)
                            if (virtualTx.id in pending) continue

                            val account = allAccounts.find { it.id == series.accountId }
                            val category = allCategories.find { it.id == series.categoryId }
                            finalResult.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                        }
                    }
                }
            }
            finalResult.sortedByDescending { it.transaction.date }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Matérialise une occurrence virtuelle d'une série récurrente en une véritable exception persistée en DB.
     * Si l'exception existe déjà, retourne son ID.
     */
    suspend fun materializeOccurrence(seriesId: Long, seriesDate: Long): Long {
        // 1. Vérifier si l'exception existe déjà
        val existing = transactionDao.getException(seriesId.toString(), seriesDate)
        if (existing != null) return existing.id

        // 2. Récupérer la série
        val series = recurringSeriesDao.getSeriesById(seriesId) ?: return -1L

        // 3. Créer l'exception
        val exception = TransactionEntity(
            title = series.title,
            amount = series.amount,
            type = series.type,
            status = TransactionStatus.PLANNED,
            date = seriesDate,
            accountId = series.accountId,
            categoryId = series.categoryId,
            note = series.note,
            seriesId = series.id.toString(),
            seriesDate = seriesDate,
            isException = true,
            linkedGoalId = series.linkedGoalId,
            linkedDebtId = series.linkedDebtId,
        )
        return transactionDao.upsert(exception)
    }
    /**
     * Retourne toutes les transactions d'un mois : les transactions ponctuelles, les exceptions,
     * et les occurrences virtuelles générées à la volée à partir des séries actives.
     * Intègre le filtrage en temps réel des suppressions en attente (Undo).
     */
    fun observeTransactionsBetween(start: Long, end: Long): Flow<List<TransactionWithRelations>> {
        val allTransactionsFlow = transactionDao.observeBetween(start, end)
        val seriesFlow = recurringSeriesDao.observeActiveSeries()
        val accountsFlow = accountDao.observeAll()
        val categoriesFlow = categoryDao.observeAll()

        return combine(
            allTransactionsFlow, 
            seriesFlow, 
            accountsFlow, 
            categoriesFlow,
            _pendingDeletes,
            _pendingSeriesDeletes,
            _pendingSeriesFromDates
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val allInPeriod = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST")
            val seriesList = args[1] as List<RecurringSeriesEntity>
            @Suppress("UNCHECKED_CAST")
            val accounts = args[2] as List<AccountEntity>
            @Suppress("UNCHECKED_CAST")
            val categories = args[3] as List<CategoryEntity>
            @Suppress("UNCHECKED_CAST")
            val pending = args[4] as Set<Long>
            @Suppress("UNCHECKED_CAST")
            val pendingSeries = args[5] as Map<String, SeriesDeletionMode>
            @Suppress("UNCHECKED_CAST")
            val pendingDates = args[6] as Map<String, Long>

            // On garde les transactions réelles (non supprimées) pour l'affichage final
            // On applique le filtrage des suppressions en attente (Undo)
            val finalResult = allInPeriod.filter { twr -> 
                isTransactionVisible(
                    twr.transaction,
                    pending,
                    pendingSeries,
                    pendingDates
                )
            }.toMutableList()

            for (series in seriesList) {
                val sIdStr = series.id.toString()
                // Barrière de sécurité : on ne génère rien pour une série annulée ou en cours de suppression ALL
                if (series.isCancelled || pendingSeries[sIdStr] == SeriesDeletionMode.ALL) continue

                // 1. Calculer les occurrences virtuelles de cette série via le moteur
                val occurrences = RecurrenceEngine.generateOccurrences(series, start, end)
                
                // 2. Pour chaque date prévue, on vérifie si une exception (même supprimée) existe déjà
                for (virtualTx in occurrences) {
                    val occEpoch = virtualTx.date
                    
                    // Vérifier si cette occurrence virtuelle spécifique est en cours de suppression FUTURE
                    val seriesPendingMode = pendingSeries[sIdStr]
                    if (seriesPendingMode == SeriesDeletionMode.FUTURE) {
                        val fromDate = pendingDates[sIdStr]
                        if (fromDate != null && occEpoch >= fromDate) continue
                    }

                    // Une exception (matérialisée ou supprimée) bloque la génération du virtuel
                    val hasExistingEntry = allInPeriod.any { 
                        it.transaction.seriesId == sIdStr && it.transaction.seriesDate == occEpoch 
                    }
                    
                    if (!hasExistingEntry) {
                        // Sécurité finale : ne pas afficher si le virtuel vient d'être supprimé (même si pas encore en base)
                        if (virtualTx.id in pending) continue

                        val account = accounts.find { it.id == series.accountId }
                        val category = categories.find { it.id == series.categoryId }
                        
                        finalResult.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                    }
                }
            }
            
            finalResult.sortedBy { it.transaction.date }
        }.flowOn(Dispatchers.Default)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTransaction(id: Long): Flow<TransactionWithRelations?> {
        if (id < 0L) {
            // Cas d'un virtuel pur (non encore matérialisé)
            return flow {
                val initial = getTransactionById(id)
                if (initial == null) {
                    emit(null)
                    return@flow
                }
                val sId = initial.transaction.seriesId
                val sDate = initial.transaction.seriesDate ?: initial.transaction.date
                if (sId == null) {
                    emit(initial)
                } else {
                    emitAll(combine(
                        transactionDao.observeSeries(sId),
                        recurringSeriesDao.observeActiveSeries(),
                        accountDao.observeAll(),
                        categoryDao.observeAll()
                    ) { realTxs, allSeries, accounts, categories ->
                        val realMatch = realTxs.find { it.transaction.seriesDate == sDate }
                        if (realMatch != null) return@combine realMatch
                        val series = allSeries.find { it.id.toString() == sId }
                        if (series != null) {
                            val virtuals = RecurrenceEngine.generateOccurrences(series, sDate, sDate)
                            val vMatch = virtuals.find { it.seriesDate == sDate }
                            if (vMatch != null) {
                                val account = accounts.find { it.id == series.accountId }
                                val category = categories.find { it.id == series.categoryId }
                                return@combine TransactionWithRelations(vMatch, category, account, emptyList())
                            }
                        }
                        null
                    })
                }
            }
        }

        // Cas d'une transaction réelle (id >= 0) : on réagit aux changements de slot (seriesDate)
        return transactionDao.observeById(id).flatMapLatest { current ->
            if (current == null) return@flatMapLatest flowOf(null)

            val sId = current.transaction.seriesId
            val sDate = current.transaction.seriesDate ?: current.transaction.date

            if (sId == null) {
                flowOf(current)
            } else {
                combine(
                    transactionDao.observeSeries(sId),
                    recurringSeriesDao.observeActiveSeries(),
                    accountDao.observeAll(),
                    categoryDao.observeAll()
                ) { realTxs, allSeries, accounts, categories ->
                    val realMatch = realTxs.find { it.transaction.seriesDate == sDate }
                    if (realMatch != null) return@combine realMatch
                    val series = allSeries.find { it.id.toString() == sId }
                    if (series != null) {
                        val virtuals = RecurrenceEngine.generateOccurrences(series, sDate, sDate)
                        val vMatch = virtuals.find { it.seriesDate == sDate }
                        if (vMatch != null) {
                            val account = accounts.find { it.id == series.accountId }
                            val category = categories.find { it.id == series.categoryId }
                            return@combine TransactionWithRelations(vMatch, category, account, emptyList())
                        }
                    }
                    null
                }
            }
        }
    }

    suspend fun saveTransaction(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        // Gérer le champ paidAt si absent
        val finalTx = if (tx.status == TransactionStatus.PAID && tx.paidAt == null) {
            tx.copy(paidAt = System.currentTimeMillis())
        } else if (tx.status == TransactionStatus.PLANNED && tx.paidAt != null) {
            tx.copy(paidAt = null)
        } else {
            tx
        }

        // 1. Sauvegarder la transaction initiale (ou mettre à jour si tx.id != 0L)
        val id = transactionDao.upsert(finalTx)
        val txId = if (finalTx.id == 0L) id else finalTx.id
        transactionDao.clearTags(txId)
        tagIds.forEach { transactionDao.addTagCrossRef(TransactionTagCrossRef(txId, it)) }

        // 2. Recalculer les progrès si lié à un objectif ou une dette
        finalTx.linkedGoalId?.let { recalculateGoalProgress(it) }
        finalTx.linkedDebtId?.let { recalculateDebtProgress(it) }

        return txId
    }

    suspend fun recalculateGoalProgress(goalId: Long) {
        val goal = goalDao.getById(goalId) ?: return
        val sum = transactionDao.getSumForGoal(goalId)
        val totalSaved = goal.startingBalance + sum
        goalDao.updateSavedAmount(goalId, totalSaved)
    }

    suspend fun recalculateDebtProgress(debtId: Long) {
        val debt = debtDao.getById(debtId) ?: return
        val sum = transactionDao.getSumForDebt(debtId)
        val totalRepaid = debt.startingBalance + sum
        debtDao.updateRepaidAmount(debtId, totalRepaid)
    }

    /**
     * Gère la sauvegarde d'une transaction avec transition de type intelligente.
     * Cette fonction orchestre le passage de ponctuel à série, de série à ponctuel,
     * ou la mise à jour d'une série existante selon la portée choisie.
     */
    suspend fun saveWithTransition(
        editingId: Long?, // ID de la transaction physique ou virtuelle (si < 0)
        title: String,
        amount: Double,
        type: TransactionType,
        date: Long,
        accountId: Long,
        categoryId: Long,
        subCategoryId: Long? = null,
        note: String?,
        frequency: com.lop.budget.domain.model.RecurrenceFrequency,
        interval: Int,
        daysOfWeek: String?,
        endDate: Long?,
        maxOccurrences: Int?,
        linkedGoalId: Long?,
        linkedDebtId: Long?,
        tagIds: List<Long>,
        scope: EditScope = EditScope.SINGLE, // Portée de la modification
        status: TransactionStatus? = null // Nouveau statut optionnel (ex: pour togglePaid)
    ): Long {
        // 1. Déterminer l'origine (Série parente) et le SLOT d'origine
        val initialTwr = editingId?.let { getTransactionById(it) }
        val seriesIdFromSource = initialTwr?.transaction?.seriesId?.toLongOrNull()
        
        // Le seriesDate d'origine est LA CLÉ pour bloquer le slot virtuel
        // Si on édite un virtuel, son .date EST le seriesDate (le slot).
        // Si on édite une exception existante, son .seriesDate est déjà renseigné.
        val originalSeriesDate = initialTwr?.transaction?.seriesDate ?: initialTwr?.transaction?.date ?: date

        // 2. Gérer la matérialisation si on édite une occurrence virtuelle
        var finalEditingId = editingId
        var currentTwr = initialTwr

        // LOP-98 : Ne plus matérialiser systématiquement avant le choix de la portée.
        // La portée ALL gère elle-même son rattachement au nouveau slot.
        if (finalEditingId != null && finalEditingId < 0L && scope != EditScope.ALL) {
            // C'est une occurrence virtuelle : on la matérialise physiquement en base
            // MAIS on utilise originalSeriesDate pour le slot
            if (seriesIdFromSource != null) {
                finalEditingId = materializeOccurrence(seriesIdFromSource, originalSeriesDate)
                currentTwr = transactionDao.getById(finalEditingId!!)
            }
        }
        
        val currentSeriesId = seriesIdFromSource ?: currentTwr?.transaction?.seriesId?.toLongOrNull()
        val finalStatus = status ?: currentTwr?.transaction?.status ?: TransactionStatus.PLANNED

        // Branchement selon la portée
        when (scope) {
            EditScope.SINGLE -> {
                if (frequency == com.lop.budget.domain.model.RecurrenceFrequency.NONE) {
                    // --- CAS : VERS PONCTUEL ---
                    if (currentSeriesId != null) {
                        // On débranche de la série : on arrête la série avant ce slot
                        cancelSeries(currentSeriesId.toString(), SeriesDeletionMode.FUTURE, originalSeriesDate)
                        
                        val singleTx = TransactionEntity(
                            id = finalEditingId ?: 0L,
                            title = title,
                            amount = amount,
                            type = type,
                            status = finalStatus,
                            date = date,
                            accountId = accountId,
                            categoryId = categoryId,
                            subCategoryId = subCategoryId,
                            note = note,
                            paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null,
                            seriesId = null, // Débranchée
                            seriesDate = null,
                            isException = false,
                            linkedGoalId = linkedGoalId,
                            linkedDebtId = linkedDebtId
                        )
                        return saveTransaction(singleTx, tagIds)
                    } else {
                        // Mise à jour ponctuelle standard
                        val tx = TransactionEntity(
                            id = finalEditingId ?: 0L,
                            title = title,
                            amount = amount,
                            type = type,
                            status = finalStatus,
                            date = date,
                            accountId = accountId,
                            categoryId = categoryId,
                            subCategoryId = subCategoryId,
                            note = note,
                            paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null,
                            linkedGoalId = linkedGoalId,
                            linkedDebtId = linkedDebtId
                        )
                        return saveTransaction(tx, tagIds)
                    }
                } else {
                    // --- CAS : VERS SÉRIE (Mise à jour d'occurrence ou conversion ponctuel -> série) ---
                    if (currentSeriesId != null) {
                        // On met à jour l'exception. 
                        // IMPORTANT : seriesDate RESTE originalSeriesDate pour bloquer le slot !
                        val tx = TransactionEntity(
                            id = finalEditingId ?: 0L,
                            title = title,
                            amount = amount,
                            type = type,
                            status = finalStatus,
                            date = date, // Nouvelle date d'affichage
                            accountId = accountId,
                            categoryId = categoryId,
                            subCategoryId = subCategoryId,
                            note = note,
                            paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null,
                            seriesId = currentSeriesId.toString(),
                            seriesDate = originalSeriesDate, // On garde le lien vers le slot d'origine
                            isException = true,
                            linkedGoalId = linkedGoalId,
                            linkedDebtId = linkedDebtId
                        )
                        return saveTransaction(tx, tagIds)
                    } else {
                        // Conversion ponctuel -> série
                        finalEditingId?.let { hardDeleteTransaction(it) }
                        
                        val series = RecurringSeriesEntity(
                            title = title,
                            amount = amount,
                            type = type,
                            categoryId = categoryId,
                            subCategoryId = subCategoryId,
                            accountId = accountId,
                            frequency = frequency,
                            interval = interval,
                            startDate = date,
                            endDate = endDate,
                            maxOccurrences = maxOccurrences,
                            daysOfWeek = daysOfWeek,
                            isCancelled = false,
                            note = note,
                            linkedGoalId = linkedGoalId,
                            linkedDebtId = linkedDebtId
                        )
                        val newSeriesId = saveRecurringSeries(series)
                        return materializeOccurrence(newSeriesId, date)
                    }
                }
            }
            
            EditScope.FUTURE -> {
                // Mise à jour FUTURE : On tronque à originalSeriesDate pour ne pas laisser de trou
                if (currentSeriesId != null) {
                    val newSeries = RecurringSeriesEntity(
                        title = title,
                        amount = amount,
                        type = type,
                        categoryId = categoryId,
                        subCategoryId = subCategoryId,
                        accountId = accountId,
                        frequency = frequency,
                        interval = interval,
                        startDate = date,
                        endDate = endDate,
                        maxOccurrences = maxOccurrences,
                        daysOfWeek = daysOfWeek,
                        note = note,
                        linkedGoalId = linkedGoalId,
                        linkedDebtId = linkedDebtId
                    )
                    // On arrête l'ancienne série juste AVANT la borne la plus basse (ancien slot ou nouvelle date)
                    // pour éviter les doublons si on avance la date.
                    val newSeriesId = updateSeriesFrom(
                        seriesId = currentSeriesId,
                        truncateFrom = originalSeriesDate,
                        updatedSeries = newSeries,
                        newStartDate = date
                    )
                    // On matérialise la première occurrence de la nouvelle série
                    val newTxId = materializeOccurrence(newSeriesId, date)
                    
                    // LOP-97 : Ne pas perdre le statut et les tags à la matérialisation
                    val materializedTwr = getTransactionById(newTxId)
                    if (materializedTwr != null) {
                        saveTransaction(materializedTwr.transaction.copy(
                            status = finalStatus,
                            paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null
                        ), tagIds)
                    }
                    
                    return newTxId
                }
                return finalEditingId ?: 0L
            }
            
            EditScope.ALL -> {
                // Mise à jour de toute la série
                if (currentSeriesId != null) {
                    val existing = getSeriesById(currentSeriesId)
                    if (existing != null) {
                        // Si le jour a changé, on doit décaler le startDate de la série
                        // pour que les occurrences virtuelles suivent le nouveau rythme
                        val newStartDate = if (date != originalSeriesDate) {
                            val calendar = java.util.Calendar.getInstance()
                            calendar.timeInMillis = date
                            val newDay = calendar.get(java.util.Calendar.DAY_OF_MONTH)
                            
                            // On ré-applique le nouveau jour au startDate d'origine
                            calendar.timeInMillis = existing.startDate
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, newDay)
                            calendar.timeInMillis
                        } else existing.startDate

                        val updatedSeries = existing.copy(
                            title = title,
                            amount = amount,
                            type = type,
                            categoryId = categoryId,
                            subCategoryId = subCategoryId,
                            accountId = accountId,
                            frequency = frequency,
                            interval = interval,
                            startDate = newStartDate,
                            endDate = endDate,
                            maxOccurrences = maxOccurrences,
                            daysOfWeek = daysOfWeek,
                            note = note,
                            linkedGoalId = linkedGoalId,
                            linkedDebtId = linkedDebtId
                        )
                        updateEntireSeries(currentSeriesId, updatedSeries)

                        // LOP-98 : Déterminer le nouveau slot canonique pour l'occurrence courante.
                        // On doit respecter l'heure/seconde de la série pour que la fusion (seriesDate) fonctionne.
                        val targetSlot = if (date != originalSeriesDate) {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = date }
                            val baseCal = java.util.Calendar.getInstance().apply { timeInMillis = newStartDate }
                            cal.set(java.util.Calendar.HOUR_OF_DAY, baseCal.get(java.util.Calendar.HOUR_OF_DAY))
                            cal.set(java.util.Calendar.MINUTE, baseCal.get(java.util.Calendar.MINUTE))
                            cal.set(java.util.Calendar.SECOND, baseCal.get(java.util.Calendar.SECOND))
                            cal.set(java.util.Calendar.MILLISECOND, baseCal.get(java.util.Calendar.MILLISECOND))
                            cal.timeInMillis
                        } else originalSeriesDate

                        // Propager aux exceptions matérialisées (on change tout SAUF le seriesDate/slot)
                        // Note : Pour les autres exceptions que la courante, le seriesDate reste inchangé 
                        // pour l'instant car le ticket cible l'occurrence éditée.
                        transactionDao.updateSeriesExceptions(
                            seriesId = currentSeriesId.toString(),
                            title = title,
                            amount = amount,
                            type = type,
                            categoryId = categoryId,
                            accountId = accountId,
                            note = note
                        )

                        // LOP-98 : Réaligner l'occurrence courante sur le nouveau slot
                        if (currentTwr != null) {
                            // Cas 1 : L'occurrence était déjà matérialisée
                            return saveTransaction(currentTwr.transaction.copy(
                                title = title,
                                amount = amount,
                                type = type,
                                status = finalStatus,
                                categoryId = categoryId,
                                accountId = accountId,
                                note = note,
                                date = date,
                                seriesDate = targetSlot, // Réalignement crucial
                                isException = true,
                                paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr.transaction.paidAt ?: System.currentTimeMillis()) else null
                            ), tagIds)
                        } else if (finalEditingId != null && finalEditingId < 0L) {
                            // Cas 2 : L'occurrence était virtuelle (non matérialisée avant car portée ALL)
                            val newTxId = materializeOccurrence(currentSeriesId, targetSlot)
                            val materializedTwr = getTransactionById(newTxId)
                            if (materializedTwr != null) {
                                saveTransaction(materializedTwr.transaction.copy(
                                    title = title,
                                    amount = amount,
                                    type = type,
                                    status = finalStatus,
                                    categoryId = categoryId,
                                    accountId = accountId,
                                    note = note,
                                    date = date, // Date d'affichage (peut inclure l'heure du picker)
                                    seriesDate = targetSlot, // Slot canonique pour la fusion
                                    paidAt = if (finalStatus == TransactionStatus.PAID) System.currentTimeMillis() else null
                                ), tagIds)
                            }
                            return newTxId
                        }
                    }
                }
                return finalEditingId ?: 0L
            }
        }
        return 0L
    }

    suspend fun updateEntireSeries(seriesId: Long, updatedSeries: RecurringSeriesEntity) {
        recurringSeriesDao.update(updatedSeries.copy(id = seriesId))
    }

    suspend fun updateSeriesFrom(
        seriesId: Long,
        truncateFrom: Long,
        updatedSeries: RecurringSeriesEntity,
        newStartDate: Long = truncateFrom
    ): Long {
        // 1. Tronquer l'ancienne série
        // On tronque au plus tôt des deux bornes pour ne jamais laisser les deux séries
        // générer sur la même période (cas où la nouvelle date est antérieure à l'ancienne)
        cancelSeries(seriesId.toString(), SeriesDeletionMode.FUTURE, minOf(truncateFrom, newStartDate))

        // 2. Sauvegarder la nouvelle série (ID = 0 pour auto-générer)
        return recurringSeriesDao.upsert(updatedSeries.copy(id = 0, startDate = newStartDate))
    }

    suspend fun saveRecurringSeries(series: RecurringSeriesEntity): Long {
        return recurringSeriesDao.upsert(series)
    }

    suspend fun getSeriesById(id: Long) = recurringSeriesDao.getSeriesById(id)

    suspend fun getTransactionById(id: Long): TransactionWithRelations? {
        if (id >= 0L) return transactionDao.getById(id)
        
        // --- CAS : TRANSACTION VIRTUELLE (ID < 0) ---
        // On doit reconstituer l'objet à partir de sa série parente
        // L'ID virtuel contient indirectement le seriesId et la date, 
        // mais pour être sûr, on scanne les séries actives sur une plage large
        // (Ou on pourrait passer par une map de cache, mais le scan est plus robuste ici)
        
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.add(java.util.Calendar.YEAR, -1)
        val start = calendar.timeInMillis
        calendar.add(java.util.Calendar.YEAR, 2)
        val end = calendar.timeInMillis

        val seriesList = recurringSeriesDao.observeActiveSeries().first()
        for (series in seriesList) {
            val occurrences = com.lop.budget.domain.RecurrenceEngine.generateOccurrences(series, start, end)
            val match = occurrences.find { it.id == id }
            if (match != null) {
                // On a trouvé le virtuel, on récupère ses relations pour l'UI
                val account = accountDao.getById(match.accountId)
                val category = categoryDao.getById(match.categoryId)
                return TransactionWithRelations(match, category, account, emptyList())
            }
        }
        
        return null
    }

    suspend fun cancelSeries(seriesIdStr: String, mode: SeriesDeletionMode, fromDate: Long? = null) {
        val seriesId = seriesIdStr.toLongOrNull() ?: return
        
        when (mode) {
            SeriesDeletionMode.ALL -> {
                // 1. Annuler la série
                recurringSeriesDao.updateCancelled(seriesId, true)
                // 2. Supprimer TOUTES les transactions matérialisées
                transactionDao.softDeleteSeries(seriesIdStr)
            }
            SeriesDeletionMode.FUTURE -> {
                // 1. Mettre à jour la date de fin de la série pour arrêter la génération future
                val series = recurringSeriesDao.getSeriesById(seriesId)
                if (series != null && fromDate != null) {
                    // On met une date de fin juste avant l'occurrence sélectionnée, mais on garde isCancelled = false
                    recurringSeriesDao.upsert(series.copy(endDate = fromDate - 1))
                } else {
                    recurringSeriesDao.updateCancelled(seriesId, true)
                }
                // 2. Supprimer les transactions matérialisées à partir de la date
                if (fromDate != null) {
                    transactionDao.softDeleteSeriesFrom(seriesIdStr, fromDate)
                }
            }
        }
    }

    /** Modifie la catégorie même si la transaction est déjà payée. */
    suspend fun changeCategory(transactionId: Long, categoryId: Long) =
        transactionDao.updateCategory(transactionId, categoryId)

    suspend fun changeDate(transactionId: Long, date: Long) =
        transactionDao.updateDate(transactionId, date)

    suspend fun changeAccount(transactionId: Long, accountId: Long) =
        transactionDao.updateAccount(transactionId, accountId)

    /** Bascule le statut d'une transaction entre PAID et PLANNED. */
    suspend fun toggleTransactionStatus(twr: TransactionWithRelations) {
        val tx = twr.transaction
        val newStatus = if (tx.status == TransactionStatus.PAID) TransactionStatus.PLANNED else TransactionStatus.PAID
        
        // On délègue maintenant à saveWithTransition pour garantir l'unification et la matérialisation
        saveWithTransition(
            editingId = tx.id,
            title = tx.title,
            amount = tx.amount,
            type = tx.type,
            date = tx.date,
            accountId = tx.accountId,
            categoryId = tx.categoryId,
            subCategoryId = tx.subCategoryId,
            note = tx.note,
            frequency = com.lop.budget.domain.model.RecurrenceFrequency.NONE, // Ce n'est pas un changement de règle
            interval = 1,
            daysOfWeek = null,
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = tx.linkedGoalId,
            linkedDebtId = tx.linkedDebtId,
            tagIds = twr.tags.map { it.id },
            scope = EditScope.SINGLE,
            status = newStatus
        )
    }

    /**
     * Supprime une occurrence de transaction (soft delete).
     * Matérialise l'occurrence si elle est virtuelle.
     */
    suspend fun softDeleteTransactionOccurrence(twr: TransactionWithRelations) {
        val tx = twr.transaction
        val realId = if (tx.id < 0L && tx.seriesId != null && tx.seriesDate != null) {
            materializeOccurrence(tx.seriesId.toLong(), tx.seriesDate)
        } else {
            tx.id
        }

        if (realId >= 0L) {
            softDeleteTransaction(realId)
        }
    }

    suspend fun setStatus(transactionId: Long, status: String) {
        val paidAt = if (status == TransactionStatus.PAID.name) System.currentTimeMillis() else null
        transactionDao.updateStatus(transactionId, status, paidAt)
        // Recalculer le progrès si la transaction est liée à un objectif ou une dette
        val twr = transactionDao.getById(transactionId)
        twr?.transaction?.linkedGoalId?.let { recalculateGoalProgress(it) }
        twr?.transaction?.linkedDebtId?.let { recalculateDebtProgress(it) }
    }

    suspend fun softDeleteTransaction(id: Long) {
        val twr = transactionDao.getById(id)
        transactionDao.softDelete(id)
        twr?.transaction?.linkedGoalId?.let { recalculateGoalProgress(it) }
        twr?.transaction?.linkedDebtId?.let { recalculateDebtProgress(it) }
    }

    suspend fun restoreTransaction(id: Long) {
        transactionDao.restore(id)
        val twr = transactionDao.getById(id)
        twr?.transaction?.linkedGoalId?.let { recalculateGoalProgress(it) }
        twr?.transaction?.linkedDebtId?.let { recalculateDebtProgress(it) }
    }

    suspend fun hardDeleteTransaction(id: Long) {
        val twr = transactionDao.getById(id)
        transactionDao.hardDelete(id)
        twr?.transaction?.linkedGoalId?.let { recalculateGoalProgress(it) }
        twr?.transaction?.linkedDebtId?.let { recalculateDebtProgress(it) }
    }

    // Référentiels
    fun observeAccounts() = accountDao.observeAll()
    fun observeCategories() = categoryDao.observeAll()
    fun observeCategoriesByType(type: TransactionType) = categoryDao.observeByType(type.name)
    fun observeTags() = tagDao.observeAll()
    fun observeGoals() = goalDao.observeAll()
    fun observeDebts() = debtDao.observeAll()

    /**
     * Observe les soldes de tous les comptes en temps réel.
     * @return Map<AccountId, Balance>
     */
    fun observeAccountBalances(): Flow<Map<Long, Double>> = combine(
        accountDao.observeAll(),
        transactionDao.observeAll()
    ) { accounts, transactions ->
        BalanceEngine.calculateBalances(accounts, transactions.map { it.transaction })
    }.flowOn(Dispatchers.IO)

    /**
     * Observe le solde total consolidé.
     */
    fun observeTotalBalance(): Flow<Double> = combine(
        accountDao.observeAll(),
        observeAccountBalances()
    ) { accounts, balances ->
        BalanceEngine.calculateTotalBalance(accounts, balances)
    }.flowOn(Dispatchers.IO)

    suspend fun saveAccount(a: AccountEntity) = accountDao.upsert(a)
    suspend fun getAccountById(id: Long) = accountDao.getById(id)
    suspend fun deleteAccount(id: Long) = accountDao.delete(id)
    suspend fun saveCategory(c: CategoryEntity) = categoryDao.upsert(c)
    suspend fun getCategoryById(id: Long) = categoryDao.getById(id)
    suspend fun deleteCategory(id: Long) = categoryDao.delete(id)
    suspend fun saveTag(t: TagEntity) = tagDao.upsert(t)
    suspend fun deleteTag(id: Long) = tagDao.delete(id)
    suspend fun getTagUsageCount(id: Long) = tagDao.countUsages(id)
    suspend fun saveGoal(g: GoalEntity) = goalDao.upsert(g)
    suspend fun getGoalById(id: Long) = goalDao.getById(id)
    suspend fun deleteGoal(id: Long) = goalDao.delete(id)
    suspend fun saveDebt(d: DebtEntity) = debtDao.upsert(d)
    suspend fun getDebtById(id: Long) = debtDao.getById(id)
    suspend fun deleteDebt(id: Long) = debtDao.delete(id)

    private fun isTransactionVisible(
        tx: TransactionEntity,
        pendingDeletes: Set<Long>,
        pendingSeriesDeletes: Map<String, SeriesDeletionMode>,
        pendingSeriesFromDates: Map<String, Long>
    ): Boolean {
        if (tx.deleted || tx.id in pendingDeletes) return false

        val seriesPendingMode = if (tx.seriesId != null) pendingSeriesDeletes[tx.seriesId] else null
        val isSeriesPending = when (seriesPendingMode) {
            SeriesDeletionMode.ALL -> true
            SeriesDeletionMode.FUTURE -> {
                val fromDate = pendingSeriesFromDates[tx.seriesId]
                fromDate != null && tx.date >= fromDate
            }
            null -> false
        }
        return !isSeriesPending
    }

    suspend fun getDefaultExpenseCategoryId(): Long {
        // Cherche "Alimentation" ou "Autre" ou la première dépense
        val all = categoryDao.observeAll().first()
        return all.find { it.name.contains("Alimentation", ignoreCase = true) }?.id
            ?: all.find { it.type == TransactionType.EXPENSE }?.id
            ?: 1L
    }
}

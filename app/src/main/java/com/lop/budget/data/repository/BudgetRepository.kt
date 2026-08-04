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
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    fun setPendingDelete(txId: Long, isPending: Boolean) {
        if (isPending) _pendingDeletes.value += txId
        else _pendingDeletes.value -= txId
    }

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
            val searchStartLocal = Instant.ofEpochMilli(searchStart).atZone(zone).toLocalDate()
            val searchEndLocal = Instant.ofEpochMilli(searchEnd).atZone(zone).toLocalDate()

            // Résultat final : commencer par les réels déjà filtrés par DAO (Undo déjà géré car on filtre au final)
            val finalResult = realTxs.filter { twr ->
                val tx = twr.transaction
                if (tx.id in pending) return@filter false
                val seriesPendingMode = if (tx.seriesId != null) pendingSeries[tx.seriesId] else null
                when (seriesPendingMode) {
                    SeriesDeletionMode.ALL -> false
                    SeriesDeletionMode.FUTURE -> {
                        val fromDate = pendingDates[tx.seriesId]
                        fromDate == null || tx.date < fromDate
                    }
                    null -> true
                }
            }.toMutableList()

            // 3. Ajouter les occurrences virtuelles des séries qui matchent
            for (series in seriesList) {
                val sIdStr = series.id.toString()
                
                // Filtre sur les critères de recherche (Texte, Compte, Catégorie)
                val matchesText = query.isBlank() || series.title.contains(query, ignoreCase = true) || series.note?.contains(query, ignoreCase = true) == true
                val matchesAccount = accountId == null || series.accountId == accountId
                val matchesCategory = categoryId == null || series.categoryId == categoryId
                
                if (matchesText && matchesAccount && matchesCategory) {
                    // Barrière de sécurité Undo
                    if (pendingSeries[sIdStr] == SeriesDeletionMode.ALL) continue

                    val occurrences = generateOccurrencesForMonth(series, searchStartLocal, searchEndLocal, zone)
                    for (occDate in occurrences) {
                        val occEpoch = occDate.atStartOfDay(zone).toInstant().toEpochMilli()

                        // Filtre Undo Future
                        if (pendingSeries[sIdStr] == SeriesDeletionMode.FUTURE) {
                            val fromDate = pendingDates[sIdStr]
                            if (fromDate != null && occEpoch >= fromDate) continue
                        }

                        // Vérifier si une exception existe déjà (matérialisée ou supprimée)
                        val hasExisting = realTxs.any { it.transaction.seriesId == sIdStr && it.transaction.seriesDate == occEpoch }
                        if (!hasExisting) {
                            val virtualId = -abs("${series.id}_$occEpoch".hashCode().toLong()) - 1L
                            if (virtualId in pending) continue

                            val virtualTx = TransactionEntity(
                                id = virtualId,
                                title = series.title,
                                amount = series.amount,
                                type = series.type,
                                status = TransactionStatus.PLANNED,
                                date = occEpoch,
                                accountId = series.accountId,
                                categoryId = series.categoryId,
                                note = series.note,
                                seriesId = sIdStr,
                                seriesDate = occEpoch,
                                isException = false,
                                linkedGoalId = series.linkedGoalId,
                                linkedDebtId = series.linkedDebtId
                            )
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

            val zone = ZoneId.systemDefault()
            val startLocalDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val endLocalDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()

            // On garde les transactions réelles (non supprimées) pour l'affichage final
            // On applique le filtrage des suppressions en attente (Undo)
            val finalResult = allInPeriod.filter { twr -> 
                val tx = twr.transaction
                if (tx.deleted || tx.id in pending) return@filter false
                
                val seriesPendingMode = if (tx.seriesId != null) pendingSeries[tx.seriesId] else null
                val isSeriesPending = when (seriesPendingMode) {
                    SeriesDeletionMode.ALL -> true
                    SeriesDeletionMode.FUTURE -> {
                        val fromDate = pendingDates[tx.seriesId]
                        fromDate != null && tx.date >= fromDate
                    }
                    null -> false
                }
                !isSeriesPending
            }.toMutableList()

            for (series in seriesList) {
                val sIdStr = series.id.toString()
                // Barrière de sécurité : on ne génère rien pour une série annulée ou en cours de suppression ALL
                if (series.isCancelled || pendingSeries[sIdStr] == SeriesDeletionMode.ALL) continue

                // 1. Calculer les occurrences virtuelles de cette série qui tombent dans ce mois
                val occurrences = generateOccurrencesForMonth(series, startLocalDate, endLocalDate, zone)
                
                // 2. Pour chaque date prévue, on vérifie si une exception (même supprimée) existe déjà
                for (occDate in occurrences) {
                    val occEpoch = occDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    
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
                        val virtualId = -abs("${series.id}_$occEpoch".hashCode().toLong()) - 1L
                        
                        // Sécurité finale : ne pas afficher si le virtuel vient d'être supprimé (même si pas encore en base)
                        if (virtualId in pending) continue

                        val virtualTx = TransactionEntity(
                            id = virtualId,
                            title = series.title,
                            amount = series.amount,
                            type = series.type,
                            status = TransactionStatus.PLANNED,
                            date = occEpoch,
                            accountId = series.accountId,
                            categoryId = series.categoryId,
                            note = series.note,
                            seriesId = sIdStr,
                            seriesDate = occEpoch,
                            isException = false,
                            linkedGoalId = series.linkedGoalId,
                            linkedDebtId = series.linkedDebtId
                        )
                        val account = accounts.find { it.id == series.accountId }
                        val category = categories.find { it.id == series.categoryId }
                        
                        finalResult.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                    }
                }
            }
            
            finalResult.sortedBy { it.transaction.date }
        }.flowOn(Dispatchers.Default)
    }

    private fun generateOccurrencesForMonth(
        series: RecurringSeriesEntity, 
        monthStart: LocalDate, 
        monthEnd: LocalDate, 
        zone: ZoneId
    ): List<LocalDate> {
        val occurrences = mutableListOf<LocalDate>()
        val seriesStart = Instant.ofEpochMilli(series.startDate).atZone(zone).toLocalDate()
        val seriesEnd = series.endDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        
        // Si la série se termine avant le début du mois, ou commence après la fin du mois
        if (seriesEnd != null && seriesEnd.isBefore(monthStart)) return occurrences
        if (seriesStart.isAfter(monthEnd)) return occurrences
        
        var current = seriesStart
        var count = 0
        val max = series.maxOccurrences ?: Int.MAX_VALUE
        
        while (count < max) {
            if (seriesEnd != null && current.isAfter(seriesEnd)) break
            
            // Si l'occurrence est dans le mois ciblé, on l'ajoute
            if (!current.isBefore(monthStart) && !current.isAfter(monthEnd)) {
                occurrences.add(current)
            }
            
            // Si on a dépassé le mois, on peut s'arrêter (optimisation)
            if (current.isAfter(monthEnd)) break
            
            // Prochaine occurrence
            current = when (series.frequency) {
                com.lop.budget.domain.model.RecurrenceFrequency.DAILY -> current.plusDays(series.interval.toLong())
                com.lop.budget.domain.model.RecurrenceFrequency.WEEKLY -> current.plusWeeks(series.interval.toLong())
                com.lop.budget.domain.model.RecurrenceFrequency.MONTHLY -> current.plusMonths(series.interval.toLong())
                com.lop.budget.domain.model.RecurrenceFrequency.YEARLY -> current.plusYears(series.interval.toLong())
                else -> break
            }
            count++
        }
        
        return occurrences
    }
    fun observeTransaction(id: Long) = transactionDao.observeById(id)
    // observePaidSum est supprimé car le calcul se fait désormais en mémoire dans le ViewModel
    // à partir de observeTransactionsBetween() qui inclut les occurrences virtuelles.

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
        // Optionnel : Mettre à jour isCompleted si totalSaved >= targetAmount
    }

    suspend fun recalculateDebtProgress(debtId: Long) {
        val debt = debtDao.getById(debtId) ?: return
        val sum = transactionDao.getSumForDebt(debtId)
        val totalRepaid = debt.startingBalance + sum
        debtDao.updateRepaidAmount(debtId, totalRepaid)
        // Optionnel : Mettre à jour isFullyRepaid si totalRepaid >= totalAmount
    }

    /**
     * Gère la sauvegarde d'une transaction avec transition de type intelligente.
     * Cette fonction orchestre le passage de ponctuel à série, de série à ponctuel,
     * ou la mise à jour d'une série existante.
     */
    suspend fun saveWithTransition(
        editingId: Long?, // ID de la transaction physique éditée (si existe)
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
        tagIds: List<Long>
    ) {
        val currentTwr = editingId?.let { transactionDao.getById(it) }
        val currentSeriesId = currentTwr?.transaction?.seriesId?.toLongOrNull()

        if (frequency == com.lop.budget.domain.model.RecurrenceFrequency.NONE) {
            // --- CAS 1 : VERS PONCTUEL ---
            if (currentSeriesId != null) {
                // On passe de série à ponctuel : 
                // 1. On arrête la série parente pour le futur (conserve le passé)
                cancelSeries(currentSeriesId.toString(), SeriesDeletionMode.FUTURE, date)
                
                // 2. On transforme l'occurrence éditée en transaction isolée
                val singleTx = TransactionEntity(
                    id = editingId ?: 0L,
                    title = title,
                    amount = amount,
                    type = type,
                    status = currentTwr.transaction.status,
                    date = date,
                    accountId = accountId,
                    categoryId = categoryId,
                    subCategoryId = subCategoryId,
                    note = note,
                    paidAt = currentTwr.transaction.paidAt, // Préserver la date de paiement existante
                    seriesId = null, // Débranchée
                    seriesDate = null,
                    isException = false,
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveTransaction(singleTx, tagIds)
            } else {
                // Simple mise à jour ou création de transaction ponctuelle
                val tx = TransactionEntity(
                    id = editingId ?: 0L,
                    title = title,
                    amount = amount,
                    type = type,
                    status = currentTwr?.transaction?.status ?: TransactionStatus.PLANNED,
                    date = date,
                    accountId = accountId,
                    categoryId = categoryId,
                    subCategoryId = subCategoryId,
                    note = note,
                    paidAt = currentTwr?.transaction?.paidAt, // Préserver la date de paiement existante
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveTransaction(tx, tagIds)
            }
        } else {
            // --- CAS 2 : VERS SÉRIE ---
            if (currentSeriesId != null) {
                // Mise à jour d'une série existante
                val series = RecurringSeriesEntity(
                    id = currentSeriesId,
                    title = title,
                    amount = amount,
                    type = type,
                    categoryId = categoryId,
                    subCategoryId = subCategoryId,
                    accountId = accountId,
                    frequency = frequency,
                    interval = interval,
                    startDate = date, // Repart de la date éditée
                    endDate = endDate,
                    maxOccurrences = maxOccurrences,
                    daysOfWeek = daysOfWeek,
                    isCancelled = false,
                    note = note,
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveRecurringSeries(series)
                
                // On met à jour l'exception si on en éditait une
                if (editingId != null) {
                    val tx = TransactionEntity(
                        id = editingId,
                        title = title,
                        amount = amount,
                        type = type,
                        status = currentTwr.transaction.status,
                        date = date,
                        accountId = accountId,
                        categoryId = categoryId,
                        subCategoryId = subCategoryId,
                        note = note,
                        seriesId = currentSeriesId.toString(),
                        seriesDate = date,
                        isException = true,
                        linkedGoalId = linkedGoalId,
                        linkedDebtId = linkedDebtId
                    )
                    saveTransaction(tx, tagIds)
                }
            } else {
                // Conversion ponctuel -> série
                // 1. Supprimer l'ancienne transaction isolée
                editingId?.let { hardDeleteTransaction(it) }
                
                // 2. Créer la nouvelle série
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
                saveRecurringSeries(series)
            }
        }
    }

    suspend fun updateEntireSeries(seriesId: Long, updatedSeries: RecurringSeriesEntity) {
        recurringSeriesDao.update(updatedSeries.copy(id = seriesId))
    }

    suspend fun updateSeriesFrom(seriesId: Long, fromDate: Long, updatedSeries: RecurringSeriesEntity) {
        // 1. Tronquer l'ancienne série (arrête à la veille de fromDate)
        cancelSeries(seriesId.toString(), SeriesDeletionMode.FUTURE, fromDate)
        
        // 2. Sauvegarder la nouvelle série (ID = 0 pour auto-générer)
        recurringSeriesDao.upsert(updatedSeries.copy(id = 0, startDate = fromDate))
    }

    suspend fun saveRecurringSeries(series: RecurringSeriesEntity): Long {
        return recurringSeriesDao.upsert(series)
    }

    suspend fun getSeriesById(id: Long) = recurringSeriesDao.getSeriesById(id)

    suspend fun getTransactionById(id: Long) = transactionDao.getById(id)

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

    /**
     * Bascule le statut d'une transaction entre PAID et PLANNED.
     * Matérialise l'occurrence si elle est virtuelle.
     */
    suspend fun toggleTransactionStatus(twr: TransactionWithRelations) {
        val tx = twr.transaction
        val realId = if (tx.id < 0L && tx.seriesId != null && tx.seriesDate != null) {
            materializeOccurrence(tx.seriesId.toLong(), tx.seriesDate)
        } else {
            tx.id
        }

        if (realId >= 0L) {
            val newStatus = if (tx.status == TransactionStatus.PAID) TransactionStatus.PLANNED else TransactionStatus.PAID
            setStatus(realId, newStatus.name)
        }
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

    suspend fun getDefaultExpenseCategoryId(): Long {
        // Cherche "Alimentation" ou "Autre" ou la première dépense
        val all = categoryDao.observeAll().first()
        return all.find { it.name.contains("Alimentation", ignoreCase = true) }?.id
            ?: all.find { it.type == TransactionType.EXPENSE }?.id
            ?: 1L
    }
}

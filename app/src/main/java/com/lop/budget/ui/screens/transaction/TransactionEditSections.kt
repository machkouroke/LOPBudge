package com.lop.budget.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringArrayResource
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lop.budget.BuildConfig
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.usecase.financial.LinkableItem
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopSwitch
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.surfaceRelief
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

/**
 * LOP-227, P-3 : en-tête hors carte, à la manière de Silo — bascule Dépense/Revenu, puis le montant
 * en grand. Un montant manquant grise Enregistrer, sans message (P-6).
 */
@Composable
fun AmountHeader(
    form: TransactionForm,
    currency: String,
    onSetType: (TransactionType) -> Unit,
    onSetAmount: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val track = RoundedCornerShape(50)
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .surfaceRelief(track)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, track)
                .padding(4.dp),
        ) {
            TypeSegment(
                label = stringResource(R.string.tx_type_expense),
                selected = form.type == TransactionType.EXPENSE,
                tag = TestTags.TX_EDIT_TYPE_EXPENSE,
                color = LopTheme.extended.expense,
                onColor = LopTheme.extended.onExpense,
                modifier = Modifier.weight(1f),
            ) { onSetType(TransactionType.EXPENSE) }
            TypeSegment(
                label = stringResource(R.string.tx_type_income),
                selected = form.type == TransactionType.INCOME,
                tag = TestTags.TX_EDIT_TYPE_INCOME,
                color = LopTheme.extended.income,
                onColor = LopTheme.extended.onIncome,
                modifier = Modifier.weight(1f),
            ) { onSetType(TransactionType.INCOME) }
        }
        AmountField(amountInput = form.amountInput, currency = currency, onSetAmount = onSetAmount)
    }
}

/**
 * Montant mis en avant. Le libellé, devise comprise, est dans la décoration du champ : le lecteur
 * d'écran l'annonce avec lui.
 */
@Composable
private fun AmountField(
    amountInput: String,
    currency: String,
    onSetAmount: (String) -> Unit,
) {
    // Centré dans toute la largeur : un champ d'une ligne occupe toute la place qu'on lui donne, un
    // symbole posé à côté partirait au bord. La devise passe donc dans le libellé.
    val style = MaterialTheme.typography.displayMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    BasicTextField(
        value = amountInput,
        onValueChange = onSetAmount,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.TX_EDIT_FIELD_AMOUNT),
        textStyle = style,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // CA-01 de LOP-2 : devise de l'application, jamais de symbole en dur.
                RowLabel(stringResource(R.string.tx_amount_in_currency, Format.currencySymbol(currency)))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (amountInput.isEmpty()) {
                        Text("0", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f))
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * LOP-227, P-7 : sous le montant, les catégories suggérées, puis « ⋯ Plus », toujours visible, qui
 * ouvre la liste complète. La catégorie choisie porte `TX_EDIT_FIELD_CATEGORY` et ne rend que son nom.
 */
@Composable
fun CategorySuggestions(
    suggestions: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.tx_category_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Rangée non paresseuse : cinq choix au plus, tous composés même hors de l'écran, pour
            // que le choix courant reste trouvable par son identifiant.
            val scroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fadingEdges(scroll)
                    .horizontalScroll(scroll)
                    .testTag(TestTags.TX_EDIT_CATEGORY_SUGGESTIONS)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { category ->
                    val selected = category.id == selectedId
                    CategoryChip(
                        category = category,
                        selected = selected,
                        onClick = { onSelect(category.id) },
                        modifier = if (selected) Modifier.testTag(TestTags.TX_EDIT_FIELD_CATEGORY) else Modifier,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MoreChip(onMore)
        }
    }
}

/**
 * Pastille de catégorie, plate comme dans Silo : une ombre serait coupée par le défilement. Choisie :
 * remplie de sa couleur, bordée d'elle et en gras — la sélection ne se lit pas à la seule teinte
 * (DESIGN.md § 9). Le texte reste `onSurface` : une couleur de catégorie claire serait illisible.
 */
@Composable
private fun CategoryChip(
    category: CategoryEntity,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = Color(category.colorArgb)
    val shape = RoundedCornerShape(50)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier = modifier
            .background(if (selected) color.copy(alpha = 0.18f).compositeOver(base) else base, shape)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) color else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIcon(
            icon = IconMapper.get(category.icon),
            tint = color,
            background = color.copy(alpha = 0.15f),
            size = 28.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = category.name,
            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Estompe les bords d'une rangée qui défile, du côté où il reste à voir : une pastille coupée s'efface
 * au lieu de s'arrêter net contre « Plus » (LOP-227, P-8). La lecture de [scroll] a lieu au dessin :
 * le fondu suit le défilement sans recomposer.
 */
private fun Modifier.fadingEdges(scroll: ScrollState, width: Dp = 32.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx().coerceAtMost(size.width / 2)
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = w),
                size = Size(w, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black, 1f to Color.Transparent, startX = size.width - w, endX = size.width,
                ),
                topLeft = Offset(size.width - w, 0f),
                size = Size(w, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** « ⋯ Plus » : ouvre le sélecteur complet des catégories. */
@Composable
private fun MoreChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .clickable(
                onClickLabel = stringResource(R.string.tx_category_more_action),
                role = Role.Button,
                onClick = onClick,
            )
            .testTag(TestTags.TX_EDIT_CATEGORY_MORE)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.MoreHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.tx_category_more),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** CA-02 de LOP-5 : nom de l'élément rattaché, suivi de « (archivé) » s'il l'est. */
@Composable
internal fun linkLabel(item: LinkableItem): String =
    if (item.summary.status == ItemStatus.ARCHIVED) stringResource(R.string.tx_link_archived, item.summary.name)
    else item.summary.name

/** Sans lien : [none] ; lien vers un élément qui n'existe plus : « Élément indisponible » (CA-05). */
@Composable
private fun linkValue(id: Long?, item: LinkableItem?, @StringRes none: Int): String = when {
    id == null -> stringResource(none)
    item == null -> stringResource(R.string.tx_link_unavailable)
    else -> linkLabel(item)
}

/**
 * LOP-227, P-3 : carte « saisie », une ligne par champ — nom, compte, date, note, étiquettes. La
 * catégorie se choisit au-dessus, dans [CategorySuggestions] (P-7). Une ligne qui ouvre un sélecteur porte son `testTag` et rend `[libellé, valeur]`,
 * dans cet ordre : les parcours instrumentés lisent la valeur en dernier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryRowsCard(
    form: TransactionForm,
    accounts: List<AccountEntity>,
    tags: List<TagEntity>,
    accountError: String?,
    onSetTitle: (String) -> Unit,
    onSetNote: (String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDate: () -> Unit,
    onOpenTags: () -> Unit,
) {
    FloatingCard(contentPadding = RowsCardPadding) {
        Column {
            RowTextField(
                label = stringResource(R.string.tx_name_label),
                value = form.title,
                onValueChange = onSetTitle,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_TITLE),
            )
            RowDivider()
            val account = accounts.find { it.id == form.accountId }
            FormRow(
                label = stringResource(R.string.tx_account_label),
                onClick = onOpenAccount,
                errorMessage = accountError,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_ACCOUNT),
            ) {
                RowValue(
                    text = account?.name ?: stringResource(R.string.tx_select_account),
                    icon = account?.let { IconMapper.get(it.icon) },
                    iconTint = account?.let { Color(it.colorArgb) },
                    muted = account == null,
                )
            }
            RowDivider()
            FormRow(
                label = stringResource(R.string.tx_date_label),
                onClick = onOpenDate,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_DATE),
            ) {
                RowValue(Format.fullDate(form.date))
            }
            RowDivider()
            RowTextField(
                label = stringResource(R.string.tx_notes_label),
                value = form.note,
                onValueChange = onSetNote,
                singleLine = false,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_NOTE),
            )
            RowDivider()
            val selectedTags = tags.filter { it.id in form.tagIds }
            FormRow(
                label = stringResource(R.string.tx_tags_label),
                onClick = onOpenTags,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_TAGS),
            ) {
                if (selectedTags.isEmpty()) {
                    RowValue(stringResource(R.string.tx_no_tags), muted = true)
                } else {
                    // Passe à la ligne plutôt que de défiler : le texte agrandi reste lisible (CA-06).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        selectedTags.forEach { PillTag(text = it.name, color = Color(it.colorArgb)) }
                    }
                }
            }
        }
    }
}

/**
 * LOP-227, P-3 : carte « options ».
 *
 * CA-01 de LOP-5 : objectif et prêt sont proposés quel que soit le type. Une contribution d'objectif
 * ou le remboursement d'une créance est un revenu (P-3 de LOP-5). CA-02 : le lien est relu par
 * identifiant, archivé compris ; il n'est plus cherché parmi les nouveaux choix.
 */
@Composable
fun OptionsCard(
    form: TransactionForm,
    /** I-6 de LOP-2 : masquée dès qu'une récurrence est choisie ; en édition, selon la portée. */
    isPaidToggleVisible: Boolean,
    linkedGoal: LinkableItem?,
    linkedLoan: LinkableItem?,
    goalError: String?,
    loanError: String?,
    onSetStatus: (TransactionStatus) -> Unit,
    onOpenGoal: () -> Unit,
    onOpenDebt: () -> Unit,
    /** Lignes de récurrence ([RecurrenceRows]) ; `null` quand la portée les masque (CA-08 de LOP-52). */
    recurrence: (@Composable () -> Unit)?,
    /** CA-01 de LOP-244 : dépense récurrente seulement, hors édition d'une seule occurrence. */
    isSubscriptionToggleVisible: Boolean = false,
    onSetSubscription: (Boolean) -> Unit = {},
) {
    FloatingCard(contentPadding = RowsCardPadding) {
        Column {
            if (recurrence != null) {
                recurrence()
                RowDivider()
            }
            if (isSubscriptionToggleVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(end = 8.dp)) {
                        RowLabel(stringResource(R.string.tx_subscription_label))
                    }
                    LopSwitch(
                        checked = form.isSubscription,
                        onCheckedChange = onSetSubscription,
                        modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_SUBSCRIPTION),
                    )
                }
                RowDivider()
            }
            if (isPaidToggleVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(end = 8.dp)) {
                        RowLabel(stringResource(R.string.tx_detail_mark_as_paid))
                    }
                    LopSwitch(
                        checked = form.status == TransactionStatus.PAID,
                        onCheckedChange = { isChecked ->
                            onSetStatus(if (isChecked) TransactionStatus.PAID else TransactionStatus.PLANNED)
                        },
                        modifier = Modifier.testTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID),
                    )
                }
                RowDivider()
            }
            FormRow(
                label = stringResource(R.string.tx_linked_goal_label),
                onClick = onOpenGoal,
                errorMessage = goalError,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_GOAL),
            ) {
                RowValue(
                    text = linkValue(form.linkedGoalId, linkedGoal, R.string.tx_no_goal_linked),
                    icon = linkedGoal?.let { IconMapper.get(it.summary.icon) },
                    iconTint = linkedGoal?.let { Color(it.summary.colorArgb) },
                    muted = form.linkedGoalId == null,
                )
            }
            RowDivider()
            FormRow(
                label = stringResource(R.string.tx_linked_debt_label),
                onClick = onOpenDebt,
                errorMessage = loanError,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_DEBT),
            ) {
                RowValue(
                    text = linkValue(form.linkedLoanId, linkedLoan, R.string.tx_no_debt_linked),
                    icon = linkedLoan?.let { IconMapper.get(it.summary.icon) },
                    iconTint = linkedLoan?.let { Color(it.summary.colorArgb) },
                    muted = form.linkedLoanId == null,
                )
            }
        }
    }
}

private val RowsCardPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)

/** Au-delà de cette échelle de police, libellé et valeur s'empilent au lieu de se serrer (CA-06). */
private const val STACKED_FONT_SCALE = 1.3f

/**
 * Ligne de formulaire à la manière de Silo (LOP-227) : libellé à gauche dans une colonne fixe,
 * valeur à droite. En texte agrandi, le libellé passe au-dessus de la valeur.
 */
@Composable
private fun LabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (LocalDensity.current.fontScale > STACKED_FONT_SCALE) {
            Column(Modifier.weight(1f)) {
                RowLabel(label)
                value()
            }
        } else {
            Box(Modifier.weight(0.34f).padding(end = 8.dp)) { RowLabel(label) }
            Box(Modifier.weight(0.66f)) { value() }
        }
        // Place réservée même sans chevron : les valeurs de toutes les lignes restent alignées.
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { trailing?.invoke() }
    }
}

/**
 * Ligne qui ouvre un sélecteur. [modifier] porte le `testTag` sur le nœud cliquable ; l'erreur est
 * rendue **hors** de ce nœud, pour que la ligne reste `[libellé, valeur]`.
 */
@Composable
private fun FormRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    value: @Composable () -> Unit,
) {
    Column {
        LabeledRow(
            label = label,
            modifier = modifier.clickable(onClick = onClick),
            trailing = {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            value = value,
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun RowLabel(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * LOP-227, P-8 : trois niveaux de texte dans une ligne, à la manière de Silo. La valeur saisie est
 * foncée et en graisse moyenne ; le libellé est gris ; l'attente (« Facultatif », « Aucun prêt lié »)
 * est plus claire encore.
 */
@Composable
private fun rowValueStyle() = MaterialTheme.typography.bodyLarge.copy(
    fontWeight = FontWeight.Medium,
    color = MaterialTheme.colorScheme.onSurface,
)

/**
 * Texte d'attente : `onSurfaceVariant` à 85 %, soit 4,83:1 sur la carte en clair et 6,03:1 en sombre.
 * Plus clair, il passerait sous le seuil de 4,5:1 de DESIGN.md § 9.
 */
@Composable
private fun rowPlaceholderStyle() = MaterialTheme.typography.bodyLarge.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
)

/** Valeur d'une ligne ; [muted] pour l'absence de choix (« Aucun objectif lié »). */
@Composable
private fun RowValue(text: String, icon: Any? = null, iconTint: Color? = null, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            val tint = iconTint ?: MaterialTheme.colorScheme.primary
            CircleIcon(icon = icon, tint = tint, background = tint.copy(alpha = 0.15f), size = 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = if (muted) rowPlaceholderStyle() else rowValueStyle())
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/**
 * Ligne de saisie libre. Le libellé est dans la décoration du champ : toucher la ligne entière donne
 * le focus, et le lecteur d'écran annonce le libellé avec le champ.
 */
@Composable
private fun RowTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = rowValueStyle(),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            LabeledRow(label) {
                Box {
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.tx_field_optional), style = rowPlaceholderStyle())
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * LOP-227, P-3 : la récurrence en lignes de la carte « options ». Les réglages avancés (intervalle,
 * jours, fin) se déplient sous la ligne Répéter, comme avant la recomposition.
 */
@Composable
fun RecurrenceRows(
    form: TransactionForm,
    onSetInterval: (Int) -> Unit,
    onToggleDow: (Int) -> Unit,
    onSetEndDate: (Long?) -> Unit,
    onSetMaxOccurrences: (Int?) -> Unit,
    onOpenFrequency: () -> Unit,
    onOpenEndDate: () -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Column(Modifier.testTag(TestTags.TX_EDIT_BLOCK_RECURRENCE)) {
        val frequencyLabel = when (form.frequency) {
            RecurrenceFrequency.NONE -> stringResource(R.string.tx_repeat_none)
            RecurrenceFrequency.DAILY -> stringResource(R.string.tx_repeat_daily)
            RecurrenceFrequency.WEEKLY -> stringResource(R.string.tx_repeat_weekly)
            RecurrenceFrequency.MONTHLY -> stringResource(R.string.tx_repeat_monthly)
            RecurrenceFrequency.YEARLY -> stringResource(R.string.tx_repeat_yearly)
        }
        FormRow(
            label = stringResource(R.string.tx_repeat_label),
            onClick = onOpenFrequency,
            modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_FREQUENCY),
        ) {
            RowValue(frequencyLabel, muted = form.frequency == RecurrenceFrequency.NONE)
        }

        if (form.frequency != RecurrenceFrequency.NONE) {
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag(TestTags.TX_EDIT_BTN_ADVANCED),
            ) {
                Text(if (showAdvanced) "Moins" else stringResource(R.string.tx_repeat_advanced))
                Icon(
                    if (showAdvanced) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    null
                )
            }
        }

        AnimatedVisibility(visible = showAdvanced && form.frequency != RecurrenceFrequency.NONE) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                // Interval
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tx_repeat_every))
                    Spacer(Modifier.width(8.dp))
                    NumberField(
                        value = form.interval,
                        onValue = { it?.takeIf { v -> v > 0 }?.let(onSetInterval) },
                        modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_INTERVAL)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tx_repeat_intervals))
                }

                // Days of week for weekly
                if (form.frequency == RecurrenceFrequency.WEEKLY) {
                    Text(
                        stringResource(R.string.tx_repeat_days_of_week),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val days = stringArrayResource(R.array.tx_days_short)
                        (1..7).forEach { day ->
                            val selected = form.daysOfWeek.contains(day)
                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { onToggleDow(day) },
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = days.getOrElse(day - 1) { "" },
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // End condition
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                Text(
                    stringResource(R.string.tx_repeat_ends),
                    style = MaterialTheme.typography.labelLarge
                )

                RadioRow(
                    selected = form.endDate == null && form.maxOccurrences == null,
                    onClick = {
                        onSetEndDate(null)
                        onSetMaxOccurrences(null)
                    }
                ) {
                    Text(stringResource(R.string.tx_repeat_ends_never))
                }

                RadioRow(
                    selected = form.endDate != null,
                    onClick = onOpenEndDate
                ) {
                    Text(stringResource(R.string.tx_repeat_ends_on))
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onOpenEndDate,
                        modifier = Modifier.testTag("tx_edit_recurrence_end_date_btn")
                    ) {
                        Text(Format.fullDate(form.endDate ?: form.date))
                    }
                }

                RadioRow(
                    selected = form.maxOccurrences != null,
                    onClick = { onSetMaxOccurrences(12) }
                ) {
                    Text(stringResource(R.string.tx_repeat_ends_after))
                    Spacer(Modifier.width(8.dp))
                    NumberField(
                        value = form.maxOccurrences,
                        onValue = onSetMaxOccurrences
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tx_repeat_occurrences_label))
                }
            }
        }
    }
}

/**
 * Segment de la bascule de type. Choisi : rempli de la couleur sémantique du type — la sélection
 * se lit au remplissage, pas à la seule teinte du texte (DESIGN.md § 9).
 */
@Composable
private fun TypeSegment(
    label: String,
    selected: Boolean,
    tag: String,
    color: Color,
    onColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // Repère de débogage lu par les parcours instrumentés (TC_35, TC_81, TC_167, TC_177).
    val text = if (selected && BuildConfig.DEBUG) "$label ✅" else label
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NumberField(
    value: Int?,
    onValue: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { 
            text = it
            onValue(it.toIntOrNull())
        },
        modifier = modifier
            .width(80.dp)
            .height(50.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        content()
    }
}

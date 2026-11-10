package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lop.budget.R
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.theme.lopBackgroundBrush
import com.lop.budget.ui.theme.lopRimBrush
import com.lop.budget.ui.theme.lopSheenBrush
import com.lop.budget.util.IconMapper
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Le DatePicker de Material 3 désigne un jour par son minuit **UTC**. L'app, elle, stocke le
 * minuit **local** du jour choisi. Les deux conversions ci-dessous passent par la date calendaire,
 * jamais par un décalage : un décalage pris à une date et appliqué à une autre perd un jour dès que
 * le changement d'heure les sépare (ANO du 5 octobre 2026 — choisir le 31 octobre depuis le
 * 5 octobre enregistrait le 30).
 */
private fun Long.toPickerUtcDay(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalStartOfDay(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * DatePicker qui rend le jour vu par l'utilisateur, au minuit local (voir [toPickerUtcDay]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopDatePicker(
    initialDateMillis: Long?,
    onDateSelected: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis?.toPickerUtcDay()
    )

    // `semantics` avant `testTag` : le sélecteur de date est monté dans sa propre fenêtre de
    // dialogue, qui n'hérite pas du `testTagsAsResourceId` de l'activité. Sans ce drapeau, son
    // identifiant de test reste invisible à l'automatisation.
    @OptIn(ExperimentalComposeUiApi::class)
    DatePickerDialog(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag(TestTags.PICKER_DATE),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDateSelected(dateState.selectedDateMillis?.toLocalStartOfDay())
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) {
        DatePicker(state = dateState)
    }
}

/**
 * Version plage du [LopDatePicker], mêmes conversions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopDateRangePicker(
    initialStartMillis: Long?,
    initialEndMillis: Long?,
    onRangeSelected: (Long?, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMillis?.toPickerUtcDay(),
        initialSelectedEndDateMillis = initialEndMillis?.toPickerUtcDay()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onRangeSelected(
                    datePickerState.selectedStartDateMillis?.toLocalStartOfDay(),
                    datePickerState.selectedEndDateMillis?.toLocalStartOfDay()
                )
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) {
        DateRangePicker(
            state = datePickerState,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Switch stylisé aux couleurs de LOPBudge.
 */
@Composable
fun LopSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        )
    )
}

/**
 * Un scaffold réutilisable pour les écrans de second niveau (Settings, Edit, etc.).
 * Propose un header OneUI-ish avec un gradient et un divider subtil au scroll.
 */
/**
 * Hauteur occupée par la barre de navigation flottante de `LopNavHost` au-dessus de la barre système :
 * pilule de 64 dp et marge de 12 dp (`FloatingBottomBar`), plus 20 dp sous le tout (`LopNavHost`).
 */
private val FloatingNavBarClearance = 64.dp + 12.dp + 20.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopScreenScaffold(
    title: String,
    /**
     * `null` : section principale de la barre du bas, sans flèche de retour (P-9 de LOP-22). La liste
     * réserve alors en bas la place de la barre flottante, que sa fin ne passe jamais dessous (LOP-223).
     */
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector = Icons.Default.Close,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /**
     * Bloc fixé sous le titre, qui ne défile pas avec le contenu : les filtres d'une analyse
     * restent visibles pendant la lecture de la liste (LOP-40, P-6). Vide par défaut. Il reçoit la
     * source du flou de l'écran : ses contrôles peuvent être en verre ([glass]) et laisser voir la
     * liste qui défile derrière eux.
     */
    header: @Composable ColumnScope.(HazeState) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val haze = rememberHazeState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Écran prolongé sous la barre d'état, transparente : le dégradé part du haut de la fenêtre et
    // continue celui de `LopNavHost` (LOP-229, CA-01). Opaque, il garde les transitions glissées
    // sans superposition.
    val background = lopBackgroundBrush()
    Scaffold(
        modifier = modifier.underStatusBar(statusBar).fillMaxSize().background(background),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = snackbarHost,
        topBar = {
            // Barre de titre sans fond, façon Liquid Glass : la liste défile derrière la barre
            // d'état, le titre et l'en-tête (bord flouté posé sur la liste, plus bas).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBar)
            ) {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            if (onBack != null) {
                                IconButton(
                                    onClick = onBack,
                                    modifier = Modifier.testTag(TestTags.BTN_BACK)
                                ) {
                                    Icon(navigationIcon, contentDescription = stringResource(R.string.back))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent
                        ),
                        windowInsets = WindowInsets(0.dp)
                    )
                    header(haze)
                }
                // Plus de trait sous la barre au défilement : le bord flouté fait la limite, sans
                // coupure nette.
            }
        },
        // La barre du bas se pose au-dessus des barres système, jamais dessous : sans ça le
        // bouton qu'elle porte chevauche la barre de navigation.
        bottomBar = { Box(Modifier.navigationBarsPadding()) { bottomBar() } }
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            // Source du flou de la barre de titre et des contrôles en verre, ciel compris.
            modifier = Modifier.fillMaxSize().hazeSource(haze).background(background),
            // Les marges du Scaffold sont portées par le contenu, pas par la liste : celle-ci
            // occupe tout l'écran et **défile sous** les barres, qui restent donc transparentes.
            // Posées sur le `Modifier`, elles découpaient une bande opaque autour de la barre du
            // bas, dans laquelle rien ne pouvait défiler.
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 20.dp,
                end = padding.calculateEndPadding(layoutDirection) + 20.dp,
                top = padding.calculateTopPadding() + 20.dp,
                bottom = padding.calculateBottomPadding() + 20.dp + if (onBack == null) FloatingNavBarClearance else 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
        // Bord flouté sous la barre d'état, le titre et l'en-tête : plein jusque sous le titre, qui
        // reste lisible, puis de moins en moins à travers l'en-tête et la marge haute de la liste.
        // Au repos, rien n'est dessous : il ne se voit pas.
        Box(
            Modifier
                .fillMaxWidth()
                .height(padding.calculateTopPadding() + 20.dp)
                .scrollEdge(haze, solid = statusBar + TopBarHeight)
        )
    }
}

/** Hauteur de `CenterAlignedTopAppBar` (Material 3). */
private val TopBarHeight = 64.dp

/**
 * Card flottante pour les sections d'écran (LOP-229, CA-04, P-6) : surface opaque avec un reflet
 * discret en haut, liseré éclairé par le haut, ombre diffuse (P-11).
 *
 * Posée dans une autre carte, elle devient une **tuile** (P-14) : ni ombre ni contour, fond teinté de
 * [tint] (couleur de son compte ou de sa catégorie), ou à défaut de [color], ou l'aplat du thème.
 */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    /** Teinte du contour, posée à 50 % ; non précisée, liseré éclairé du thème (P-6). */
    color: Color = Color.Unspecified,
    cornerRadius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    /** Teinte du fond quand la carte est une tuile, posée dans une autre carte (P-14). */
    tint: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val colors = MaterialTheme.colorScheme
    if (LocalInsideCard.current) {
        val tileTint = if (tint.isSpecified) tint else color
        val fill = if (tileTint.isSpecified) {
            // Opaque : la teinte est composée sur la carte parente, rien ne transparaît dessous.
            val alpha = if (colors.background.luminance() < 0.5f) 0.16f else 0.08f
            tileTint.copy(alpha = alpha).compositeOver(colors.surfaceContainerLow)
        } else {
            colors.surfaceVariant
        }
        Surface(modifier = modifier, color = fill, shape = shape) {
            Box(Modifier.padding(contentPadding)) { content() }
        }
        return
    }
    Surface(
        modifier = modifier.softShadow(shape),
        // Rôle « carte » (P-11) : blanc en clair ; `surfaceVariant` reste l'aplat posé dessus.
        color = colors.surfaceContainerLow,
        border = if (color.isSpecified) BorderStroke(1.dp, color.copy(alpha = 0.5f)) else BorderStroke(1.dp, lopRimBrush()),
        shape = shape,
    ) {
        CompositionLocalProvider(LocalInsideCard provides true) {
            Box(Modifier.background(lopSheenBrush()).padding(contentPadding)) { content() }
        }
    }
}

/** Vrai dans le contenu d'une [FloatingCard] : une carte posée là devient une tuile (P-14). */
private val LocalInsideCard = staticCompositionLocalOf { false }

/**
 * Relief des surfaces qui ne passent pas par une [Surface] Material — feuilles, boutons d'en-tête
 * (LOP-229, CA-04, P-3, P-6) : ombre douce et liseré éclairé par le haut. À chaîner avant le fond ;
 * le liseré est dessiné par-dessus.
 */
@Composable
fun Modifier.surfaceRelief(shape: Shape): Modifier =
    softShadow(shape).border(BorderStroke(1.dp, lopRimBrush()), shape)

/**
 * Verre des contrôles flottants, façon Liquid Glass (DESIGN.md § 6) : le contenu de [haze] se voit
 * flouté sous une teinte légère, cerné d'un liseré. Sans ombre : sous du translucide, elle ferait
 * une tache (§ 5.3). Le liseré du thème est transparent en clair ; le verre y prend le contour des
 * séparateurs. Remplace `surfaceRelief` + fond, et découpe à [shape].
 */
@Composable
fun Modifier.glass(haze: HazeState, shape: Shape): Modifier {
    val colors = MaterialTheme.colorScheme
    val rim = if (colors.background.luminance() < 0.5f) lopRimBrush() else SolidColor(colors.outlineVariant)
    return clip(shape)
        .hazeEffect(haze) {
            blurRadius = 24.dp
            noiseFactor = 0f
            backgroundColor = colors.background
            tints = listOf(HazeTint(colors.surfaceContainerHigh.copy(alpha = 0.6f)))
        }
        .border(BorderStroke(1.dp, rim), shape)
}

/**
 * Bord de défilement façon Liquid Glass (DESIGN.md § 4) : le contenu de [haze] qui passe ici est
 * flouté pleinement sur les [solid] du haut, puis de moins en moins jusqu'au bas, sans coupure nette.
 * Flou progressif, et non fondu : un fondu laissait transparaître le texte net sous le titre.
 * Sans rien dessous, le ciel flouté reste le ciel : le bord ne se voit pas.
 */
@Composable
fun Modifier.scrollEdge(haze: HazeState, solid: Dp = 0.dp): Modifier {
    val background = MaterialTheme.colorScheme.background
    val solidPx = with(LocalDensity.current) { solid.toPx() }
    return hazeEffect(haze) {
        blurRadius = 24.dp
        noiseFactor = 0f
        backgroundColor = background
        progressive = HazeProgressive.verticalGradient(startY = solidPx, startIntensity = 1f, endIntensity = 0f)
    }
}

/**
 * Prolonge l'élément vers le haut sous la barre d'état, transparente : le NavHost s'arrête sous
 * elle. Sa place dans la mise en page ne change pas.
 */
fun Modifier.underStatusBar(height: Dp): Modifier = layout { measurable, constraints ->
    val extra = height.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = extra))
    layout(placeable.width, placeable.height - extra) { placeable.place(0, -extra) }
}

/**
 * Ombre diffuse des surfaces (LOP-229, CA-04, P-9) : large et légère plutôt que courte et sèche.
 * L'alpha des couleurs multiplie celui de la plateforme (ombre portée ≈ 19 %) : 25 % en donne
 * ≈ 5 %, sur un flou de 16 dp, comme Silo (P-11). En sombre, le liseré fait l'essentiel du relief.
 */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 16.dp): Modifier =
    shadow(elevation, shape, clip = false, spotColor = Color.Black.copy(alpha = 0.25f))

/** Pastille ronde colorée contenant une icône de catégorie/compte. */
@Composable
fun CircleIcon(
    icon: Any, // ImageVector or String (URL)
    tint: Color,
    background: Color,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (icon is String && icon.startsWith("http")) Color.White else background)
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            is ImageVector -> {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(size * 0.5f)
                )
            }

            is String -> {
                if (icon.startsWith("http")) {
                    AsyncImage(
                        model = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(size * 0.85f)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        onError = { state ->
                            android.util.Log.e("LOPBudge", "❌ CircleIcon: Failed to load $icon. Error: ${state.result.throwable.message}")
                        },
                        error = androidx.compose.ui.graphics.painter.ColorPainter(Color.Gray.copy(alpha = 0.1f)),
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color.LightGray.copy(alpha = 0.05f))
                    )
                } else {
                    val vector = IconMapper.get(icon)
                    if (vector is ImageVector) {
                        Icon(
                            vector,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(size * 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/** Petit chip capsule (catégorie, tag, filtre). */
@Composable
fun PillTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.18f), shape = CircleShape, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Champ de saisie stylisé pour les formulaires. */
@Composable
fun LopTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
    placeholder: String? = null,
    readOnly: Boolean = false,
    /** Message affiché sous le champ, qui passe alors en erreur. */
    errorMessage: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = textStyle,
            leadingIcon = leading,
            trailingIcon = trailing,
            minLines = minLines,
            readOnly = readOnly,
            isError = errorMessage != null,
            supportingText = errorMessage?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
        )
    }
}

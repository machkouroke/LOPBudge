package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lop.budget.R
import com.lop.budget.util.IconMapper

/**
 * Un scaffold réutilisable pour les écrans de second niveau (Settings, Edit, etc.).
 * Propose un header OneUI-ish avec un gradient et un divider subtil au scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector = Icons.Default.Close,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val showTopBarDivider by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                            )
                        )
                    )
            ) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(navigationIcon, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets(0.dp)
                )

                if (showTopBarDivider) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        },
        bottomBar = bottomBar
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
    }
}

/** Card flottante pour les sections d'écran. */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    cornerRadius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(cornerRadius),
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** Pastille ronde colorée contenant une icône de catégorie/compte. */
@Composable
fun CircleIcon(
    icon: Any, // ImageVector or String (URL)
    tint: Color,
    background: Color,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (icon is String && icon.startsWith("http")) Color.White else background),
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

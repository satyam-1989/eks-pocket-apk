@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Theme ----------

val Mono = FontFamily.Monospace
val TerminalBg = Color(0xFF08111C)
val TerminalFg = Color(0xFFD5E0EC)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2B33D), onPrimary = Color(0xFF2A1B00),
    primaryContainer = Color(0xFF4A3608), onPrimaryContainer = Color(0xFFFFDEA6),
    secondary = Color(0xFF5CC8B8), onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF1E3B44), onSecondaryContainer = Color(0xFFBDEEE6),
    background = Color(0xFF0F1B2A), onBackground = Color(0xFFE4EAF2),
    surface = Color(0xFF0F1B2A), onSurface = Color(0xFFE4EAF2),
    surfaceVariant = Color(0xFF1C2C44), onSurfaceVariant = Color(0xFF9AA9BC),
    surfaceContainerLowest = Color(0xFF0A1420), surfaceContainerLow = Color(0xFF132034),
    surfaceContainer = Color(0xFF16243A), surfaceContainerHigh = Color(0xFF1C2C44),
    surfaceContainerHighest = Color(0xFF23354F),
    outline = Color(0xFF3C4E66), outlineVariant = Color(0xFF26364D),
    error = Color(0xFFFF7A70), onError = Color(0xFF3A0905),
    errorContainer = Color(0xFF4A1A17), onErrorContainer = Color(0xFFFFDAD5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8F5B00), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDEA6), onPrimaryContainer = Color(0xFF2A1B00),
    secondary = Color(0xFF006A5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6EEE7), onSecondaryContainer = Color(0xFF00201B),
    background = Color(0xFFEEF2F6), onBackground = Color(0xFF142235),
    surface = Color(0xFFEEF2F6), onSurface = Color(0xFF142235),
    surfaceVariant = Color(0xFFDDE4EC), onSurfaceVariant = Color(0xFF4E5D70),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFE8EDF2),
    surfaceContainer = Color(0xFFE2E8EF), surfaceContainerHigh = Color(0xFFDAE1EA),
    surfaceContainerHighest = Color(0xFFD2DAE4),
    outline = Color(0xFF7D8A9B), outlineVariant = Color(0xFFC3CCD7),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun EksPocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

object StatusColors {
    val ok = Color(0xFF3DBA8C)
    val warn = Color(0xFFE5A23A)
    val bad = Color(0xFFE5574E)
    val idle = Color(0xFF8796A8)
}

private val OK_STATES = setOf(
    "Running", "Active", "ACTIVE", "Ready", "Succeeded", "Completed", "Available", "Bound",
)
private val WARN_STATES = setOf(
    "Pending", "ContainerCreating", "PodInitializing", "Terminating", "CREATING", "UPDATING",
    "Progressing", "Cordoned",
)

fun statusColor(s: String): Color = when {
    s in OK_STATES -> StatusColors.ok
    s in WARN_STATES -> StatusColors.warn
    s.isBlank() || s == "Unknown" || s.startsWith("Scaled") -> StatusColors.idle
    else -> StatusColors.bad
}

// ---------- Async helpers ----------

class Loader<T>(private val scope: CoroutineScope, private val block: () -> T) {
    var data by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var generation = 0

    fun reload() {
        val gen = ++generation
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) { block() }
                if (gen == generation) data = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: e.javaClass.simpleName
            } finally {
                if (gen == generation) loading = false
            }
        }
    }
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val loader = remember(*keys) { Loader(scope, block) }
    LaunchedEffect(loader) { loader.reload() }
    return loader
}

/** Runs a mutating action off the main thread and reports the outcome in a snackbar. */
class Actions(private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    var busy by mutableStateOf(false)
        private set

    fun run(label: String, after: () -> Unit = {}, block: () -> String?) {
        scope.launch {
            busy = true
            val message = try {
                val result = withContext(Dispatchers.IO) { block() }
                after()
                result ?: "$label: done"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$label failed. ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
            snackbar.showSnackbar(message)
        }
    }
}

@Composable
fun rememberActions(snackbar: SnackbarHostState): Actions {
    val scope = rememberCoroutineScope()
    return remember(snackbar) { Actions(scope, snackbar) }
}

// ---------- Components ----------

/** Name of the active AWS profile, shown in every screen header. */
val LocalProfileName = compositionLocalOf<String?> { null }

@Composable
fun ProfileTag(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 140.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun AppScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val profile = LocalProfileName.current
                        if (subtitle != null || profile != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (profile != null) {
                                    ProfileTag(profile)
                                    if (subtitle != null) Spacer(Modifier.width(8.dp))
                                }
                                if (subtitle != null) {
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        floatingActionButton = fab,
        contentWindowInsets = WindowInsets.safeDrawing,
        content = content,
    )
}

@Composable
fun ErrorCard(message: String, onRetry: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Try again") }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatusPill(text: String) {
    val c = statusColor(text)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(c.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            color = c,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** One row in a resource list. [facts] render as separate quiet labels. */
@Composable
fun ResourceRow(
    title: String,
    facts: List<String>,
    status: String?,
    onClick: (() -> Unit)? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (status != null) StatusPill(status)
                    facts.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (menu != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        menu { open = false }
                    }
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
fun <T> ResourceList(
    loader: Loader<List<T>>,
    emptyText: String,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = loader.loading,
        onRefresh = { loader.reload() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val err = loader.error
            if (err != null) item { ErrorCard(err, onRetry = { loader.reload() }) }
            val data = loader.data
            if (data != null && data.isEmpty()) item { EmptyState(emptyText) }
            items(data.orEmpty(), key = key) { row(it) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ScaleDialog(name: String, current: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scale $name") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    value = ((value.toIntOrNull() ?: 0) - 1).coerceAtLeast(0).toString()
                }) { Text("−") }
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp).padding(horizontal = 8.dp),
                )
                OutlinedButton(onClick = {
                    value = ((value.toIntOrNull() ?: 0) + 1).toString()
                }) { Text("+") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.toIntOrNull() != null,
                onClick = { onConfirm(value.toInt()) },
            ) { Text("Scale") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SelectorButton(
    label: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label, fontFamily = Mono, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 440.dp),
        ) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, fontFamily = Mono) },
                    onClick = { onSelect(o); open = false },
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono)
    }
}

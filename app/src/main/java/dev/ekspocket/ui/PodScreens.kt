@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ekspocket.Nav
import dev.ekspocket.Screen
import dev.ekspocket.data.Session
import dev.ekspocket.k8s.TerminalBuffer
import dev.ekspocket.k8s.age
import dev.ekspocket.k8s.containerState
import dev.ekspocket.k8s.meta
import dev.ekspocket.k8s.objects
import dev.ekspocket.k8s.podSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

// ---------------- Pod detail ----------------

@Composable
fun PodDetailScreen(session: Session, ns: String, name: String, nav: Nav) {
    val k8s = session.k8s ?: return
    val path = "/api/v1/namespaces/$ns/pods/$name"
    val loader = rememberLoader(path) { k8s.getJson(path) }
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(snackbar)
    var confirmDelete by remember { mutableStateOf(false) }

    AppScaffold(
        title = name,
        subtitle = "Pod in $ns",
        onBack = { nav.pop() },
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { loader.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton(onClick = { nav.push(Screen.Yaml("pod/$name", path, ns)) }) {
                Icon(Icons.Filled.Edit, "Edit YAML")
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete pod") }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loader.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            loader.error?.let { e -> item { ErrorCard(e, onRetry = { loader.reload() }) } }
            val pod = loader.data
            if (pod != null) {
                val s = podSummary(pod)
                val status = pod.optJSONObject("status")
                val spec = pod.optJSONObject("spec")
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            StatusPill(s.phase)
                            Spacer(Modifier.height(10.dp))
                            InfoRow("Ready", s.ready)
                            InfoRow("Restarts", s.restarts.toString())
                            InfoRow("Node", spec?.optString("nodeName").orEmpty())
                            InfoRow("Pod IP", status?.optString("podIP").orEmpty())
                            InfoRow("Age", age(pod.meta("creationTimestamp")))
                            InfoRow("QoS class", status?.optString("qosClass").orEmpty())
                            InfoRow("Service account", spec?.optString("serviceAccountName").orEmpty())
                        }
                    }
                }
                val statuses = status?.optJSONArray("containerStatuses").objects()
                    .associateBy { it.optString("name") }
                item {
                    Text(
                        "Containers",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(spec?.optJSONArray("containers").objects(), key = { it.optString("name") }) { c ->
                    val cname = c.optString("name")
                    val cs = statuses[cname]
                    ContainerCard(
                        name = cname,
                        image = c.optString("image"),
                        state = containerState(cs),
                        ready = cs?.optBoolean("ready") == true,
                        running = cs?.optJSONObject("state")?.has("running") == true,
                        restarts = cs?.optInt("restartCount") ?: 0,
                        onLogs = { nav.push(Screen.Logs(ns, name, cname)) },
                        onShell = { nav.push(Screen.Exec(ns, name, cname)) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete pod?",
            text = "$name will be deleted. If a deployment owns it, a new pod will replace it.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                actions.run("Delete pod", after = { nav.pop() }) {
                    k8s.delete(path)
                    "Deleted $name"
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ContainerCard(
    name: String,
    image: String,
    state: String,
    ready: Boolean,
    running: Boolean,
    restarts: Int,
    onLogs: () -> Unit,
    onShell: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    fontFamily = Mono,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(if (ready) "Ready" else "NotReady")
            }
            Text(
                image,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Text(
                if (restarts > 0) "$state, $restarts restarts" else state,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilledTonalButton(onClick = onLogs) { Text("Logs") }
                FilledTonalButton(onClick = onShell, enabled = running) { Text("Open shell") }
            }
        }
    }
}

// ---------------- Logs ----------------

@Composable
fun LogsScreen(session: Session, ns: String, pod: String, container: String?, nav: Nav) {
    val k8s = session.k8s ?: return
    var follow by rememberSaveable { mutableStateOf(true) }
    var previous by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(true) }
    val lines = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var streaming by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(follow, previous) {
        lines.clear()
        error = null
        val queue = ConcurrentLinkedQueue<String>()
        val call = k8s.logCall(ns, pod, container, 1000, follow && !previous, previous)
        val reader = launch(Dispatchers.IO) {
            try {
                k8s.streamLines(call) { queue.add(it) }
            } catch (e: Exception) {
                if (!call.isCanceled()) error = e.message ?: e.javaClass.simpleName
            }
        }
        streaming = true
        try {
            while (true) {
                while (true) {
                    val l = queue.poll() ?: break
                    lines.add(l)
                }
                if (lines.size > 6000) {
                    val keep = lines.takeLast(5000)
                    lines.clear()
                    lines.addAll(keep)
                }
                if (!reader.isActive && queue.isEmpty()) break
                delay(250)
            }
        } finally {
            call.cancel()
            streaming = false
        }
    }

    LaunchedEffect(lines.size, pinned) {
        if (pinned && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    AppScaffold(
        title = pod,
        subtitle = if (container != null) "Logs for $container" else "Logs",
        onBack = { nav.pop() },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = follow && !previous,
                    onClick = { follow = !follow },
                    label = { Text(if (streaming && follow && !previous) "Following" else "Follow") },
                    enabled = !previous,
                )
                FilterChip(
                    selected = previous,
                    onClick = { previous = !previous },
                    label = { Text("Previous container") },
                )
                FilterChip(
                    selected = pinned,
                    onClick = { pinned = !pinned },
                    label = { Text("Stick to bottom") },
                )
            }
            error?.let { ErrorCard(it) }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                ) {
                    if (lines.isEmpty() && error == null) {
                        item {
                            Text(
                                if (streaming) "Waiting for log lines" else "No log lines",
                                color = TerminalFg.copy(alpha = 0.6f),
                                fontFamily = Mono,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    itemsIndexed(lines) { _, line ->
                        Text(line, color = TerminalFg, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

// ---------------- Exec ----------------

private fun frame(channel: Int, data: ByteArray): ByteString {
    val out = ByteArray(data.size + 1)
    out[0] = channel.toByte()
    System.arraycopy(data, 0, out, 1, data.size)
    return out.toByteString()
}

@Composable
fun ExecScreen(session: Session, ns: String, pod: String, container: String?, nav: Nav) {
    val k8s = session.k8s ?: return
    val term = remember { TerminalBuffer() }
    var input by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var socket by remember { mutableStateOf<WebSocket?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    val widthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    val cols = ((widthDp - 20) / (7.2f * fontScale)).toInt().coerceIn(20, 300)

    fun send(text: String) {
        socket?.send(frame(0, text.toByteArray(Charsets.UTF_8)))
    }

    DisposableEffect(attempt) {
        term.feed("[connecting to $pod${container?.let { "/$it" } ?: ""}]\r\n")
        val ws = k8s.exec(ns, pod, container, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                val size = JSONObject().put("Width", cols).put("Height", 40).toString()
                webSocket.send(frame(4, size.toByteArray(Charsets.UTF_8)))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size == 0) return
                val payload = bytes.substring(1).utf8()
                when (bytes[0].toInt()) {
                    1, 2 -> term.feed(payload)
                    3 -> {
                        val status = runCatching { JSONObject(payload) }.getOrNull()
                        if (status?.optString("status") != "Success") {
                            term.feed("\r\n[${status?.optString("message") ?: payload}]\r\n")
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                term.feed("\r\n[session ended]\r\n")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                val hint = when (response?.code) {
                    401 -> " The cluster rejected your credentials."
                    403 -> " Your Kubernetes permissions don't allow pods/exec."
                    null -> ""
                    else -> " HTTP ${response?.code}."
                }
                term.feed("\r\n[connection lost: ${t.message ?: t.javaClass.simpleName}.$hint]\r\n")
            }
        })
        socket = ws
        onDispose {
            ws.close(1000, null)
            socket = null
        }
    }

    AppScaffold(
        title = pod,
        subtitle = if (connected) "Shell in ${container ?: "container"}" else "Disconnected",
        onBack = { nav.pop() },
        actions = {
            if (!connected) TextButton(onClick = { attempt++ }) { Text("Reconnect") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val scroll = rememberScrollState()
            LaunchedEffect(scroll) {
                snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
                SelectionContainer {
                    Text(
                        term.text,
                        color = TerminalFg,
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(10.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val keys = listOf(
                    "Tab" to "\t", "Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004", "↑" to "\u001B[A",
                    "↓" to "\u001B[B", "Esc" to "\u001B", "Clear" to "\u000C",
                )
                keys.forEach { (label, seq) ->
                    AssistChip(
                        onClick = {
                            if (label == "Tab") {
                                send(input + seq)
                                input = ""
                            } else {
                                if (label == "Clear") term.clear()
                                send(seq)
                            }
                        },
                        label = { Text(label, fontFamily = Mono) },
                        enabled = connected,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Type a command") },
                    singleLine = true,
                    enabled = connected,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input + "\r"); input = "" }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { send(input + "\r"); input = "" }, enabled = connected) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run")
                }
            }
        }
    }
}

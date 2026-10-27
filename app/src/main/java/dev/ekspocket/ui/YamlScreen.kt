@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ekspocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ekspocket.Nav
import dev.ekspocket.data.Session
import dev.ekspocket.k8s.YamlCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun template(ns: String) = """
apiVersion: v1
kind: ConfigMap
metadata:
  name: example
  namespace: $ns
data:
  greeting: hello
""".trimStart()

@Composable
fun YamlScreen(session: Session, title: String, path: String?, namespace: String, nav: Nav) {
    val k8s = session.k8s ?: return
    val isNew = path == null
    var text by remember { mutableStateOf(if (isNew) template(namespace) else "") }
    var loaded by remember { mutableStateOf(isNew) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(snackbar)
    if (path?.contains("/secrets/") == true || text.contains("kind: Secret")) SecureWindow()

    LaunchedEffect(path, reloadKey) {
        if (path == null) return@LaunchedEffect
        try {
            text = withContext(Dispatchers.IO) {
                val obj = k8s.getJson(path)
                obj.optJSONObject("metadata")?.remove("managedFields")
                YamlCodec.dump(obj)
            }
            loaded = true
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
        }
    }

    AppScaffold(
        title = title,
        subtitle = if (isNew) "Creates or updates resources, default namespace $namespace" else "Saving replaces the whole object",
        onBack = { nav.pop() },
        snackbar = snackbar,
        actions = {
            TextButton(
                enabled = loaded && !actions.busy,
                onClick = {
                    val yaml = text
                    if (path == null) {
                        actions.run("Apply") { k8s.applyYaml(yaml, namespace).joinToString("\n") }
                    } else {
                        actions.run("Save", after = { reloadKey++ }) {
                            k8s.replace(path, yaml)
                            "Saved"
                        }
                    }
                },
            ) { Text(if (isNew) "Apply" else "Save") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            loadError?.let { ErrorCard(it, onRetry = { reloadKey++ }) }
            if (!loaded && loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = loaded,
                textStyle = TextStyle(
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(12.dp),
            )
        }
    }
}

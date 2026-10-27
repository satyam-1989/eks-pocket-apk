package dev.ekspocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.data.CredentialStore
import dev.ekspocket.data.Session
import dev.ekspocket.ui.ClusterHomeScreen
import dev.ekspocket.ui.ClustersScreen
import dev.ekspocket.ui.EksPocketTheme
import dev.ekspocket.ui.ExecScreen
import dev.ekspocket.ui.LogsScreen
import dev.ekspocket.ui.PodDetailScreen
import dev.ekspocket.ui.SetupScreen
import dev.ekspocket.ui.YamlScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = CredentialStore(applicationContext)
        setContent {
            EksPocketTheme {
                Surface(color = MaterialTheme.colorScheme.background) { App(store) }
            }
        }
    }
}

sealed interface Screen {
    data object Setup : Screen
    data object Clusters : Screen
    data class ClusterHome(val cluster: EksCluster) : Screen
    data class PodDetail(val ns: String, val name: String) : Screen
    data class Logs(val ns: String, val pod: String, val container: String?) : Screen
    data class Exec(val ns: String, val pod: String, val container: String?) : Screen
    /** [path] null means "apply new YAML"; otherwise edit the object at that API path. */
    data class Yaml(val title: String, val path: String?, val namespace: String) : Screen
}

class Nav(private val stack: SnapshotStateList<Screen>) {
    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replaceAll(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
}

@Composable
fun App(store: CredentialStore) {
    val session = remember { Session(store) }
    val stack = remember {
        mutableStateListOf<Screen>(if (session.config == null) Screen.Setup else Screen.Clusters)
    }
    val nav = remember { Nav(stack) }
    val holder = rememberSaveableStateHolder()

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    val screen = stack.last()
    holder.SaveableStateProvider(key = "${stack.lastIndex}:${screen.hashCode()}") {
        when (screen) {
            Screen.Setup -> SetupScreen(
                initial = session.config,
                canGoBack = stack.size > 1,
                onBack = { nav.pop() },
                onSave = {
                    session.save(it)
                    nav.replaceAll(Screen.Clusters)
                },
                onRemoveKeys = if (session.config != null) {
                    {
                        session.signOut()
                        nav.replaceAll(Screen.Setup)
                    }
                } else null,
            )

            Screen.Clusters -> ClustersScreen(
                session = session,
                onOpen = { nav.push(Screen.ClusterHome(it)) },
                onSettings = { nav.push(Screen.Setup) },
            )

            is Screen.ClusterHome -> ClusterHomeScreen(session, screen.cluster, nav)
            is Screen.PodDetail -> PodDetailScreen(session, screen.ns, screen.name, nav)
            is Screen.Logs -> LogsScreen(session, screen.ns, screen.pod, screen.container, nav)
            is Screen.Exec -> ExecScreen(session, screen.ns, screen.pod, screen.container, nav)
            is Screen.Yaml -> YamlScreen(session, screen.title, screen.path, screen.namespace, nav)
        }
    }
}

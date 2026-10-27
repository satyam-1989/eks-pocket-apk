package dev.ekspocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.data.CredentialStore
import dev.ekspocket.data.Session
import dev.ekspocket.ui.ClusterHomeScreen
import dev.ekspocket.ui.ClustersScreen
import dev.ekspocket.ui.EditProfileScreen
import dev.ekspocket.ui.EksPocketTheme
import dev.ekspocket.ui.ExecScreen
import dev.ekspocket.ui.LocalProfileName
import dev.ekspocket.ui.LogsScreen
import dev.ekspocket.ui.PodDetailScreen
import dev.ekspocket.ui.ProfilesScreen
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
    data object Profiles : Screen
    /** [id] null means "add a new profile". */
    data class EditProfile(val id: String?) : Screen
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
        mutableStateListOf<Screen>(
            if (session.state.profiles.isEmpty()) Screen.EditProfile(null) else Screen.Clusters
        )
    }
    val nav = remember { Nav(stack) }
    val holder = rememberSaveableStateHolder()

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    val screen = stack.last()
    CompositionLocalProvider(LocalProfileName provides session.active?.name) {
        holder.SaveableStateProvider(key = "${stack.lastIndex}:${screen.hashCode()}") {
            when (screen) {
                is Screen.EditProfile -> EditProfileScreen(
                    existing = session.profile(screen.id),
                    isFirst = session.state.profiles.isEmpty(),
                    canGoBack = stack.size > 1,
                    nameTaken = { session.nameTaken(it, screen.id) },
                    onBack = { nav.pop() },
                    onSave = {
                        session.upsert(it)
                        nav.replaceAll(Screen.Clusters)
                    },
                )

                Screen.Profiles -> ProfilesScreen(
                    session = session,
                    onBack = { nav.pop() },
                    onAdd = { nav.push(Screen.EditProfile(null)) },
                    onEdit = { nav.push(Screen.EditProfile(it.id)) },
                    onUse = {
                        session.switchTo(it.id)
                        nav.replaceAll(Screen.Clusters)
                    },
                    onDelete = {
                        session.delete(it.id)
                        if (session.state.profiles.isEmpty()) nav.replaceAll(Screen.EditProfile(null))
                    },
                )

                Screen.Clusters -> ClustersScreen(
                    session = session,
                    onOpen = { nav.push(Screen.ClusterHome(it)) },
                    onManageProfiles = { nav.push(Screen.Profiles) },
                )

                is Screen.ClusterHome -> ClusterHomeScreen(session, screen.cluster, nav)
                is Screen.PodDetail -> PodDetailScreen(session, screen.ns, screen.name, nav)
                is Screen.Logs -> LogsScreen(session, screen.ns, screen.pod, screen.container, nav)
                is Screen.Exec -> ExecScreen(session, screen.ns, screen.pod, screen.container, nav)
                is Screen.Yaml -> YamlScreen(session, screen.title, screen.path, screen.namespace, nav)
            }
        }
    }
}

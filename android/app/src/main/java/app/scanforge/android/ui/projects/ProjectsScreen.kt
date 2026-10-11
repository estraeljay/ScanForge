package app.scanforge.android.ui.projects

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.scanforge.android.app
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.ConfirmDialog
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.geometry.LengthUnit
import app.scanforge.project.ProjectDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ProjectRow(val doc: ProjectDocument, val bytes: Long, val thumbnail: File?)

enum class SortBy { DATE, NAME, SIZE }

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.app.store
    val rows = MutableStateFlow<List<ProjectRow>>(emptyList())
    val showArchived = MutableStateFlow(false)

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        rows.value = store.list(includeArchived = showArchived.value).map { d ->
            ProjectRow(d, store.sizeOnDisk(d.id), File(store.dir(d.id), "previews/thumbnail.png").takeIf { it.isFile })
        }
    }

    private fun act(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) { runCatching(block); refresh() }
    fun rename(id: String, name: String) = act { store.rename(id, name) }
    fun duplicate(id: String) = act { store.duplicate(id) }
    fun archive(id: String, archived: Boolean) = act { store.setArchived(id, archived) }
    fun delete(id: String) = act { store.delete(id) }
    fun toggleArchived() { showArchived.value = !showArchived.value; refresh() }
}

@Composable
fun ProjectsScreen(nav: NavController, vm: ProjectsViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.refresh() }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val archived by vm.showArchived.collectAsStateWithLifecycle()
    val unit = LocalContext.current.app.settings.value.unit
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortBy.DATE) }
    var grid by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<ProjectDocument?>(null) }
    var deleting by remember { mutableStateOf<ProjectDocument?>(null) }
    val shown = rows.filter { r -> query.isBlank() || r.doc.name.contains(query, true) || r.doc.tags.any { it.contains(query, true) } || r.doc.category.name.contains(query, true) }
        .let { list -> when (sort) { SortBy.DATE -> list.sortedByDescending { it.doc.updatedAt }; SortBy.NAME -> list.sortedBy { it.doc.name.lowercase() }; SortBy.SIZE -> list.sortedByDescending { it.bytes } } }

    Column(Modifier.fillMaxSize().screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, if (grid) "Show as list" else "Show as grid") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search name, tag or category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortBy.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            FilterChip(selected = archived, onClick = vm::toggleArchived, label = { Text("Archived") })
        }
        if (shown.isEmpty()) Text(if (rows.isEmpty()) "No projects yet. Start a new scan." else "No projects match.", color = SfColors.TextSecondary)
        LazyVerticalGrid(columns = if (grid) GridCells.Adaptive(170.dp) else GridCells.Fixed(1), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.doc.id }) { row ->
                ProjectCard(row, grid, unit, onOpen = { nav.navigate(Routes.project(row.doc.id)) },
                    onRename = { renaming = row.doc }, onDuplicate = { vm.duplicate(row.doc.id) },
                    onArchive = { vm.archive(row.doc.id, !row.doc.archived) }, onDelete = { deleting = row.doc })
            }
        }
    }
    renaming?.let { doc ->
        var name by remember(doc.id) { mutableStateOf(doc.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(doc.id, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { doc ->
        ConfirmDialog("Delete \"${doc.name}\"?", "The scan, its captures, measurements and exports on this device will be permanently deleted. Files already sent to Blender are not affected.",
            "Delete", onConfirm = { vm.delete(doc.id) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun ProjectCard(row: ProjectRow, grid: Boolean, unit: LengthUnit, onOpen: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val d = row.doc
    val v = d.model.current
    val dims = v?.let { (0 until 3).map { i -> (it.boundsMax[i] - it.boundsMin[i]) * d.scale.factor } }
    Surface(color = SfColors.Panel, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        val content: @Composable () -> Unit = {
            Thumb(row.thumbnail, if (grid) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(72.dp))
            Column(Modifier.padding(10.dp).then(if (grid) Modifier else Modifier.fillMaxWidth(0.8f))) {
                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.status.name.lowercase()} · ${d.category.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                dims?.let { Text(it.joinToString(" × ") { x -> unit.format(x, 0).removeSuffix(" ${unit.symbol}") } + " ${unit.symbol}", style = MaterialTheme.typography.bodySmall) }
                Text("${d.updatedAt.take(10)} · ${humanBytes(row.bytes)}", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                if (d.tags.isNotEmpty()) Text(d.tags.joinToString { "#$it" }, style = MaterialTheme.typography.bodySmall, color = SfColors.Accent)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${d.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open / export") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(if (d.archived) "Unarchive" else "Archive") }, onClick = { menu = false; onArchive() })
                    DropdownMenuItem(text = { Text("Delete…", color = SfColors.Bad) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (grid) Column { content() } else Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun Thumb(file: File?, modifier: Modifier) {
    val bmp = remember(file?.path, file?.lastModified()) { file?.let { BitmapFactory.decodeFile(it.path) } }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Model preview", contentScale = ContentScale.Crop, modifier = modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Filled.ViewInAr, "No model yet", tint = SfColors.TextSecondary) }
}

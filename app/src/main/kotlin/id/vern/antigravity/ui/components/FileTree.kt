package id.vern.antigravity.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.vern.antigravity.R
import id.vern.antigravity.data.ProjectFile
import id.vern.antigravity.ui.theme.AntigravityAmber
import id.vern.antigravity.ui.theme.AntigravityBlue
import id.vern.antigravity.ui.theme.AntigravityGreen
import id.vern.antigravity.ui.theme.AntigravityRed
import java.io.File

@Composable
fun FileTree(
    rootDirectory: File,
    onFileSelected: (File) -> Unit,
    modifier: Modifier = Modifier,
    showHiddenFiles: Boolean = false,
    gitStatusForPath: ((File) -> String?)? = null
) {
    var expandedDirs by remember { mutableStateOf(setOf<String>()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var targetParentDir by remember { mutableStateOf(rootDirectory) }
    var newItemName by remember { mutableStateOf("") }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember(rootDirectory) { mutableStateOf("") }
    var hiddenFilesVisible by remember(rootDirectory) { mutableStateOf(showHiddenFiles) }
    val context = LocalContext.current

    LaunchedEffect(showHiddenFiles) { hiddenFilesVisible = showHiddenFiles }

    val query = searchQuery.trim()

    fun directoryHasMatch(dir: File, depth: Int): Boolean {
        if (depth >= 8) return false
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return false
        return children.any { child ->
            (hiddenFilesVisible || !child.name.startsWith(".")) &&
                (child.name.contains(query, ignoreCase = true) ||
                    (child.isDirectory && directoryHasMatch(child, depth + 1)))
        }
    }

    fun isVisible(file: File): Boolean {
        if (!hiddenFilesVisible && file.name.startsWith(".")) return false
        if (query.isEmpty()) return true
        return file.name.contains(query, ignoreCase = true) ||
            (file.isDirectory && directoryHasMatch(file, 0))
    }

    fun loadFiles(dir: File): List<ProjectFile> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val list = dir.listFiles() ?: return emptyList()
        return list.filter { isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file ->
                val isDir = file.isDirectory
                val path = file.absolutePath
                val isExp = expandedDirs.contains(path) || query.isNotEmpty()
                ProjectFile(
                    name = file.name,
                    file = file,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else file.length(),
                    lastModified = file.lastModified(),
                    children = if (isDir && isExp) loadFiles(file) else emptyList(),
                    isExpanded = isExp
                )
            }
    }

    val fileItems = remember(rootDirectory, expandedDirs, refreshTrigger, query, hiddenFilesVisible) {
        loadFiles(rootDirectory)
    }

    fun copyPath(file: File) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Path", file.absolutePath))
                Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    Column(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rootDirectory.name.ifEmpty { stringResource(R.string.file_manager_title) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row {
                        IconButton(
                            onClick = {
                                targetParentDir = rootDirectory
                                newItemName = ""
                                showNewFileDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                                contentDescription = stringResource(R.string.file_manager_new_file),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                targetParentDir = rootDirectory
                                newItemName = ""
                                showNewFolderDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = stringResource(R.string.file_manager_new_folder),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { hiddenFilesVisible = !hiddenFilesVisible },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (hiddenFilesVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (hiddenFilesVisible) "Hide hidden files" else stringResource(R.string.file_show_hidden),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { refreshTrigger++ },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.file_manager_refresh),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.file_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        if (fileItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (query.isNotEmpty()) "No matching files" else stringResource(R.string.file_manager_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(fileItems, key = { it.absolutePath }) { projectFile ->
                    FileTreeItem(
                        item = projectFile,
                        depth = 0,
                        onToggleExpand = { path ->
                            expandedDirs = if (expandedDirs.contains(path)) {
                                expandedDirs - path
                            } else {
                                expandedDirs + path
                            }
                        },
                        onFileClick = onFileSelected,
                        onDeleteClick = { fileToDelete = it.file },
                        onRenameClick = { file ->
                            fileToRename = file.file
                            renameText = file.name
                            renameError = null
                        },
                        onCopyPathClick = { copyPath(it) },
                        gitStatusForPath = gitStatusForPath
                    )
                }
            }
        }
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(0.92f).wrapContentHeight(),
            title = {
                DialogBlurEffect()
                Text(stringResource(R.string.file_manager_new_file))
            },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    label = { Text(stringResource(R.string.file_manager_file_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        if (newItemName.isNotBlank()) {
                            val newF = File(targetParentDir, newItemName.trim())
                            newF.createNewFile()
                            showNewFileDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text(stringResource(R.string.btn_create)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(8.dp), onClick = { showNewFileDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(0.92f).wrapContentHeight(),
            title = {
                DialogBlurEffect()
                Text(stringResource(R.string.file_manager_new_folder))
            },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    label = { Text(stringResource(R.string.file_manager_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        if (newItemName.isNotBlank()) {
                            val newD = File(targetParentDir, newItemName.trim())
                            newD.mkdirs()
                            showNewFolderDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text(stringResource(R.string.btn_create)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(8.dp), onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    fileToRename?.let { file ->
        AlertDialog(
            onDismissRequest = {
                fileToRename = null
                renameError = null
            },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(0.92f).wrapContentHeight(),
            title = {
                DialogBlurEffect()
                Text(stringResource(R.string.file_rename_title))
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = {
                            renameText = it
                            renameError = null
                        },
                        label = { Text("New name") },
                        singleLine = true,
                        isError = renameError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    renameError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        val name = renameText.trim()
                        when {
                            name.isBlank() -> renameError = "Name cannot be empty"
                            name.contains('/') || name.contains('\\') -> renameError = "Invalid name"
                            else -> {
                                val parent = file.parentFile ?: rootDirectory
                                val target = File(parent, name)
                                if (target.exists() && target.absolutePath != file.absolutePath) {
                                    renameError = "A file with that name already exists"
                                } else if (file.renameTo(target)) {
                                    fileToRename = null
                                    renameError = null
                                    refreshTrigger++
                                } else {
                                    renameError = "Rename failed"
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.editor_btn_save)) }
            },
            dismissButton = {
                TextButton(
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        fileToRename = null
                        renameError = null
                    }
                ) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(0.92f).wrapContentHeight(),
            title = {
                DialogBlurEffect()
                Text(stringResource(R.string.file_manager_delete_title))
            },
            text = { Text(stringResource(R.string.file_manager_delete_confirm, file.name)) },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        file.deleteRecursively()
                        fileToDelete = null
                        refreshTrigger++
                    }
                ) { Text(stringResource(R.string.file_manager_delete)) }
            },
            dismissButton = {
                TextButton(shape = RoundedCornerShape(8.dp), onClick = { fileToDelete = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

@Composable
private fun FileTreeItem(
    item: ProjectFile,
    depth: Int,
    onToggleExpand: (String) -> Unit,
    onFileClick: (File) -> Unit,
    onDeleteClick: (ProjectFile) -> Unit,
    onRenameClick: (ProjectFile) -> Unit,
    onCopyPathClick: (File) -> Unit,
    gitStatusForPath: ((File) -> String?)? = null
) {
    val paddingStart = (depth * 16 + 8).dp
    val gitStatus = gitStatusForPath?.invoke(item.file)?.trim()?.take(1)?.uppercase()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                if (item.isDirectory) {
                    onToggleExpand(item.absolutePath)
                } else {
                    onFileClick(item.file)
                }
            }
            .padding(start = paddingStart, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.isDirectory) {
            Icon(
                imageVector = if (item.isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (item.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = AntigravityAmber
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
            Icon(
                imageVector = getFileIcon(item.extension),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = getFileColor(item.extension)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (!gitStatus.isNullOrEmpty()) {
            Text(
                text = gitStatus,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = when (gitStatus) {
                    "A" -> AntigravityGreen
                    "D", "!" -> AntigravityRed
                    "?" -> AntigravityBlue
                    else -> AntigravityAmber
                },
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        if (!item.isDirectory && item.sizeBytes > 0) {
            Text(
                text = item.formattedSize,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }

        IconButton(
            onClick = { onRenameClick(item) },
            modifier = Modifier.size(22.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.file_rename_action),
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        IconButton(
            onClick = { onCopyPathClick(item.file) },
            modifier = Modifier.size(22.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.file_copy_path),
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        IconButton(
            onClick = { onDeleteClick(item) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.file_manager_delete),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }

    if (item.isDirectory && item.isExpanded) {
        item.children.forEach { child ->
            FileTreeItem(
                item = child,
                depth = depth + 1,
                onToggleExpand = onToggleExpand,
                onFileClick = onFileClick,
                onDeleteClick = onDeleteClick,
                onRenameClick = onRenameClick,
                onCopyPathClick = onCopyPathClick,
                gitStatusForPath = gitStatusForPath
            )
        }
    }
}

private fun getFileIcon(ext: String): ImageVector {
    return when (ext) {
        "kt", "java", "py", "js", "ts", "cpp", "c", "h", "rs", "go" -> Icons.Default.Code
        "md", "txt", "log", "rst" -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun getFileColor(ext: String): Color {
    return when (ext) {
        "kt" -> Color(0xFF7F52FF)
        "java" -> Color(0xFFE76F00)
        "py" -> Color(0xFF3572A5)
        "js", "ts" -> Color(0xFFF7DF1E)
        "sh", "bash" -> AntigravityGreen
        "json", "xml", "yaml", "yml" -> AntigravityAmber
        "md" -> AntigravityBlue
        else -> Color(0xFF94A3B8)
    }
}

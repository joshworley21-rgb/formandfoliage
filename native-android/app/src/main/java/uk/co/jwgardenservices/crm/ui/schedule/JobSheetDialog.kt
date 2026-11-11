package uk.co.jwgardenservices.crm.ui.schedule

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.jwgardenservices.crm.data.model.JobPhotoWithUrl
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.ContactButtons

@Composable
fun JobSheetDialog(
    job: ScheduledJob,
    clientName: String,
    clientPhone: String? = null,
    clientAddress: String? = null,
    viewModel: CrmViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskFlow = remember(job.id) { viewModel.observeJobTasks(job.id) }
    val tasks by taskFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var newTask by remember(job.id) { mutableStateOf("") }
    var photos by remember(job.id) { mutableStateOf<List<JobPhotoWithUrl>>(emptyList()) }
    var photosLoading by remember(job.id) { mutableStateOf(true) }
    var photoError by remember(job.id) { mutableStateOf<String?>(null) }
    var uploading by remember(job.id) { mutableStateOf(false) }
    var editingPhoto by remember(job.id) { mutableStateOf<JobPhotoWithUrl?>(null) }
    var captionText by remember(job.id) { mutableStateOf("") }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(job.id) {
        runCatching { viewModel.refreshJobTasks(job.id) }
        photosLoading = true
        try {
            photos = viewModel.jobPhotos(job.id)
            photoError = null
        } catch (error: Exception) {
            photoError = error.message ?: "Could not load photos"
        } finally {
            photosLoading = false
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            photoError = null
            try {
                uris.forEach { uri ->
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { ImageCompression.compress(context, uri) }.getOrNull()
                    } ?: run {
                        photoError = "Could not read that photo. Try a different one."
                        return@forEach
                    }
                    viewModel.uploadJobPhoto(job.id, bytes, "image/jpeg", "jpg")
                }
                photos = viewModel.jobPhotos(job.id)
                toast(if (uris.size == 1) "Photo added" else "Photos added")
            } catch (error: Exception) {
                photoError = error.message ?: "Upload failed"
            } finally {
                uploading = false
            }
        }
    }

    val done = tasks.count { it.done }

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 800.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Job sheet — $clientName", style = MaterialTheme.typography.headlineSmall)
                if (!clientAddress.isNullOrBlank()) {
                    Text(
                        text = clientAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ContactButtons(phone = clientPhone, address = clientAddress)
                Text(
                    text = "Tick off tasks and attach photos of completed work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Tasks",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$done/${tasks.size} done",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = task.done,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    try {
                                        viewModel.setJobTaskDone(task, checked)
                                    } catch (error: Exception) {
                                        toast(error.message ?: "Could not update the task")
                                    }
                                }
                            },
                        )
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (task.done) TextDecoration.LineThrough else null,
                            color = if (task.done) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        viewModel.deleteJobTask(task.id)
                                    } catch (error: Exception) {
                                        toast(error.message ?: "Could not remove the task")
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Remove task",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (tasks.isEmpty()) {
                    Text(
                        text = "No tasks yet — add the work planned for this visit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTask,
                        onValueChange = { newTask = it },
                        placeholder = { Text("e.g. Mow front lawn") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    viewModel.addJobTask(job.id, newTask, tasks.size)
                                    newTask = ""
                                } catch (error: Exception) {
                                    toast(error.message ?: "Could not add the task")
                                }
                            }
                        },
                        enabled = newTask.isNotBlank(),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Photos",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { picker.launch("image/*") },
                        enabled = !uploading,
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (uploading) "Uploading…" else "Add photos")
                    }
                }

                photoError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when {
                    photosLoading -> Text(
                        text = "Loading photos…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    photos.isEmpty() -> Text(
                        text = "No photos yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        photos.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { item ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.secondary)
                                            .clickable {
                                                editingPhoto = item
                                                captionText = item.photo.caption.orEmpty()
                                            },
                                    ) {
                                        if (item.url != null) {
                                            AsyncImage(
                                                model = item.url,
                                                contentDescription = item.photo.caption
                                                    ?: "Completed work photo",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        if (!item.photo.caption.isNullOrBlank()) {
                                            Text(
                                                text = item.photo.caption.orEmpty(),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .fillMaxWidth()
                                                    .background(
                                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        viewModel.deleteJobPhoto(
                                                            item.photo.id,
                                                            item.photo.storagePath,
                                                        )
                                                        photos = photos.filter {
                                                            it.photo.id != item.photo.id
                                                        }
                                                    } catch (error: Exception) {
                                                        toast(error.message ?: "Could not remove the photo")
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                    CircleShape,
                                                )
                                                .size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = "Delete photo",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                repeat(3 - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }

    editingPhoto?.let { item ->
        AlertDialog(
            onDismissRequest = { editingPhoto = null },
            title = { Text("Photo caption") },
            text = {
                OutlinedTextField(
                    value = captionText,
                    onValueChange = { captionText = it },
                    label = { Text("Caption (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                viewModel.updateJobPhotoCaption(item.photo.id, captionText)
                                photos = photos.map { photo ->
                                    if (photo.photo.id == item.photo.id) {
                                        photo.copy(
                                            photo = photo.photo.copy(
                                                caption = captionText.trim().ifBlank { null },
                                            ),
                                        )
                                    } else {
                                        photo
                                    }
                                }
                                editingPhoto = null
                            } catch (error: Exception) {
                                toast(error.message ?: "Could not save the caption")
                            }
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPhoto = null }) { Text("Cancel") }
            },
        )
    }
}

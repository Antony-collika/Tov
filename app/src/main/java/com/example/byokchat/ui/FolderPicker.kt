package com.example.byokchat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.byokchat.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the system folder picker and adds the selected folder to the AI playground. */
@Composable
fun rememberFolderPicker(
    onPicked: () -> Unit = {},
    onError: ((String) -> Unit)? = null,
): () -> Unit {
    val context = LocalContext.current
    val fs = context.container.fs
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching { fs.onFolderPicked(uri) }
                withContext(Dispatchers.Main) {
                    result.onSuccess { onPicked() }
                        .onFailure { error ->
                            val message = error.message ?: "Could not add this folder."
                            if (onError != null) onError(message)
                            else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
    }
    return { launcher.launch(null) }
}

/** Picker for the independent Session storage folder. */
@Composable
fun rememberSessionFolderPicker(onPicked: () -> Unit = {}): () -> Unit {
    val fs = LocalContext.current.container.fs
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { fs.onSessionFolderPicked(uri) }
                onPicked()
            }
        }
    }
    return { launcher.launch(null) }
}

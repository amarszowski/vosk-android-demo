package org.vosk.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import org.json.JSONException
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import org.vosk.demo.ui.theme.VoskTheme
import java.io.IOException
import java.util.*

class VoskActivity : ComponentActivity(), RecognitionListener {
    private var currentModel: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null

    private val availableModelNames = mutableStateListOf<String>()
    private var recognizedText by mutableStateOf("")
    private var partialResult by mutableStateOf("")
    private var uiState by mutableStateOf(UiState.START)
    private var selectedModelName by mutableStateOf("en-us")
    private var errorMessage by mutableStateOf<String?>(null)
    private var isDropdownExpanded by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LibVosk.setLogLevel(LogLevel.INFO)

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                errorMessage = "TTS initialization failed"
            }
        }

        setContent {
            VoskTheme {
                MainScreen()
            }
        }

        // Check permissions and initialize
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSIONS_REQUEST_RECORD_AUDIO
            )
        } else {
            initModels()
        }
    }

    @OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val audioPermissionState = rememberPermissionState(
            Manifest.permission.RECORD_AUDIO
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = stringResource(R.string.app_logo),
                        modifier = Modifier
                            .size(230.dp, 86.dp)
                            .padding(bottom = 16.dp)
                    )

                    // Language selector
                    Text(
                        text = stringResource(R.string.select_model),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = isDropdownExpanded,
                        onExpandedChange = { isDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedModelName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            enabled = uiState != UiState.START && uiState != UiState.MIC
                        )

                        ExposedDropdownMenu(
                            expanded = isDropdownExpanded,
                            onDismissRequest = { isDropdownExpanded = false }
                        ) {
                            availableModelNames.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        selectedModelName = model
                                        isDropdownExpanded = false
                                        loadAndSwitchModel(model)
                                    }
                                )
                            }
                        }
                    }

                    // Microphone button
                    OutlinedButton(
                        onClick = { recognizeMicrophone() },
                        enabled = uiState != UiState.START,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_mic_24),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = when (uiState) {
                                UiState.MIC -> stringResource(R.string.stop_microphone)
                                else -> stringResource(R.string.listen_microphone)
                            }
                        )
                    }

                    // Pause button
                    OutlinedButton(
                        onClick = {
                            isPaused = !isPaused
                            pause(isPaused)
                        },
                        enabled = uiState == UiState.MIC,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isPaused) R.drawable.baseline_play_arrow_24
                                else R.drawable.baseline_pause_24
                            ),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = if (isPaused) stringResource(R.string.continueRecognition)
                            else stringResource(R.string.pause)
                        )
                    }

                    // Speak button
                    OutlinedButton(
                        onClick = { speakRecognizedText() },
                        enabled = uiState == UiState.DONE && recognizedText.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_volume_up_24),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(R.string.speak_recognized_text))
                    }

                    // Results text
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Text(
                            text = errorMessage ?: when (uiState) {
                                UiState.START -> stringResource(R.string.preparing)
                                UiState.READY -> stringResource(R.string.ready)
                                UiState.MIC -> partialResult.ifEmpty { stringResource(R.string.say_something) }
                                UiState.DONE -> recognizedText.ifEmpty { stringResource(R.string.ready) }
                            },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }

    private fun initModels() {
        try {
            val modelDirs = assets.list("") // List all assets
            if (modelDirs != null) {
                for (dir in modelDirs) {
                    if (dir.startsWith("model-")) {
                        val modelName = dir.replace("model-", "")
                        availableModelNames.add(modelName)
                    }
                }
                if (availableModelNames.isNotEmpty()) {
                    selectedModelName = availableModelNames[0]
                    loadAndSwitchModel(selectedModelName)
                }
            }
        } catch (e: IOException) {
            errorMessage = "Error retrieving model directories: ${e.message}"
        }
    }

    private fun loadAndSwitchModel(modelName: String) {
        uiState = UiState.START
        errorMessage = null

        // Unload the previous model if it exists
        currentModel?.close()

        // Load the new model from assets
        val modelDir = "model-$modelName"
        StorageService.unpack(
            this,
            modelDir,
            modelName,
            { model: Model? ->
                currentModel = model
                // Set TTS locale based on the loaded model
                val ttsLocale = getTtsLocaleForModel(modelName)
                if (tts?.isLanguageAvailable(ttsLocale) == TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = ttsLocale
                } else {
                    errorMessage = "TTS language not supported for $modelName"
                }
                uiState = UiState.READY
            },
            { exception: IOException ->
                errorMessage = "Failed to load model $modelName: ${exception.message}"
            }
        )
    }

    private fun recognizeMicrophone() {
        if (speechService != null) {
            uiState = UiState.DONE
            speechService?.stop()
            speechService = null
        } else {
            uiState = UiState.MIC
            try {
                currentModel?.let { model ->
                    val rec = Recognizer(model, 16000.0f)
                    speechService = SpeechService(rec, 16000.0f)
                    speechService?.startListening(this)
                } ?: run {
                    errorMessage = "No model loaded!"
                }
            } catch (e: IOException) {
                errorMessage = e.message
            }
        }
    }

    private fun pause(checked: Boolean) {
        speechService?.setPause(checked)
    }

    private fun speakRecognizedText() {
        if (!recognizedText.isBlank()) {
            tts?.speak(recognizedText, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onResult(hypothesis: String) {
        try {
            val jsonResult = JSONObject(hypothesis)
            val text = jsonResult.optString("text", "")
            if (text.isNotEmpty()) {
                recognizedText = text
                partialResult = ""
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onPartialResult(hypothesis: String) {
        try {
            val jsonPartialResult = JSONObject(hypothesis)
            val partial = jsonPartialResult.optString("partial", "")
            if (partial.isNotEmpty()) {
                partialResult = partial
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onFinalResult(hypothesis: String) {
        uiState = UiState.DONE
    }

    override fun onError(e: Exception) {
        errorMessage = e.message
    }

    override fun onTimeout() {
        uiState = UiState.DONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModels()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        tts?.run {
            stop()
            shutdown()
        }

        speechService?.run {
            stop()
            shutdown()
        }

        currentModel?.close()
    }

    private fun getTtsLocaleForModel(modelName: String): Locale = when (modelName) {
        "english" -> Locale.ENGLISH
        "hindi" -> Locale("hi")
        "kazakh" -> Locale("kk")
        "polish" -> Locale("pl")
        "russian" -> Locale("ru")
        "turkish" -> Locale("tr")
        "ukrainian" -> Locale("uk")
        "uzbek" -> Locale("uz")
        else -> Locale.ENGLISH
    }

    enum class UiState {
        START, READY, DONE, MIC
    }

    companion object {
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
    }
}
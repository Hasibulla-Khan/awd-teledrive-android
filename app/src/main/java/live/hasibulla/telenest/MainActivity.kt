package live.hasibulla.telenest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import live.hasibulla.telenest.data.repository.UpdateRepository
import live.hasibulla.telenest.data.repository.UpdateState
import live.hasibulla.telenest.data.secure.SecureSettings
import live.hasibulla.telenest.data.worker.TeleDriveWorkerManager
import live.hasibulla.telenest.ui.navigation.NavGraph
import live.hasibulla.telenest.ui.screens.security.SecurityViewModel
import live.hasibulla.telenest.ui.screens.settings.SettingsViewModel
import live.hasibulla.telenest.ui.theme.TeledriveTheme
import live.hasibulla.telenest.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var workerManager: TeleDriveWorkerManager
    
    @Inject
    lateinit var secureSettings: SecureSettings
    
    @Inject
    lateinit var updateRepository: UpdateRepository
    
    private val securityViewModel: SecurityViewModel by viewModels()
    private var lastPauseTime: Long = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissions.add(Manifest.permission.CAMERA)
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        
        // Trigger update check once
        lifecycleScope.launch {
            updateRepository.checkForUpdates(manual = false)
        }
        
        // Safety: Cancel all previous work to prevent crashes from old task versions
        try {
            androidx.work.WorkManager.getInstance(this).cancelAllWork()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val now = System.currentTimeMillis()
                    if (lastPauseTime != 0L && (now - lastPauseTime > 30_000)) {
                        securityViewModel.setLocked(true)
                    }
                } else if (event == Lifecycle.Event.ON_PAUSE) {
                    lastPauseTime = System.currentTimeMillis()
                }
            },
        )

        enableEdgeToEdge()
        @OptIn(ExperimentalMaterial3Api::class)
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            
            val isDarkModePref by themeViewModel.isDarkMode.collectAsState()
            val themeColor by themeViewModel.themeColor.collectAsState()
            val currentLanguage by settingsViewModel.currentLanguage.collectAsState()
            val updateState by updateRepository.updateState.collectAsState()
            
            val darkTheme = isDarkModePref ?: isSystemInDarkTheme()

            // The locale is handled by AppCompatDelegate.setApplicationLocales in SettingsViewModel.
            // We just need to make sure Compose is aware of the configuration changes.
            val configuration = LocalConfiguration.current
            
            CompositionLocalProvider(
                LocalConfiguration provides configuration
            ) {
                TeledriveTheme(darkTheme = darkTheme, primaryColor = themeColor) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                    
                    // Global Update Dialog
                    GlobalUpdateDialog(
                        updateState = updateState,
                        onDismiss = { updateRepository.resetState() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalUpdateDialog(
    updateState: UpdateState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(updateState) {
        when (updateState) {
            is UpdateState.UpToDate -> {
                android.widget.Toast.makeText(context, context.getString(R.string.up_to_date), android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            is UpdateState.Error -> {
                android.widget.Toast.makeText(context, context.getString(R.string.err_update_check, updateState.message), android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            else -> {}
        }
    }
    
    when (updateState) {
        is UpdateState.NewVersionAvailable -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_available, updateState.release.name)) },
                text = { 
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(updateState.release.body)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateState.release.html_url))
                        context.startActivity(intent)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.open_browser))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.later))
                    }
                }
            )
        }
        else -> {}
    }
}

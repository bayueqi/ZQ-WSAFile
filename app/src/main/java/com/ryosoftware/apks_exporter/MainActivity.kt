package com.ryosoftware.apks_exporter

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContent {
            AppTheme(this@MainActivity) {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("preferences") },
                            onNavigateToFiles = { openSystemFileManager() },
                            onNavigateToAppInfo = { openAndroidSettings(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS) },
                            onNavigateToSystemSettings = { openAndroidSettings(Settings.ACTION_SETTINGS) },
                            onNavigateToDeveloperOptions = { openDeveloperOptions() },
                            onNavigateToAppDetail = { packageName ->
                                navController.navigate("app_detail/$packageName")
                            }
                        )
                    }
                    composable("preferences") {
                        PreferencesScreen(
                            onNavigateBack = {
                                navController.popBackStack()
                                viewModel.loadData()
                            }
                        )
                    }
                    composable(
                        route = "app_detail/{packageName}",
                        arguments = listOf(navArgument("packageName") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                        AppDetailScreen(
                            packageName = packageName,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.isSelecting.value) finish()
                else viewModel.cancelSelection()
            }
        })
    }

    private fun openSystemFileManager() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(DOCUMENTS_UI, "$DOCUMENTS_UI.files.FilesActivity")
            setDataAndType(Uri.parse(PRIMARY_ROOT), DOCUMENT_ROOT_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            return
        } catch (e: Exception) {
        }

        val fallback = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(PRIMARY_ROOT), DOCUMENT_ROOT_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fallback.resolveActivity(packageManager) != null) {
            startActivity(fallback)
            return
        }
        Toast.makeText(this, R.string.file_manager_missing, Toast.LENGTH_SHORT).show()
    }

    private fun openDeveloperOptions() {
        val wsaSettings = Intent(WSA_SETTINGS_ACTION).apply {
            component = ComponentName(WSA_BRIDGE_PACKAGE, WSA_SETTINGS_HANDLER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSettings(wsaSettings)) return

        val developmentPage = Intent().apply {
            component = ComponentName(SETTINGS_PACKAGE, DEVELOPER_SETTINGS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSettings(developmentPage)) return

        openAndroidSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    private fun startSettings(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun openAndroidSettings(action: String) {
        val intent = Intent(action).apply {
            setPackage(SETTINGS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            return
        } catch (e: Exception) {
        }
        Toast.makeText(this, R.string.system_screen_missing, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadData()
        MainBackupWorker.onAppExecuted(this)
    }

    companion object {
        private const val DOCUMENTS_UI = "com.android.documentsui"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val WSA_BRIDGE_PACKAGE = "com.microsoft.windows.userapp"
        private const val WSA_SETTINGS_HANDLER = "com.microsoft.windows.intents.SubsystemIntentHandler"
        private const val WSA_SETTINGS_ACTION = "com.microsoft.windows.SUBSYSTEM_SETTINGS"
        private const val DEVELOPER_SETTINGS_ACTIVITY =
            "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"
        private const val PRIMARY_ROOT = "content://com.android.externalstorage.documents/root/primary"
        private const val DOCUMENT_ROOT_MIME = "vnd.android.document/root"
    }
}

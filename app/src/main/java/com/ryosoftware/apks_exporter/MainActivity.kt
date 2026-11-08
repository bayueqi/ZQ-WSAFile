package com.ryosoftware.apks_exporter

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
                            onNavigateToAppInfo = { openSystemScreen(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS) },
                            onNavigateToSystemSettings = { openSystemScreen(Settings.ACTION_SETTINGS) },
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
        packageManager.getLaunchIntentForPackage(DOCUMENTS_UI)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
            return
        }
        for (root in STORAGE_ROOTS) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(root), "vnd.android.document/root")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, R.string.file_manager_missing, Toast.LENGTH_SHORT).show()
    }

    private fun openSystemScreen(vararg actions: String) {
        for (action in actions) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                continue
            }
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
        private val STORAGE_ROOTS = arrayOf(
            "content://com.android.externalstorage.documents/root/primary",
            "content://com.android.externalstorage.documents/root/home"
        )
    }
}

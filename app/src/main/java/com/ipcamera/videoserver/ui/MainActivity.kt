package com.ipcamera.videoserver.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val current by navController.currentBackStackEntryAsState()
                            listOf(
                                "status" to "Status",
                                "settings" to "Settings",
                                "archive" to "Archive",
                            ).forEach { (route, label) ->
                                NavigationBarItem(
                                    selected = current?.destination?.route == route,
                                    onClick = {
                                        navController.navigate(route) { launchSingleTop = true }
                                    },
                                    icon = {},
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController,
                        startDestination = "status",
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable("status") { StatusScreen(vm) }
                        composable("settings") { SettingsScreen(vm) }
                        composable("archive") { ArchiveScreen(vm) }
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

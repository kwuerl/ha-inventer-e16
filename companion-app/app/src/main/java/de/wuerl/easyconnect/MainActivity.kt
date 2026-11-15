// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.wuerl.easyconnect.ui.SetupRoute
import de.wuerl.easyconnect.ui.SetupViewModel
import de.wuerl.easyconnect.ui.theme.EasyConnectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as EasyConnectApp).graph
        setContent {
            EasyConnectTheme {
                SetupRoute(viewModel(factory = viewModelFactory { initializer { SetupViewModel(graph) } }))
            }
        }
    }
}

package com.datapoint

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.datapoint.sdk.callbacks.DataPointCallback
import com.datapoint.sdk.callbacks.DataPointListener
import com.datapoint.sdk.DataPoint
import com.datapoint.sdk.callbacks.models.Environment
import com.datapoint.sdk.callbacks.InitCallback
import com.datapoint.ui.theme.DataPointTheme

class MainActivity : ComponentActivity() {

    private var status by mutableStateOf("Not initialized")
    private var selectedEnvironment by mutableStateOf(Environment.PRODUCTION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable SDK debug logging
        DataPoint.isLoggingEnabled = true

        // Set listener
        DataPoint.setListener(object : DataPointListener {
            override fun onTaskCompleted(payload: String?) {
                log("onTaskCompleted → payload=$payload")
                status = "Task completed: $payload"
            }

            override fun onAdRequested() {
                log("onAdRequested → host should show an ad")
                status = "Ad requested – show your ad here"
            }

            override fun noTaskAvailable() {
                log("noTaskAvailable → host can show native ads")
                status = "No tasks available – show fallback/native ads"
            }

            override fun onClosed() {
                log("onClosed")
                status = "Task screen closed"
            }

            override fun onError(message: String, code: Int) {
                log("onError → $message (code=$code)")
                status = "Error ($code): $message"
            }
        })

        setContent {
            DataPointTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoScreen(
                        status = status,
                        selectedEnvironment = selectedEnvironment,
                        onEnvironmentChange = { env ->
                            if (env != selectedEnvironment) {
                                DataPoint.clearPersistedState(applicationContext)
                                status =
                                    "Environment changed — SDK data cleared. Tap Initialize again."
                            }
                            selectedEnvironment = env
                        },
                        onInitialize = ::initSdk,
                        onSetAttributes = ::setAttributes,
                        onShowTasks = ::showTasks,
                        onClose = ::closeTasks,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun initSdk() {
        status = "Initializing…"
        DataPoint.initialize(
            context = applicationContext,
            apiKey = BuildConfig.DATAPOINT_SDK_API_KEY,
            userId = null,
            environment = selectedEnvironment,
            callback = object : InitCallback {
                override fun onSuccess() {
                    log("SDK initialized successfully")
                    status = "Initialized ✓"
                }

                override fun onError(message: String, code: Int) {
                    log("SDK init failed: $message ($code)")
                    status = "Init failed ($code): $message"
                }
            }
        )
    }

    private fun showTasks() {
        DataPoint.showTasks(this)
    }

    private fun closeTasks() {
        DataPoint.closeTasks()
    }

    private fun setAttributes() {
        status = "Setting attributes…"

        DataPoint.setAge(25)
        DataPoint.setAgeRange("18-24")
        DataPoint.setOccupation("Engineer")
        DataPoint.setGender("male")

        DataPoint.setUserAttributes(
            mapOf("preferred_language" to "en", "theme" to "dark"),
            object : DataPointCallback {
                override fun onSuccess() {
                    log("All attributes set successfully")
                    status = "Attributes set ✓"
                }

                override fun onError(message: String, code: Int) {
                    log("Set attributes failed: $message ($code)")
                    status = "Set attributes failed ($code): $message"
                }
            }
        )
    }

    private fun log(msg: String) {
        Log.d("DemoApp", msg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoScreen(
    status: String,
    selectedEnvironment: Environment,
    onEnvironmentChange: (Environment) -> Unit,
    onInitialize: () -> Unit,
    onSetAttributes: () -> Unit,
    onShowTasks: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val envOptions =
        remember {
            listOf(
                Environment.PRODUCTION to "Production",
                Environment.SANDBOX to "Sandbox"
            )
        }
    var envMenuExpanded by remember { mutableStateOf(false) }
    val envLabel = envOptions.first { it.first == selectedEnvironment }.second

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "DataPoint SDK Demo",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(24.dp))

        ExposedDropdownMenuBox(
            expanded = envMenuExpanded,
            onExpandedChange = { envMenuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = envLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Environment") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = envMenuExpanded) },
                modifier =
                    Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = envMenuExpanded,
                onDismissRequest = { envMenuExpanded = false }
            ) {
                envOptions.forEach { (env, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onEnvironmentChange(env)
                            envMenuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = status,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onInitialize,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Initialize SDK")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onSetAttributes,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set User Attributes")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onShowTasks,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show Tasks")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Close Tasks")
        }
    }
}

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.datapoint.sdk.callbacks.DataPointCallback
import com.datapoint.sdk.callbacks.DataPointListener
import com.datapoint.sdk.DataPoint
import com.datapoint.sdk.models.Environment
import com.datapoint.sdk.callbacks.InitCallback
import com.datapoint.ui.theme.DataPointTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var status by mutableStateOf("Not initialized")

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
                        onInitialize = ::initSdk,
                        onSetAttributes = ::setAttributes,
                        onShowTasks = ::showTasks,
                        onCloseTasks = ::closeTasks,
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
            userId = UUID.randomUUID().toString(),
            environment = Environment.PRODUCTION,
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

@Composable
private fun DemoScreen(
    status: String,
    onInitialize: () -> Unit,
    onSetAttributes: () -> Unit,
    onShowTasks: () -> Unit,
    onCloseTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
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

        Spacer(Modifier.height(32.dp))

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
            onClick = onCloseTasks,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Close Tasks")
        }
    }
}

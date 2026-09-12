package com.sg.linuxgo

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.sg.linuxgo.ui.components.SoftwareGrid
import com.sg.linuxgo.ui.theme.PocketLinuxTheme

/**
 * Choice / text input prompts shown during interactive setup steps.
 */
object SetupPromptUi {

    fun getStepTitle(step: Bootstrap.SetupStep): String {
        return when (step) {
            Bootstrap.SetupStep.SELECT_INSTALL_MODE -> "Choose Installation Mode"
            Bootstrap.SetupStep.SELECT_DISTRO -> "Select Linux Distribution"
            Bootstrap.SetupStep.SELECT_DE -> "Choose Desktop Environment"
            Bootstrap.SetupStep.SELECT_STYLE -> "Select UI Style / Theme"
            Bootstrap.SetupStep.SELECT_WM -> "Choose Window Manager"
            Bootstrap.SetupStep.CONFIGURE_USER -> "Configure User Account"
            Bootstrap.SetupStep.HARDWARE_ACCEL -> "Hardware Acceleration"
            Bootstrap.SetupStep.SELECT_GPU_DRIVER -> "Select Vulkan / GPU Driver"
            Bootstrap.SetupStep.SELECT_OPENGL_BACKEND -> "Select OpenGL Backend"
            Bootstrap.SetupStep.SELECT_SHELL -> "Select Primary Shell"
            Bootstrap.SetupStep.SELECT_ZSH_THEME -> "Select Zsh Theme"
            Bootstrap.SetupStep.SELECT_FONT -> "Select Nerd Font"
            Bootstrap.SetupStep.SELECT_SOFTWARE -> "Select Additional Software"
            Bootstrap.SetupStep.CHECK_COMPATIBILITY -> "System Requirements Check"
            Bootstrap.SetupStep.SELECT_REGION -> "Select Your Region"
            Bootstrap.SetupStep.SELECT_MIRROR -> "Select Fastest Mirror"
            Bootstrap.SetupStep.INSTALLING -> "Installing System Core"
            else -> "Action Required"
        }
    }

    fun showChoicePrompt(
        context: Context,
        promptContainer: LinearLayout,
        tvStepDescription: TextView,
        step: Bootstrap.SetupStep,
        choices: List<Bootstrap.Choice>,
        onConsumed: () -> Unit
    ) {
        promptContainer.removeAllViews()
        promptContainer.visibility = View.VISIBLE
        tvStepDescription.text = getStepTitle(step)

        val isSoftwareSelect = step == Bootstrap.SetupStep.SELECT_SOFTWARE

        if (isSoftwareSelect) {
            val softwareChoices = choices.filter { it.id != "done" }
            val composeView = ComposeView(context).apply {
                setContent {
                    PocketLinuxTheme {
                        var selectedIds by remember { mutableStateOf(emptySet<String>()) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SoftwareGrid(
                                choices = softwareChoices,
                                selectedIds = selectedIds,
                                onSelectionChanged = { id, _, selected ->
                                    selectedIds = selected.toSet()
                                    LocalBroadcastManager.getInstance(context).sendBroadcast(
                                        Intent(SetupForegroundService.BROADCAST_HANDLE_CHOICE)
                                            .putExtra(SetupForegroundService.EXTRA_CHOICE_ID, id)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(360.dp)
                            )
                            androidx.compose.material3.Button(
                                onClick = {
                                    promptContainer.visibility = View.GONE
                                    onConsumed()
                                    LocalBroadcastManager.getInstance(context).sendBroadcast(
                                        Intent(SetupForegroundService.BROADCAST_HANDLE_CHOICE)
                                            .putExtra(SetupForegroundService.EXTRA_CHOICE_ID, "done")
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "CONTINUE")
                            }
                        }
                    }
                }
            }
            promptContainer.addView(composeView)
            return
        }

        choices.forEach { choice ->
            if (choice.id == "done") {
                val proceedBtn = MaterialButton(context).apply {
                    text = choice.label
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 }
                    setOnClickListener {
                        promptContainer.visibility = View.GONE
                        onConsumed()
                        LocalBroadcastManager.getInstance(context).sendBroadcast(
                            Intent(SetupForegroundService.BROADCAST_HANDLE_CHOICE)
                                .putExtra(SetupForegroundService.EXTRA_CHOICE_ID, "done")
                        )
                    }
                }
                promptContainer.addView(proceedBtn)
            } else {
                val btn = MaterialButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 }
                    val isComingSoonDistro =
                        step == Bootstrap.SetupStep.SELECT_DISTRO && choice.id != "debian"
                    text = if (isComingSoonDistro) "${choice.label} (Coming soon)" else choice.label
                    isEnabled = !isComingSoonDistro
                    if (isComingSoonDistro) alpha = 0.5f
                    setOnClickListener {
                        promptContainer.visibility = View.GONE
                        onConsumed()
                        LocalBroadcastManager.getInstance(context).sendBroadcast(
                            Intent(SetupForegroundService.BROADCAST_HANDLE_CHOICE)
                                .putExtra(SetupForegroundService.EXTRA_CHOICE_ID, choice.id)
                        )
                    }
                }
                promptContainer.addView(btn)
            }
        }
    }

    fun showInputPrompt(
        context: Context,
        promptContainer: LinearLayout,
        tvStepDescription: TextView,
        step: Bootstrap.SetupStep,
        title: String,
        hint: String,
        onConsumed: () -> Unit
    ) {
        promptContainer.removeAllViews()
        promptContainer.visibility = View.VISIBLE
        tvStepDescription.text = getStepTitle(step)

        val editText = EditText(context).apply {
            this.hint = hint
            setSingleLine(true)
        }
        val layout = com.google.android.material.textfield.TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(editText)
        }

        val submitBtn = MaterialButton(context).apply {
            text = "SUBMIT"
            setOnClickListener {
                val input = editText.text.toString()
                if (input.isNotEmpty()) {
                    promptContainer.visibility = View.GONE
                    onConsumed()
                    LocalBroadcastManager.getInstance(context).sendBroadcast(
                        Intent(SetupForegroundService.BROADCAST_HANDLE_INPUT)
                            .putExtra(SetupForegroundService.EXTRA_INPUT_VALUE, input)
                    )
                }
            }
        }

        promptContainer.addView(layout)
        promptContainer.addView(submitBtn)
    }
}

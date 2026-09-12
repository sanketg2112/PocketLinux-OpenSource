package com.sg.linuxgo.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import com.sg.linuxgo.ui.utils.performClickHaptic

private data class LearnTopic(
    val id: String,
    val emoji: String,
    val title: String,
    val tagline: String,
    val analogy: String,
    val reveals: List<String>,
    val quiz: Quiz? = null,
    /** When set, detail shows clean is / isn’t cards before progressive reveals. */
    val showExpectations: Boolean = false
)

private data class Quiz(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val success: String
)

private data class TryCommand(
    val label: String,
    val command: String,
    val why: String
)

private val topics = listOf(
    LearnTopic(
        id = "container",
        emoji = "📦",
        title = "Container",
        tagline = "Your Linux in a box",
        analogy = "Think of a shipping container: everything Linux needs lives inside, separate from Android.",
        reveals = listOf(
            "Each container is its own mini computer on your phone.",
            "You can have more than one (e.g. Debian for desktop, Alpine for light use).",
            "Delete a container → that Linux goes away. Android stays fine."
        ),
        quiz = Quiz(
            question = "A container is…",
            options = listOf("An Android app update", "A full Linux environment", "Just a wallpaper"),
            correctIndex = 1,
            success = "Yep — it’s your Linux sandbox."
        )
    ),
    LearnTopic(
        id = "expect",
        emoji = "◇",
        title = "What to expect",
        tagline = "What it is · what it isn’t",
        analogy = "PocketLinux runs real Linux userspace on Android without root. It shares Android’s kernel — powerful for everyday use, with a few hard limits.",
        reveals = listOf(
            "Package installs, desktop apps, coding, and learning are the sweet spot.",
            "Heavy tools can feel slower than on a laptop — normal for rootless Linux.",
            "If something needs Docker, systemd services, or kernel modules, it usually won’t work here."
        ),
        quiz = Quiz(
            question = "PocketLinux is best thought of as…",
            options = listOf(
                "A dual-boot second OS",
                "Real Linux tools on Android, without root",
                "A full Docker host"
            ),
            correctIndex = 1,
            success = "Exactly — everyday Linux on your phone, not a PC dual-boot."
        ),
        showExpectations = true
    ),
    LearnTopic(
        id = "distro",
        emoji = "🐧",
        title = "Distro",
        tagline = "Which Linux flavor?",
        analogy = "Like Android vs iOS — same idea (Linux), different packaging and tools.",
        reveals = listOf(
            "Debian / Ubuntu → apt (lots of packages, beginner-friendly).",
            "Arch → pacman (newest stuff, rolling).",
            "Alpine → apk (tiny & fast)."
        ),
        quiz = Quiz(
            question = "Want the biggest app library?",
            options = listOf("Alpine", "Debian or Ubuntu", "Doesn’t matter"),
            correctIndex = 1,
            success = "Debian/Ubuntu’s apt ecosystem is huge."
        )
    ),
    LearnTopic(
        id = "de",
        emoji = "🖥️",
        title = "Desktop",
        tagline = "Windows, menus, taskbar",
        analogy = "The desktop environment is the “home screen” of Linux — icons, panels, windows.",
        reveals = listOf(
            "XFCE / MATE / LXQt = lighter desktops (good on phones).",
            "Launch GUI opens the desktop; Terminal is text-only.",
            "No desktop? You can still use Terminal only."
        ),
        quiz = Quiz(
            question = "Launch GUI opens…",
            options = listOf("Only a browser", "The Linux desktop", "Android settings"),
            correctIndex = 1,
            success = "GUI = graphical desktop inside the container."
        )
    ),
    LearnTopic(
        id = "terminal",
        emoji = "⌨️",
        title = "Terminal",
        tagline = "Talk to Linux with text",
        analogy = "Like texting Linux: you type a command, it does something and answers.",
        reveals = listOf(
            "Open Terminal from the container card.",
            "You need a network for most installs.",
            "Paste works — copy a command from Tips, paste here."
        ),
        quiz = Quiz(
            question = "Where do you type install commands?",
            options = listOf("Settings app", "Terminal", "Camera"),
            correctIndex = 1,
            success = "Terminal is the control panel for packages."
        )
    ),
    LearnTopic(
        id = "commands",
        emoji = "⚡",
        title = "Commands",
        tagline = "Copy · paste · go",
        analogy = "These are the “sentences” Linux understands. Tap copy, open Terminal, paste.",
        reveals = listOf(
            "Update first, then install apps.",
            "sudo means “do this as admin” — normal for installs.",
            "OpenCode installs with one curl line (any distro)."
        ),
        quiz = null
    ),
    LearnTopic(
        id = "stability",
        emoji = "🛡️",
        title = "Stability",
        tagline = "Recommended on Android 12+",
        analogy = "Android sometimes “cleans up” extra processes. Linux needs lots of them — so the system can kill your desktop by mistake.",
        reveals = listOf(
            "On Android 12+, open Developer options and turn on “Disable child process restrictions” (exact name).",
            "Unlock Developer options: Settings → About phone → tap Build number 7 times.",
            "Without this, the desktop may freeze, black-screen, or exit with no clear error.",
            "Also set PocketLinux battery use to Unrestricted for best background sessions."
        ),
        quiz = Quiz(
            question = "On Android 12+, which Developer option is recommended for stable Linux?",
            options = listOf(
                "Don’t keep activities",
                "Disable child process restrictions",
                "Show layout bounds"
            ),
            correctIndex = 1,
            success = "Yes — that setting lets Linux keep its child processes alive."
        )
    )
)

private val tryCommands = listOf(
    TryCommand("See where you are", "pwd", "Prints the current folder."),
    TryCommand("List files", "ls", "Shows what’s in this folder."),
    TryCommand("Who am I?", "whoami", "Your Linux username."),
    TryCommand("Install Firefox (Debian/Ubuntu)", "sudo apt update && sudo apt install -y firefox", "apt on Debian/Ubuntu."),
    TryCommand("Install Firefox (Arch)", "sudo pacman -S --noconfirm firefox", "pacman on Arch."),
    TryCommand("Install OpenCode", DistroPackageTips.OPENCODE_INSTALL, "Works in Terminal with network.")
)

@Composable
fun LearnMoreScreen(
    isDarkTheme: Boolean,
    accentColor: Color,
    onDismiss: () -> Unit,
    onOpenPackageTips: () -> Unit
) {
    val bg = if (isDarkTheme) BackgroundDark else BackgroundLight
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val cardBg = if (isDarkTheme) Color(0xFF1C1C20) else Color.White
    val border = if (isDarkTheme) Color(0xFF2A2A30) else Color(0xFFE5E5EA)
    val mutedSurface = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
    val context = LocalContext.current
    val view = LocalView.current

    var selectedId by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(setOf<String>()) }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    var quizAnswer by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val progress = completed.size
    val active = topics.find { it.id == selectedId }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = bg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(accentColor.copy(alpha = 0.08f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Header ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Learn the basics",
                            color = textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default
                        )
                        Text(
                            text = if (progress == 0) {
                                "Tap a topic · no homework"
                            } else {
                                "$progress of ${topics.size} explored"
                            },
                            color = if (progress > 0) accentColor else textSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (progress > 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = FontFamily.Default
                        )
                    }
                    IconButton(onClick = {
                        view.performClickHaptic()
                        onDismiss()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textSecondary
                        )
                    }
                }

                // ── Compact topic grid (pinned) ─────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 6.dp, bottom = 10.dp)
                ) {
                    topics.chunked(3).forEach { rowTopics ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowTopics.forEach { topic ->
                                TopicChip(
                                    topic = topic,
                                    selected = selectedId == topic.id,
                                    done = topic.id in completed,
                                    accentColor = accentColor,
                                    cardBg = cardBg,
                                    border = border,
                                    textPrimary = textPrimary,
                                    textSecondary = textSecondary,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    selectedId = if (selectedId == topic.id) null else topic.id
                                }
                            }
                            // Fill empty slots so last row stays aligned
                            repeat(3 - rowTopics.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = border,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // ── Detail (scrolls) ────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 8.dp)
                ) {
                    AnimatedContent(
                        targetState = active,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "topicDetail"
                    ) { topic ->
                        if (topic == null) {
                            EmptyTopicState(
                                accentColor = accentColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                cardBg = cardBg,
                                border = border
                            )
                        } else {
                            TopicDetail(
                                topic = topic,
                                accentColor = accentColor,
                                cardBg = cardBg,
                                border = border,
                                mutedSurface = mutedSurface,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                revealedCount = revealed.count { it.startsWith(topic.id) },
                                onRevealNext = {
                                    val key = "${topic.id}_${revealed.count { it.startsWith(topic.id) }}"
                                    revealed = revealed + key
                                    val n = revealed.count { it.startsWith(topic.id) }
                                    if (n >= topic.reveals.size && topic.quiz == null) {
                                        completed = completed + topic.id
                                    }
                                },
                                quizSelection = quizAnswer[topic.id],
                                onQuizPick = { idx ->
                                    quizAnswer = quizAnswer + (topic.id to idx)
                                    if (idx == topic.quiz?.correctIndex) {
                                        completed = completed + topic.id
                                    }
                                },
                                tryCommands = if (topic.id == "commands") tryCommands else emptyList(),
                                onCopy = { cmd ->
                                    copyClip(context, cmd)
                                    Toast.makeText(context, "Copied — paste in Terminal", Toast.LENGTH_SHORT).show()
                                    completed = completed + "commands"
                                }
                            )
                        }
                    }
                }

                // ── Footer actions ──────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp, top = 4.dp)
                ) {
                    Button(
                        onClick = {
                            view.performClickHaptic()
                            onDismiss()
                            onOpenPackageTips()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = "Package install tips",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Default
                        )
                    }
                    TextButton(
                        onClick = {
                            view.performClickHaptic()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Done",
                            color = textSecondary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Default
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicChip(
    topic: LearnTopic,
    selected: Boolean,
    done: Boolean,
    accentColor: Color,
    cardBg: Color,
    border: Color,
    textPrimary: Color,
    textSecondary: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val bg = when {
        selected -> accentColor.copy(alpha = 0.16f)
        done -> accentColor.copy(alpha = 0.07f)
        else -> cardBg
    }
    val stroke = if (selected) accentColor else border
    val titleColor = if (selected) accentColor else textPrimary

    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(10.dp))
            .clickable {
                view.performClickHaptic()
                onClick()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = topic.emoji,
            fontSize = 13.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = topic.title,
            color = titleColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (done) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "✓",
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun EmptyTopicState(
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    border: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        Text(
            text = "Pick a topic",
            color = textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Default
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Details show here. Start with Container or What to expect.",
            color = textSecondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "QUICK MAP",
            color = textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            fontFamily = FontFamily.Default
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Container → Expect → Distro → Desktop → Terminal → Commands",
            color = textPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Create a box · know the limits · pick a flavor · use GUI or Terminal.",
            color = textSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Tip: short cards above, full explanation below.",
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun TopicDetail(
    topic: LearnTopic,
    accentColor: Color,
    cardBg: Color,
    border: Color,
    mutedSurface: Color,
    textPrimary: Color,
    textSecondary: Color,
    revealedCount: Int,
    onRevealNext: () -> Unit,
    quizSelection: Int?,
    onQuizPick: (Int) -> Unit,
    tryCommands: List<TryCommand>,
    onCopy: (String) -> Unit
) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = topic.emoji, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = topic.title,
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default
                )
                Text(
                    text = topic.tagline,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Default
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = topic.analogy,
            color = textSecondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Default,
            lineHeight = 19.sp
        )

        if (topic.showExpectations) {
            Spacer(modifier = Modifier.height(12.dp))
            WhatItIsWhatItIsNotCards(
                accentColor = accentColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                cardBg = mutedSurface,
                border = border,
                showSweetSpot = true,
                compact = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Progressive reveals
        topic.reveals.forEachIndexed { index, line ->
            if (index < revealedCount) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = line,
                        color = textPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (revealedCount < topic.reveals.size) {
            // Note: never use negative Modifier.padding — Compose throws IllegalArgumentException.
            TextButton(
                onClick = {
                    view.performClickHaptic()
                    onRevealNext()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.padding(start = 0.dp)
            ) {
                Text(
                    text = when {
                        revealedCount == 0 && topic.showExpectations -> "A few more details…"
                        revealedCount == 0 -> "Tap to peek"
                        else -> "One more…"
                    },
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default
                )
            }
        }

        // Quiz
        val quiz = topic.quiz
        if (quiz != null && revealedCount >= topic.reveals.size) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "QUICK CHECK",
                color = textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
                fontFamily = FontFamily.Default
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = quiz.question,
                color = textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Default,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            val solved = quizSelection == quiz.correctIndex
            quiz.options.forEachIndexed { idx, opt ->
                val selected = quizSelection == idx
                val correct = solved && idx == quiz.correctIndex
                val wrong = selected && !solved && idx != quiz.correctIndex
                val optBg = when {
                    correct -> Color(0xFF1B5E20).copy(alpha = if (isLight(cardBg)) 0.12f else 0.35f)
                    wrong -> Color(0xFFB71C1C).copy(alpha = if (isLight(cardBg)) 0.10f else 0.30f)
                    selected -> accentColor.copy(alpha = 0.12f)
                    else -> mutedSurface
                }
                val optBorder = when {
                    correct -> Color(0xFF4CAF50)
                    wrong -> Color(0xFFFF5252)
                    selected -> accentColor
                    else -> border
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(optBg)
                        .border(1.dp, optBorder, RoundedCornerShape(10.dp))
                        .clickable(enabled = !solved) {
                            view.performClickHaptic()
                            onQuizPick(idx)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = opt,
                        color = textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (correct) Text(text = "✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    if (wrong) Text(text = "✗", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            }
            if (solved) {
                Text(
                    text = quiz.success,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else if (quizSelection != null) {
                Text(
                    text = "Not quite — try another",
                    color = textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default
                )
            }
        }

        // Commands playground
        if (tryCommands.isNotEmpty() && revealedCount >= topic.reveals.size) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "TAP TO COPY",
                color = textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
                fontFamily = FontFamily.Default
            )
            Spacer(modifier = Modifier.height(8.dp))
            tryCommands.forEach { cmd ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(mutedSurface)
                        .border(1.dp, border, RoundedCornerShape(10.dp))
                        .clickable {
                            view.performClickHaptic()
                            onCopy(cmd.command)
                        }
                        .padding(11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cmd.label,
                                color = textSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Default
                            )
                            Text(
                                text = cmd.command,
                                color = textPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = cmd.why,
                        color = textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

private fun isLight(color: Color): Boolean =
    color.red + color.green + color.blue > 2.2f

private fun copyClip(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("command", text))
}

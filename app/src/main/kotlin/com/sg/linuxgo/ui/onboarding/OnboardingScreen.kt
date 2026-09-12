package com.sg.linuxgo.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sg.linuxgo.ui.utils.performClickHaptic
import com.sg.linuxgo.ui.utils.performLightHaptic
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sg.linuxgo.LegalDocuments
import com.sg.linuxgo.R
import com.sg.linuxgo.ui.legal.LegalDocumentScreen
import com.sg.linuxgo.ui.theme.BackgroundDark
import com.sg.linuxgo.ui.theme.BackgroundLight
import com.sg.linuxgo.ui.theme.TextLightPrimary
import com.sg.linuxgo.ui.theme.TextLightSecondary
import com.sg.linuxgo.ui.theme.TextPrimary
import com.sg.linuxgo.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private enum class OnboardingPageKind {
    Standard,
    Expectations,
    Permissions
}

private data class OnboardingPage(
    val emoji: String,
    val title: String,
    val body: String,
    val bullets: List<String> = emptyList(),
    val kind: OnboardingPageKind = OnboardingPageKind.Standard
)

private val pages = listOf(
    OnboardingPage(
        emoji = "",
        title = "Linux on your phone",
        body = "PocketLinux runs a full Linux environment on Android — desktop, terminal, and apps — without root."
    ),
    OnboardingPage(
        emoji = "①②③",
        title = "Three quick steps",
        body = "You're only a few taps from a working Linux desktop.",
        bullets = listOf(
            "Create a container (pick a distro)",
            "Install it (needs Wi‑Fi + free storage)",
            "Launch desktop or open terminal"
        )
    ),
    OnboardingPage(
        emoji = "",
        title = "What to expect",
        body = LinuxExpectations.shortBody,
        kind = OnboardingPageKind.Expectations
    ),
    OnboardingPage(
        emoji = "",
        title = OnboardingPermissionCopy.TITLE,
        body = OnboardingPermissionCopy.BODY,
        kind = OnboardingPageKind.Permissions
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    isDarkTheme: Boolean,
    accentColor: Color,
    onFinished: () -> Unit,
    onCreateContainer: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.lastIndex
    val currentKind = pages[pagerState.currentPage].kind
    val view = LocalView.current
    val context = LocalContext.current
    var openLegalDocument by remember { mutableStateOf<LegalDocuments.Kind?>(null) }

    fun acceptLegal() {
        LegalDocuments.accept(context)
    }

    fun declineLegal() {
        view.performLightHaptic()
    }

    val permissionActions = rememberOnboardingPermissionActions(
        onContinueAfterAllow = onCreateContainer,
        onSkipFinished = onFinished
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionActions.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bg = if (isDarkTheme) BackgroundDark else BackgroundLight
    val textPrimary = if (isDarkTheme) TextPrimary else TextLightPrimary
    val textSecondary = if (isDarkTheme) TextSecondary else TextLightSecondary
    val cardBg = if (isDarkTheme) Color(0xFF1C1C20) else Color.White
    val cardBorder = if (isDarkTheme) Color(0xFF2A2A30) else Color(0xFFE5E5EA)
    val dotInactive = if (isDarkTheme) Color(0xFF44444A) else Color(0xFFD0D0D5)

    Dialog(
        onDismissRequest = { /* require explicit finish */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = bg
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(accentColor.copy(alpha = 0.12f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (!isLast) {
                            TextButton(
                                onClick = {
                                    view.performClickHaptic()
                                    scope.launch {
                                        pagerState.animateScrollToPage(pages.lastIndex)
                                    }
                                }
                            ) {
                                Text(
                                    text = "Skip",
                                    color = textSecondary,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Default
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { page ->
                        OnboardingPageContent(
                            page = pages[page],
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            cardBg = cardBg,
                            cardBorder = cardBorder,
                            accentColor = accentColor,
                            isDarkTheme = isDarkTheme,
                            notificationGranted = permissionActions.notificationGranted,
                            storageGranted = permissionActions.storageGranted
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pages.indices.forEach { index ->
                            val selected = pagerState.currentPage == index
                            val width by animateDpAsState(
                                targetValue = if (selected) 20.dp else 8.dp,
                                label = "dotWidth"
                            )
                            val color by animateColorAsState(
                                targetValue = if (selected) accentColor else dotInactive,
                                label = "dotColor"
                            )
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .height(8.dp)
                                    .width(width)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        view.performClickHaptic()
                                        scope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 28.dp)
                    ) {
                        if (currentKind == OnboardingPageKind.Permissions) {
                            OnboardingLegalConsentText(
                                accentColor = accentColor,
                                textPrimary = textPrimary,
                                onOpenTerms = {
                                    view.performClickHaptic()
                                    openLegalDocument = LegalDocuments.Kind.TERMS
                                },
                                onOpenPrivacy = {
                                    view.performClickHaptic()
                                    openLegalDocument = LegalDocuments.Kind.PRIVACY
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Button(
                            onClick = {
                                when {
                                    currentKind == OnboardingPageKind.Permissions -> {
                                        acceptLegal()
                                        // Request recommended permissions, then leave onboarding.
                                        permissionActions.requestRecommendedThen()
                                    }
                                    isLast -> {
                                        acceptLegal()
                                        view.performClickHaptic()
                                        onCreateContainer()
                                    }
                                    else -> {
                                        view.performClickHaptic()
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = when {
                                    currentKind == OnboardingPageKind.Permissions ->
                                        LegalDocuments.AGREE_BUTTON
                                    isLast -> "Create environment"
                                    else -> "Next"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Default
                            )
                        }

                        if (currentKind == OnboardingPageKind.Permissions || isLast) {
                            TextButton(
                                onClick = { declineLegal() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            ) {
                                Text(
                                    text = if (currentKind == OnboardingPageKind.Permissions) {
                                        "Not now"
                                    } else {
                                        "I'll explore first"
                                    },
                                    color = textSecondary,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Default
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                }
                openLegalDocument?.let { kind ->
                    LegalDocumentScreen(
                        kind = kind,
                        isDarkTheme = isDarkTheme,
                        onDismiss = { openLegalDocument = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    textPrimary: Color,
    textSecondary: Color,
    cardBg: Color,
    cardBorder: Color,
    accentColor: Color,
    isDarkTheme: Boolean,
    notificationGranted: Boolean = false,
    storageGranted: Boolean = false
) {
    val scroll = rememberScrollState()
    val topAligned = page.kind == OnboardingPageKind.Expectations ||
        page.kind == OnboardingPageKind.Permissions
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (topAligned) {
            Arrangement.Top
        } else {
            Arrangement.Center
        }
    ) {
        if (topAligned) {
            Spacer(modifier = Modifier.height(8.dp))
        }

        Image(
            painter = painterResource(R.drawable.ic_pocket_linux),
            contentDescription = null,
            modifier = Modifier
                .size(if (topAligned) 56.dp else 72.dp)
                .clip(RoundedCornerShape(if (topAligned) 14.dp else 18.dp))
        )

        if (page.emoji.isNotEmpty()) {
            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = page.emoji,
                fontSize = 36.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(if (topAligned) 18.dp else 28.dp))
        }

        Text(
            text = page.title,
            color = textPrimary,
            fontSize = if (topAligned) 22.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = page.body,
            color = textSecondary,
            fontSize = 15.sp,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        when (page.kind) {
            OnboardingPageKind.Expectations -> {
                Spacer(modifier = Modifier.height(20.dp))
                WhatItIsWhatItIsNotCards(
                    accentColor = accentColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    cardBg = cardBg,
                    border = cardBorder,
                    showSweetSpot = true,
                    compact = true
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            OnboardingPageKind.Permissions -> {
                Spacer(modifier = Modifier.height(18.dp))
                OnboardingPermissionCards(
                    accentColor = accentColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    cardBg = cardBg,
                    cardBorder = cardBorder,
                    notificationGranted = notificationGranted,
                    storageGranted = storageGranted
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            OnboardingPageKind.Standard -> {
                if (page.bullets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cardBg)
                            .border(1.dp, cardBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        page.bullets.forEachIndexed { index, bullet ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(accentColor.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = accentColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Default
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = bullet,
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Default,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.sg.linuxgo.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Step2Content(
    containerName: String,
    onContainerNameChanged: (String) -> Unit,
    username: String,
    onUsernameChanged: (String) -> Unit,
    installRecommends: Boolean,
    onInstallRecommendsChanged: (Boolean) -> Unit,
    distroColor: Color
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "IDENTITY",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            letterSpacing = 0.05.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Username Input
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChanged,
            label = { Text("Linux Username (non-root)", fontFamily = FontFamily.Default) },
            textStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = distroColor,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = distroColor,
                unfocusedLabelColor = TextSecondary
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )
        
        // Container Name Input
        OutlinedTextField(
            value = containerName,
            onValueChange = onContainerNameChanged,
            label = { Text("Container Name (e.g. Work Lab)", fontFamily = FontFamily.Default) },
            textStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = distroColor,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = distroColor,
                unfocusedLabelColor = TextSecondary
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        
        Text(
            text = "Running as a non-root user is safer and recommended for GUI applications like Chromium.",
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Default,
            modifier = Modifier.padding(bottom = 20.dp)
        )
        
        Text(
            text = "PACKAGE PROFILE",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
            letterSpacing = 0.05.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Minimal Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clickable { onInstallRecommendsChanged(false) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                width = if (!installRecommends) 2.dp else 1.dp,
                color = if (!installRecommends) distroColor else MaterialTheme.colorScheme.outline
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Minimal",
                    color = if (!installRecommends) distroColor else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    text = "Default slim setup with only core packages. (Fastest install)",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default
                )
            }
        }
        
        // Full Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .clickable { onInstallRecommendsChanged(true) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                width = if (installRecommends) 2.dp else 1.dp,
                color = if (installRecommends) distroColor else MaterialTheme.colorScheme.outline
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Add Recommended Packages",
                    color = if (installRecommends) distroColor else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    text = "Installs all recommended utility apps, tools and default browser. (+ ~1.5 - 2.5 GB size increase; takes more time to install)",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Includes: Firefox ESR/Browser, pavucontrol (Audio), evince (PDF/Document viewer), file-roller (Archive Manager), network-manager-gnome, extra fonts & themes, Python3 utilities, and desktop system helpers.",
                    color = if (installRecommends) distroColor.copy(alpha = 0.8f) else TextSecondary.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

package com.sg.linuxgo.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sg.linuxgo.LegalDocuments

@Composable
internal fun OnboardingLegalConsentText(
    accentColor: Color,
    textPrimary: Color,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = accentColor,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline
        )
    )
    val annotated = buildAnnotatedString {
        append(LegalDocuments.CONSENT_LEAD)
        withLink(
            LinkAnnotation.Clickable(
                tag = "terms",
                styles = linkStyle,
                linkInteractionListener = { onOpenTerms() }
            )
        ) {
            append(LegalDocuments.TERMS_TITLE)
        }
        append(LegalDocuments.CONSENT_JOIN)
        withLink(
            LinkAnnotation.Clickable(
                tag = "privacy",
                styles = linkStyle,
                linkInteractionListener = { onOpenPrivacy() }
            )
        ) {
            append(LegalDocuments.PRIVACY_TITLE)
        }
        append(LegalDocuments.CONSENT_TAIL)
    }
    Text(
        text = annotated,
        color = textPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Default,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

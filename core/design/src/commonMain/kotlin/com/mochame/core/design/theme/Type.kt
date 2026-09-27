package com.mochame.core.design.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mochame.core.design.generated.resources.PlusJakartaSans_Bold
import com.mochame.core.design.generated.resources.PlusJakartaSans_Medium
import com.mochame.core.design.generated.resources.PlusJakartaSans_Regular
import com.mochame.core.design.generated.resources.Res
import com.mochame.core.design.generated.resources.Sora_Bold
import com.mochame.core.design.generated.resources.Sora_Medium
import com.mochame.core.design.generated.resources.Sora_Regular
import org.jetbrains.compose.resources.Font


@Composable
fun displayFontFamily() = FontFamily(
    Font(Res.font.PlusJakartaSans_Regular, FontWeight.Normal),
    Font(Res.font.PlusJakartaSans_Medium, FontWeight.Medium),
    Font(Res.font.PlusJakartaSans_Bold, FontWeight.Bold)
)

@Composable
fun bodyFontFamily() = FontFamily(
    Font(Res.font.Sora_Regular, FontWeight.Normal),
    Font(Res.font.Sora_Medium, FontWeight.Medium),
    Font(Res.font.Sora_Bold, FontWeight.Bold)
)

@Composable
fun appTypography(): Typography {
    val baseline = Typography()
    val bodyFontFamily = bodyFontFamily()
    val displayFontFamily = displayFontFamily()

    return Typography(
        displayLarge = baseline.displayLarge.copy(fontFamily = displayFontFamily),
        displayMedium = baseline.displayMedium.copy(fontFamily = displayFontFamily),
        displaySmall = baseline.displaySmall.copy(fontFamily = displayFontFamily),
        headlineLarge = baseline.headlineLarge.copy(fontFamily = displayFontFamily),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = displayFontFamily),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = displayFontFamily),
        titleLarge = baseline.titleLarge.copy(fontFamily = displayFontFamily),
        titleMedium = baseline.titleMedium.copy(fontFamily = displayFontFamily),
        titleSmall = baseline.titleSmall.copy(fontFamily = displayFontFamily),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFontFamily),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFontFamily),
        bodySmall = baseline.bodySmall.copy(fontFamily = bodyFontFamily),
        labelLarge = baseline.labelLarge.copy(fontFamily = bodyFontFamily),
        labelMedium = baseline.labelMedium.copy(fontFamily = bodyFontFamily),
        labelSmall = baseline.labelSmall.copy(fontFamily = bodyFontFamily),
    )
}

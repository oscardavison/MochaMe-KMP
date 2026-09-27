package com.mochame.core.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mochame.core.design.generated.resources.Res
import com.mochame.core.design.generated.resources.app_logo
import org.jetbrains.compose.resources.painterResource

@Composable
fun AppLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.app_logo),
        contentDescription = "MochaMe Logo",
        modifier = modifier
    )
}

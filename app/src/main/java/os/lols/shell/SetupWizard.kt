package os.lols.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import os.lols.ui.AppIconTile
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsPalette
import os.lols.ui.OsButton
import os.lols.ui.PinPad
import os.lols.ui.t

/**
 * First-boot setup wizard: choose language, create a PIN (stored as a
 * real salted hash). Completing it marks the device as initialized.
 */
@Composable
fun SetupWizard(onDone: () -> Unit) {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            0 -> {
                AppIconTile(glyph = "L", color = OsPalette.accent, size = 76.dp)
                Spacer(Modifier.height(12.dp))
                Text(t("Welcome to LOLS OS", "مرحبًا بك في LOLS OS"), color = OsPalette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(t("a real OS inside your phone", "نظام تشغيل حقيقي داخل هاتفك"), color = OsPalette.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(28.dp))
                Text(t("Choose your language", "اختر لغتك"), color = OsPalette.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LangCard("English", "A", false)
                    LangCard("\u0627\u0644\u0639\u0631\u0628\u064A\u0629", "\u0639", true)
                }
                Spacer(Modifier.height(20.dp))
                OsButton(t("Continue", "متابعة"), { step = 1 }, style = os.lols.ui.OsButtonStyle.PRIMARY)
            }

            1 -> {
                Text(t("Create a PIN", "أنشئ رمز قفل"), color = OsPalette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(t("4 digits - unlocks your LOLS OS", "4 أرقام - لفتح نظامك"), color = OsPalette.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                PinPad(
                    entered = pin1.length,
                    onDigit = {
                        if (pin1.length < 4) {
                            pin1 += it
                            if (pin1.length == 4) step = 2
                        }
                    },
                    onBackspace = { if (pin1.isNotEmpty()) pin1 = pin1.dropLast(1) },
                )
                Spacer(Modifier.height(12.dp))
                OsButton(t("Back", "رجوع"), { step = 0 }, small = true)
            }

            2 -> {
                Text(t("Confirm your PIN", "أكد رمز القفل"), color = OsPalette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (mismatch) t("PINs did not match - try again", "الرمزان غير متطابقين - حاول مجددًا") else t("Enter the same 4 digits", "أدخل نفس الأرقام الأربعة"),
                    color = if (mismatch) OsPalette.red else OsPalette.textDim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(22.dp))
                PinPad(
                    entered = pin2.length,
                    onDigit = {
                        if (pin2.length < 4) {
                            pin2 += it
                            if (pin2.length == 4) {
                                if (pin2 == pin1) {
                                    scope.launch {
                                        kernel.setPin(pin1)
                                        onDone()
                                    }
                                } else {
                                    mismatch = true
                                    pin1 = ""
                                    pin2 = ""
                                    step = 1
                                }
                            }
                        }
                    },
                    onBackspace = { if (pin2.isNotEmpty()) pin2 = pin2.dropLast(1) },
                )
            }
        }
    }
}

@Composable
private fun LangCard(label: String, glyph: String, arabic: Boolean) {
    val kernel = LocalKernel.current
    val settings by kernel.settings.collectAsState()
    val selected = settings.langArabic == arabic
    GlassSurface(Modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(
            Modifier
                .clickable { kernel.update { it.copy(langArabic = arabic) } }
                .padding(vertical = 20.dp, horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(glyph, color = if (selected) OsPalette.accent else OsPalette.textDim, fontSize = 30.sp)
            Spacer(Modifier.height(8.dp))
            Text(label, color = if (selected) OsPalette.text else OsPalette.textDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.app.DarziLedgerApp
import com.example.app.TailorApp
import com.example.core.preferences.ShopPreferences
import com.example.core.preferences.ThemeMode
import com.example.core.ui.ProvideAppLocale
import com.example.ui.theme.DarziLedgerTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val appContainer = (application as TailorApp).container

    setContent {
      val prefs by appContainer.shopPreferencesRepository.preferencesFlow
        .collectAsStateWithLifecycle(initialValue = ShopPreferences())

      val darkTheme = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
      }

      ProvideAppLocale(language = prefs.language) {
        DarziLedgerTheme(darkTheme = darkTheme) {
          DarziLedgerApp(appContainer = appContainer)
        }
      }
    }
  }
}

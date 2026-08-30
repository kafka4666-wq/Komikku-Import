package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun customizationFeatureSearchPreference(
    query: String,
    onQueryChanged: (String) -> Unit,
): Preference.PreferenceItem.CustomPreference = Preference.PreferenceItem.CustomPreference(
    title = "Search features",
    content = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search features") },
                placeholder = { Text("Try: reader, card, import, privacy") },
                singleLine = true,
            )
        }
    },
)

internal fun filterCustomizationPreferences(
    preferences: List<Preference>,
    query: String,
): List<Preference> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return preferences

    return preferences.mapNotNull { preference ->
        when (preference) {
            is Preference.PreferenceGroup -> {
                val groupMatches = preference.title.contains(normalizedQuery, ignoreCase = true)
                val matchingItems = if (groupMatches) {
                    preference.preferenceItems
                } else {
                    preference.preferenceItems.filter { item ->
                        item.title.contains(normalizedQuery, ignoreCase = true) ||
                            item.subtitle?.toString()?.contains(normalizedQuery, ignoreCase = true) == true
                    }
                }
                if (groupMatches || matchingItems.isNotEmpty()) {
                    Preference.PreferenceGroup(
                        title = preference.title,
                        enabled = preference.enabled,
                        preferenceItems = persistentListOf(*matchingItems.toTypedArray()),
                    )
                } else {
                    null
                }
            }
            is Preference.PreferenceItem<*, *> -> {
                if (
                    preference.title.contains(normalizedQuery, ignoreCase = true) ||
                    preference.subtitle?.toString()?.contains(normalizedQuery, ignoreCase = true) == true
                ) {
                    preference
                } else {
                    null
                }
            }
        }
    }
}

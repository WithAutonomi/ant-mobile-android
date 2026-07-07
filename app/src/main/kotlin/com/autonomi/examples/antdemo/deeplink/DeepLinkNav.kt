package com.autonomi.examples.antdemo.deeplink

import kotlinx.coroutines.flow.MutableStateFlow

/// One-shot channel from the Activity's intent handling to the Compose nav
/// host: set a route to navigate to after handling an `autonomi://` deep link.
object DeepLinkNav {
    val target = MutableStateFlow<String?>(null)
    fun goto(route: String) { target.value = route }
    fun consumed() { target.value = null }
}

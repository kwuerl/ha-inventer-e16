// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Korbinian Würl
package de.wuerl.easyconnect.ui

/**
 * Holds back work that needs the local network until ACCESS_LOCAL_NETWORK is granted, and asks
 * for it right away instead of letting Android drop the traffic silently.
 *
 * [run] executes the action at once when the permission is there. Otherwise it remembers the
 * action (one per [key], the latest wins), calls `blocked` so the screen can say why nothing
 * happens, and publishes [LocalNetworkUi.Request]; the UI then shows the system dialog and
 * reports the answer to [onResult]. Main thread only.
 */
class LocalNetworkGate(
    private val isGranted: () -> Boolean,
    private val publish: (LocalNetworkUi?) -> Unit,
) {
    private val waiting = LinkedHashMap<String, () -> Unit>()

    var state: LocalNetworkUi? = null
        private set(value) {
            field = value
            publish(value)
        }

    fun run(key: String, blocked: () -> Unit = {}, action: () -> Unit) {
        if (isGranted()) {
            waiting.remove(key)
            action()
            return
        }
        waiting[key] = action
        blocked()
        if (state != LocalNetworkUi.Requesting) state = LocalNetworkUi.Request
    }

    /** The UI is showing the system dialog: don't ask a second time meanwhile. */
    fun onRequesting() {
        state = LocalNetworkUi.Requesting
    }

    /**
     * The answer of the system dialog. [canAskAgain] is false when Android won't show the dialog
     * any more (denied for good); the user can still allow it in the app settings.
     */
    fun onResult(granted: Boolean, canAskAgain: Boolean) {
        if (!granted) {
            state = LocalNetworkUi.Denied(canAskAgain)
            return
        }
        state = null
        val actions = waiting.values.toList()
        waiting.clear()
        actions.forEach { it() }
    }

    /** "Allow" on the screen: ask again. */
    fun ask() {
        state = LocalNetworkUi.Request
    }

    /** Back in the app (e.g. from the settings): continue if the permission is there now. */
    fun recheck() {
        if (state != null && state != LocalNetworkUi.Requesting && isGranted()) onResult(granted = true, canAskAgain = true)
    }
}

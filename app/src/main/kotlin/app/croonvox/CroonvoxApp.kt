// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import android.app.Application

class CroonvoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

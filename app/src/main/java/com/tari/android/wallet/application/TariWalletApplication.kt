/**
 * Copyright 2020 The Tari Project
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of
 * its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tari.android.wallet.application

import android.app.Activity
import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import com.tari.android.wallet.data.sharedPrefs.SharedPrefsRepository
import com.tari.android.wallet.di.DiContainer
import com.tari.android.wallet.event.Event
import com.tari.android.wallet.event.EventBus
import com.tari.android.wallet.infrastructure.Tracker
import com.tari.android.wallet.network.NetworkConnectionStateReceiver
import com.tari.android.wallet.notification.NotificationHelper
import com.tari.android.wallet.service.WalletServiceLauncher
import com.tari.android.wallet.yat.YatAdapter
import net.danlew.android.joda.JodaTimeAndroid
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Main application class.
 *
 * @author The Tari Development Team
 */
internal class TariWalletApplication : Application() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var tracker: Tracker

    @Inject
    lateinit var connectionStateReceiver: NetworkConnectionStateReceiver

    @Inject
    lateinit var sharedPrefsRepository: SharedPrefsRepository

    @Inject
    lateinit var walletServiceLauncher: WalletServiceLauncher

    @Inject
    lateinit var yatAdapter: YatAdapter

    private val activityLifecycleCallbacks = ActivityLifecycleCallbacks()

    var isInForeground = false
        private set

    init {
        System.loadLibrary("native-lib")
    }

    val currentActivity: Activity?
        get() = activityLifecycleCallbacks.currentActivity

    override fun onCreate() {
        super.onCreate()
        INSTANCE = WeakReference(this)

        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        Logger.addLogAdapter(AndroidLogAdapter())
        JodaTimeAndroid.init(this)

        DiContainer.initContainer(this)
        initApplication()

        ProcessLifecycleOwner.get().lifecycle.addObserver(AppObserver())
    }

    fun initApplication() {
        DiContainer.appComponent.inject(this)

        notificationHelper.createNotificationChannels()

        // user should authenticate every time the app starts up
        sharedPrefsRepository.isAuthenticated = false

        registerReceiver(connectionStateReceiver, connectionStateReceiver.intentFilter)

        // track app download
        tracker.download(this)

        yatAdapter.initYat(this)
    }

    companion object {
        @Volatile
        var INSTANCE: WeakReference<TariWalletApplication> = WeakReference(null)
            private set
    }

    inner class AppObserver : DefaultLifecycleObserver {

        override fun onStart(owner: LifecycleOwner) {
            super.onStart(owner)
            Logger.d("App in foreground.")
            isInForeground = true
            walletServiceLauncher.startOnAppForegrounded()
            EventBus.post(Event.App.AppForegrounded())
        }

        override fun onStop(owner: LifecycleOwner) {
            super.onStop(owner)
            Logger.d("App in background.")
            isInForeground = false
            walletServiceLauncher.stopOnAppBackgrounded()
            EventBus.post(Event.App.AppBackgrounded())
        }

        override fun onDestroy(owner: LifecycleOwner) {
            super.onDestroy(owner)
            Logger.d("App was destroyed.")
            walletServiceLauncher.stopOnAppBackgrounded()
        }
    }
}


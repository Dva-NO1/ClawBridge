package com.clawbridge.mobile

import android.app.Application
import com.clawbridge.mobile.data.MonitorRepository
import com.clawbridge.mobile.data.SettingsStore
import com.clawbridge.mobile.net.RelaySocketClient
import com.clawbridge.mobile.service.NotificationCenter
import com.clawbridge.mobile.service.RelayForegroundService

class ClawBridgeApp : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    lateinit var socketClient: RelaySocketClient
        private set

    lateinit var monitorRepository: MonitorRepository
        private set

    lateinit var notificationCenter: NotificationCenter
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        socketClient = RelaySocketClient(this, settingsStore)
        monitorRepository = MonitorRepository(this, socketClient)
        // Subscribe NotificationCenter BEFORE socketClient.start() so offline queue bursts are never missed
        notificationCenter = NotificationCenter(this, settingsStore, socketClient)
        socketClient.start()
        RelayForegroundService.start(this)
    }
}

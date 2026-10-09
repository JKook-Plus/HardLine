package dev.hardline

import android.app.Application
import dev.hardline.service.CameraController

class App : Application() {
    lateinit var controller: CameraController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = CameraController(this)
    }
}

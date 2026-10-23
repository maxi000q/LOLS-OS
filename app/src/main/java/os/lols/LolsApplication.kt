package os.lols

import android.app.Application
import os.lols.kernel.Kernel

/** Application entry: boots the LOLS kernel for real. */
class LolsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Kernel.init(this).boot()
    }
}

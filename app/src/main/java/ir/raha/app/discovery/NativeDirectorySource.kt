package ir.raha.app.discovery

import android.os.Build
import ir.raha.app.BuildConfig
import ir.raha.nativebridge.rahacore.Rahacore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only VerifiedDirectorySource in the app: the pinned OpenRung brokerapi
 * running inside the native core. There is deliberately no Kotlin HTTP fallback.
 */
class NativeDirectorySource(private val limit: Int = 20) : VerifiedDirectorySource {
    override suspend fun fetchVerifiedRelayDirectory(): ByteArray = withContext(Dispatchers.IO) {
        Rahacore.fetchVerifiedRelays(
            "raha-${BuildConfig.VERSION_NAME}",
            Build.VERSION.SDK_INT.toString(),
            limit.toLong()
        ).toByteArray(Charsets.UTF_8)
    }
}

package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityDeviceSettingsBinding
import ai.guiji.duix.test.service.FamilyBridgeService
import ai.guiji.duix.test.util.QrCodeBitmapFactory
import android.os.Bundle
import android.util.TypedValue
import android.widget.Toast
import org.json.JSONObject

class DeviceSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityDeviceSettingsBinding
    private val familyBridgeService by lazy { FamilyBridgeService(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { syncDeviceRegistration(true) }

        renderIdentityCard(familyBridgeService.getBindUrl())
        syncDeviceRegistration(false)
        Thread {
            runCatching {
                familyBridgeService.recordUsageEvent(
                    FamilyBridgeService.EVENT_SETTINGS_OPENED,
                    JSONObject().put("screen", "device_settings"),
                )
            }
        }.start()
    }

    private fun renderIdentityCard(bindUrl: String) {
        val deviceUuid = familyBridgeService.getDeviceUuid()
        binding.tvDeviceUuidValue.text = deviceUuid
        binding.tvBindLinkValue.text = bindUrl
        val qrSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            220f,
            resources.displayMetrics,
        ).toInt()
        binding.ivQrCode.setImageBitmap(QrCodeBitmapFactory.create(bindUrl, qrSize))
    }

    private fun syncDeviceRegistration(showToast: Boolean) {
        binding.btnRefresh.isEnabled = false
        binding.tvSyncStatus.text = getString(R.string.device_settings_syncing)

        Thread {
            val result = runCatching { familyBridgeService.registerCurrentDevice() }
            runOnUiThread {
                binding.btnRefresh.isEnabled = true
                result.onSuccess { registration ->
                    renderIdentityCard(registration.bindUrl)
                    binding.tvSyncStatus.text = getString(R.string.device_settings_sync_success)
                    if (showToast) {
                        Toast.makeText(mContext, R.string.device_settings_sync_success, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { error ->
                    renderIdentityCard(familyBridgeService.getBindUrl())
                    binding.tvSyncStatus.text = getString(
                        R.string.device_settings_sync_failed,
                        error.message ?: getString(R.string.ai_call_error_retry),
                    )
                    if (showToast) {
                        Toast.makeText(
                            mContext,
                            error.message ?: getString(R.string.ai_call_error_retry),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }.start()
    }
}
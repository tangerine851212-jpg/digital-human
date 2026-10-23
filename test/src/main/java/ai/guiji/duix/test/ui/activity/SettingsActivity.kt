package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.test.BuildConfig
import ai.guiji.duix.test.databinding.ActivitySettingsBinding
import ai.guiji.duix.test.service.DashScopeConfig
import ai.guiji.duix.test.service.DebugSettingsStore
import ai.guiji.duix.test.service.FamilyBridgeService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val debugSettingsStore by lazy { DebugSettingsStore(applicationContext) }
    private val familyBridgeService by lazy { FamilyBridgeService(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowDeviceCode.setOnClickListener {
            startActivity(Intent(mContext, DeviceSettingsActivity::class.java))
        }
        binding.rowDebugMode.setOnClickListener {
            binding.switchDebugMode.performClick()
        }
        binding.rowDigitalHumanTest.setOnClickListener {
            startActivity(Intent(mContext, DigitalHumanTestActivity::class.java))
        }

        binding.tvVersionValue.text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvServerValue.text = Uri.parse(DashScopeConfig.MONITOR_BASE_URL).host
            ?: DashScopeConfig.MONITOR_BASE_URL
        binding.tvDeviceValue.text = familyBridgeService.getDeviceUuid()
        binding.switchDebugMode.isChecked = debugSettingsStore.isChatDebugEnabled()
        binding.switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            debugSettingsStore.setChatDebugEnabled(isChecked)
        }

        Thread {
            runCatching {
                familyBridgeService.recordUsageEvent(
                    FamilyBridgeService.EVENT_SETTINGS_OPENED,
                    JSONObject().put("screen", "settings_home"),
                )
            }
        }.start()
    }
}
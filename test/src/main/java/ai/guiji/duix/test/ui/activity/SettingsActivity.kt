package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.test.BuildConfig
import ai.guiji.duix.test.databinding.ActivitySettingsBinding
import ai.guiji.duix.test.model.AudienceProfileCatalog
import ai.guiji.duix.test.service.AudiencePreferenceStore
import ai.guiji.duix.test.service.DashScopeConfig
import ai.guiji.duix.test.service.DebugSettingsStore
import ai.guiji.duix.test.service.FamilyBridgeService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val audiencePreferenceStore by lazy { AudiencePreferenceStore(applicationContext) }
    private val debugSettingsStore by lazy { DebugSettingsStore(applicationContext) }
    private val familyBridgeService by lazy { FamilyBridgeService(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowAudienceMode.setOnClickListener {
            startActivity(
                Intent(mContext, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_FROM_SETTINGS, true)
            )
        }
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
        updateAudienceMode()

        Thread {
            runCatching {
                familyBridgeService.recordUsageEvent(
                    FamilyBridgeService.EVENT_SETTINGS_OPENED,
                    JSONObject().put("screen", "settings_home"),
                )
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateAudienceMode()
        }
    }

    private fun updateAudienceMode() {
        val segment = audiencePreferenceStore.getSegment()
        binding.tvAudienceModeValue.text = if (segment == null) {
            "尚未选择 · 点击选择"
        } else {
            val profile = AudienceProfileCatalog.get(segment)
            "当前：${profile.displayName} · ${profile.ageRange} · 点击切换"
        }
    }
}
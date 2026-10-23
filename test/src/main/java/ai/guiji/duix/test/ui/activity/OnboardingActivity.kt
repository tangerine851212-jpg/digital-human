package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.test.databinding.ActivityOnboardingBinding
import ai.guiji.duix.test.model.AudienceSegment
import ai.guiji.duix.test.service.AudiencePreferenceStore
import android.content.Intent
import android.os.Bundle
import android.view.View

class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val audienceStore by lazy { AudiencePreferenceStore(applicationContext) }
    private var selectedSegment: AudienceSegment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedSegment = audienceStore.getSegment()
        renderSelection()

        binding.btnBack.setOnClickListener { finish() }
        binding.optionTeen.setOnClickListener { select(AudienceSegment.TEEN) }
        binding.optionAdult.setOnClickListener { select(AudienceSegment.ADULT) }
        binding.optionSenior.setOnClickListener { select(AudienceSegment.SENIOR) }
        binding.btnContinue.setOnClickListener {
            val segment = selectedSegment ?: return@setOnClickListener
            audienceStore.saveSegment(segment)
            if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
                finish()
            } else {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            binding.btnContinue.text = "保存设置"
        }
    }

    private fun select(segment: AudienceSegment) {
        selectedSegment = segment
        renderSelection()
    }

    private fun renderSelection() {
        binding.optionTeen.isSelected = selectedSegment == AudienceSegment.TEEN
        binding.optionAdult.isSelected = selectedSegment == AudienceSegment.ADULT
        binding.optionSenior.isSelected = selectedSegment == AudienceSegment.SENIOR

        binding.checkTeen.visibility =
            if (selectedSegment == AudienceSegment.TEEN) View.VISIBLE else View.INVISIBLE
        binding.checkAdult.visibility =
            if (selectedSegment == AudienceSegment.ADULT) View.VISIBLE else View.INVISIBLE
        binding.checkSenior.visibility =
            if (selectedSegment == AudienceSegment.SENIOR) View.VISIBLE else View.INVISIBLE

        binding.btnContinue.isEnabled = selectedSegment != null
    }

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
    }
}

package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.test.R
import ai.guiji.duix.test.audio.VoiceCloneRecorder
import ai.guiji.duix.test.databinding.ActivityVoiceCloneSetupBinding
import ai.guiji.duix.test.model.AvatarCatalog
import ai.guiji.duix.test.model.AvatarModel
import ai.guiji.duix.test.model.VoiceCloneProfile
import ai.guiji.duix.test.service.DashScopeConfig
import ai.guiji.duix.test.service.DebugSettingsStore
import ai.guiji.duix.test.service.VoiceCloneProfileStore
import ai.guiji.duix.test.service.VoiceCloneService
import ai.guiji.duix.test.ui.adapter.AvatarAdapter
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import java.io.File
import java.util.Locale

class VoiceCloneSetupActivity : BaseActivity() {

    private lateinit var binding: ActivityVoiceCloneSetupBinding
    private lateinit var avatarAdapter: AvatarAdapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debugSettingsStore by lazy { DebugSettingsStore(applicationContext) }
    private val profileStore by lazy { VoiceCloneProfileStore(this) }
    private val voiceCloneService = VoiceCloneService()
    private val recorder = VoiceCloneRecorder()

    private var selectedAvatar: AvatarModel = AvatarCatalog.models.first()
    private var activeProfile: VoiceCloneProfile? = null
    private var currentRecording: VoiceCloneRecorder.VoiceRecording? = null
    private var isRecording = false
    private var isCreatingVoice = false
    private var recordStartedAtMs = 0L

    private val recordTicker = object : Runnable {
        override fun run() {
            if (!isRecording) {
                return
            }
            val elapsed = (SystemClock.elapsedRealtime() - recordStartedAtMs).coerceAtLeast(0L)
            binding.tvRecordTime.text = getString(R.string.voice_clone_record_time, formatDuration(elapsed))
            if (elapsed >= MAX_RECORDING_MS) {
                stopRecording(true)
                return
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
        binding = ActivityVoiceCloneSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeProfile = profileStore.load()
        selectedAvatar = activeProfile?.toAvatarModel() ?: AvatarCatalog.models.first()

        avatarAdapter = AvatarAdapter(
            AvatarCatalog.models,
            object : AvatarAdapter.Callback {
                override fun onSelect(model: AvatarModel) {
                    selectedAvatar = model
                    avatarAdapter.setSelectedModel(model)
                    updateUi()
                }
            },
            enableSelection = true
        )

        binding.rvAvatars.layoutManager = GridLayoutManager(this, 2)
        binding.rvAvatars.adapter = avatarAdapter
        avatarAdapter.setSelectedModel(selectedAvatar)

        binding.btnPrimaryAction.setOnClickListener {
            when {
                isCreatingVoice -> Unit
                isRecording -> stopRecording(false)
                currentRecording != null -> createVoiceFromSample()
                activeProfile != null -> startVoiceCloneConversation()
                else -> requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }

        binding.btnSecondaryAction.setOnClickListener {
            if (isCreatingVoice || isRecording) {
                return@setOnClickListener
            }
            if (currentRecording != null) {
                currentRecording?.file?.delete()
                currentRecording = null
                updateUi()
            } else {
                requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }

        binding.tvRecordScript.text = getString(R.string.voice_clone_record_script)
        binding.tvRecordTime.text = getString(R.string.voice_clone_record_time, formatDuration(0L))
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(recordTicker)
        recorder.release()
    }

    override fun permissionsGet(get: Boolean, code: Int) {
        super.permissionsGet(get, code)
        if (code == REQUEST_RECORD_AUDIO) {
            if (get) {
                startRecording()
            } else {
                Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        val outputFile = File(filesDir, "voice_clone/sample_${System.currentTimeMillis()}.m4a")
        try {
            recorder.start(outputFile)
            currentRecording = null
            isRecording = true
            recordStartedAtMs = SystemClock.elapsedRealtime()
            binding.tvRecordTime.text = getString(R.string.voice_clone_record_time, formatDuration(0L))
            mainHandler.removeCallbacks(recordTicker)
            mainHandler.post(recordTicker)
            updateUi()
        } catch (e: Exception) {
            Toast.makeText(mContext, e.message ?: getString(R.string.voice_clone_record_failed), Toast.LENGTH_SHORT).show()
            recorder.release()
            isRecording = false
            updateUi()
        }
    }

    private fun stopRecording(autoStopped: Boolean) {
        if (!isRecording) {
            return
        }
        isRecording = false
        mainHandler.removeCallbacks(recordTicker)
        try {
            val recording = recorder.stop()
            if (recording.durationMs < MIN_RECORDING_MS) {
                recording.file.delete()
                currentRecording = null
                Toast.makeText(mContext, R.string.voice_clone_record_too_short, Toast.LENGTH_SHORT).show()
            } else {
                currentRecording = recording
                if (autoStopped) {
                    Toast.makeText(mContext, R.string.voice_clone_record_auto_stopped, Toast.LENGTH_SHORT).show()
                } else if (recording.durationMs < RECOMMENDED_RECORDING_MS) {
                    Toast.makeText(mContext, R.string.voice_clone_record_recommendation, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            currentRecording = null
            Toast.makeText(mContext, e.message ?: getString(R.string.voice_clone_record_failed), Toast.LENGTH_SHORT).show()
        }
        updateUi()
    }

    private fun createVoiceFromSample() {
        val recording = currentRecording ?: return
        val oldVoice = activeProfile?.voice
        isCreatingVoice = true
        updateUi()
        voiceCloneService.createVoice(
            sampleFile = recording.file,
            preferredName = "family_${selectedAvatar.voice}",
            targetModel = DashScopeConfig.VOICE_CLONE_TTS_MODEL
        ) { result ->
            runOnUiThread {
                isCreatingVoice = false
                result.onSuccess { cloneResult ->
                    val profile = VoiceCloneProfile(
                        voice = cloneResult.voice,
                        targetModel = cloneResult.targetModel,
                        preferredName = "family_${selectedAvatar.voice}",
                        samplePath = recording.file.absolutePath,
                        avatarName = selectedAvatar.name,
                        avatarVoice = selectedAvatar.voice,
                        avatarModelUrl = selectedAvatar.modelUrl,
                        avatarRes = selectedAvatar.avatarRes,
                        createdAt = System.currentTimeMillis()
                    )
                    profileStore.save(profile)
                    activeProfile = profile
                    currentRecording = null
                    if (!oldVoice.isNullOrBlank() && oldVoice != profile.voice) {
                        voiceCloneService.deleteVoice(oldVoice)
                    }
                    Toast.makeText(mContext, R.string.voice_clone_saved, Toast.LENGTH_SHORT).show()
                    updateUi()
                }.onFailure { throwable ->
                    Toast.makeText(mContext, throwable.message ?: getString(R.string.voice_clone_create_failed), Toast.LENGTH_SHORT).show()
                    updateUi()
                }
            }
        }
    }

    private fun startVoiceCloneConversation() {
        val profile = (activeProfile ?: return).copy(
            avatarName = selectedAvatar.name,
            avatarVoice = selectedAvatar.voice,
            avatarModelUrl = selectedAvatar.modelUrl,
            avatarRes = selectedAvatar.avatarRes
        )
        profileStore.save(profile)
        activeProfile = profile

        val intent = Intent(mContext, CallActivity::class.java)
        intent.putExtra("modelUrl", selectedAvatar.modelUrl)
        intent.putExtra("voice", selectedAvatar.voice)
        intent.putExtra("debug", debugSettingsStore.isChatDebugEnabled())
        intent.putExtra("cloneMode", true)
        intent.putExtra("clonedVoice", profile.voice)
        intent.putExtra("cloneTtsModel", profile.targetModel)
        startActivity(intent)
    }

    private fun updateUi() {
        val hasReadyProfile = activeProfile != null
        val hasSample = currentRecording != null

        binding.tvStatus.text = when {
            isCreatingVoice -> getString(R.string.voice_clone_status_creating)
            isRecording -> getString(R.string.voice_clone_status_recording, selectedAvatar.name)
            hasSample && hasReadyProfile -> getString(R.string.voice_clone_status_sample_replace, selectedAvatar.name)
            hasSample -> getString(R.string.voice_clone_status_sample_ready, selectedAvatar.name)
            hasReadyProfile -> getString(R.string.voice_clone_status_ready, selectedAvatar.name)
            else -> getString(R.string.voice_clone_status_idle, selectedAvatar.name)
        }

        binding.btnPrimaryAction.text = when {
            isCreatingVoice -> getString(R.string.voice_clone_primary_creating)
            isRecording -> getString(R.string.voice_clone_primary_stop_record)
            hasSample -> getString(R.string.voice_clone_primary_create)
            hasReadyProfile -> getString(R.string.voice_clone_primary_start_chat)
            else -> getString(R.string.voice_clone_primary_start_record)
        }

        binding.btnPrimaryAction.isEnabled = !isCreatingVoice
        binding.btnSecondaryAction.visibility = if (isCreatingVoice || isRecording) View.INVISIBLE else View.VISIBLE
        binding.btnSecondaryAction.text = if (hasSample) {
            getString(R.string.voice_clone_secondary_cancel_sample)
        } else {
            getString(R.string.voice_clone_secondary_rerecord)
        }
        binding.tvRecordHint.text = getString(R.string.voice_clone_record_hint)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 11
        private const val MIN_RECORDING_MS = 3_000L
        private const val RECOMMENDED_RECORDING_MS = 10_000L
        private const val MAX_RECORDING_MS = 60_000L
    }
}
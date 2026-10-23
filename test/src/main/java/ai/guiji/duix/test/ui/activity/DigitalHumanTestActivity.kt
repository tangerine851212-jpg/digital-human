package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.VirtualModelUtil
import ai.guiji.duix.sdk.client.bean.ImageFrame
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.sdk.client.render.RenderSink
import ai.guiji.duix.test.R
import ai.guiji.duix.test.audio.AudioFileDecoder
import ai.guiji.duix.test.audio.AudioResampler
import ai.guiji.duix.test.databinding.ActivityDigitalHumanTestBinding
import ai.guiji.duix.test.model.AvatarCatalog
import ai.guiji.duix.test.model.AvatarModel
import ai.guiji.duix.test.model.BatchAudioJob
import ai.guiji.duix.test.realtime.OmniRealtimeClient
import ai.guiji.duix.test.service.DashScopeConfig
import ai.guiji.duix.test.service.LocalFileAudioStore
import ai.guiji.duix.test.service.VoiceCloneProfileStore
import ai.guiji.duix.test.ui.adapter.AvatarAdapter
import ai.guiji.duix.test.ui.adapter.DigitalHumanBatchJobAdapter
import ai.guiji.duix.test.ui.dialog.LoadingDialog
import ai.guiji.duix.test.video.DigitalHumanMp4Exporter
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DigitalHumanTestActivity : BaseActivity() {

    private lateinit var binding: ActivityDigitalHumanTestBinding
    private lateinit var avatarAdapter: AvatarAdapter
    private lateinit var batchJobAdapter: DigitalHumanBatchJobAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val profileStore by lazy { VoiceCloneProfileStore(this) }

    private var selectedAvatar: AvatarModel = AvatarCatalog.models.first()
    private var selectedFolderDir: File? = null
    private var selectedFolderName: String? = null
    private var selectedFolderPath: String? = null
    private var batchJobs = mutableListOf<BatchAudioJob>()
    private var outputRootName = DEFAULT_OUTPUT_ROOT

    private var duix: DUIX? = null
    private var duixRenderer: DUIXRenderer? = null
    private var omniClient: OmniRealtimeClient? = null
    private var exporter: DigitalHumanMp4Exporter? = null
    private var currentTempOutputFile: File? = null
    private var loadingDialog: LoadingDialog? = null
    private var lastProgress = -1

    private var currentJobIndex = -1
    private var isBatchRunning = false
    private var isScanningFolder = false
    private var isFinalizingExport = false
    private var hasStartedPushForResponse = false
    private var assistantAudioDone = false
    private var responseDone = false
    private var avatarPlaybackEnded = false
    private var receivedAssistantAudio = false
    private var currentJobAttemptId = 0L
    private var nextJobAttemptId = 0L
    private var activeConnectionId = 0L
    private var nextConnectionId = 0L

    private val assistantTextBuffer = StringBuilder()

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val selectedPath = result.data?.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH)
            ?: return@registerForActivityResult
        handleFolderSelected(File(selectedPath))
    }

    private val manageStorageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (hasLocalFolderAccess()) {
            launchLocalFolderPicker()
        } else {
            updateStatus(getString(R.string.digital_human_test_manage_storage_denied))
            Toast.makeText(this, R.string.digital_human_test_manage_storage_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            launchLocalFolderPicker()
        } else {
            updateStatus(getString(R.string.digital_human_test_legacy_storage_denied))
            Toast.makeText(this, R.string.digital_human_test_legacy_storage_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val renderSink = object : RenderSink {
        override fun onVideoFrame(imageFrame: ImageFrame) {
            duixRenderer?.onVideoFrame(imageFrame)
            exporter?.recordFrame(imageFrame)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()

        binding = ActivityDigitalHumanTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedAvatar = profileStore.load()?.toAvatarModel() ?: AvatarCatalog.models.first()
        outputRootName = buildOutputRootName()

        setupPreview()
        setupAvatarList()
        setupBatchList()
        setupActions()

        Glide.with(this).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

        clearCurrentText()
        updateFolderSummary()
        updateQueueSummary()
        updateStatus(getString(R.string.digital_human_test_status_idle, selectedAvatar.name))
        updatePreviewHint()
        updateUiState()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissLoadingDialog()
        isBatchRunning = false
        clearCurrentJobRuntime(cancelExporter = true)
        disconnectOmni()
        workerExecutor.shutdownNow()
        releaseDuix()
        duixRenderer?.release()
    }

    private fun setupPreview() {
        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.glTextureView.isOpaque = false
        duixRenderer = DUIXRenderer(this, binding.glTextureView)
        binding.glTextureView.setRenderer(duixRenderer)
        binding.glTextureView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private fun setupAvatarList() {
        avatarAdapter = AvatarAdapter(
            AvatarCatalog.models,
            object : AvatarAdapter.Callback {
                override fun onSelect(model: AvatarModel) {
                    if (isBatchRunning) {
                        return
                    }
                    selectedAvatar = model
                    outputRootName = buildOutputRootName()
                    avatarAdapter.setSelectedModel(model)
                    rebuildBatchJobsForSelectedOutput()
                    updateFolderSummary()
                    updateQueueSummary()
                    updateStatus(buildReadyStatusText())
                    updatePreviewHint()
                }
            },
            enableSelection = true,
        )

        binding.rvAvatars.layoutManager = GridLayoutManager(this, 2)
        binding.rvAvatars.adapter = avatarAdapter
        avatarAdapter.setSelectedModel(selectedAvatar)
    }

    private fun setupBatchList() {
        batchJobAdapter = DigitalHumanBatchJobAdapter()
        binding.rvBatchJobs.layoutManager = LinearLayoutManager(this)
        binding.rvBatchJobs.adapter = batchJobAdapter
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSelectFolder.setOnClickListener {
            if (!isBatchRunning && !isScanningFolder) {
                openFolderPicker()
            }
        }
        binding.btnPrimaryAction.setOnClickListener {
            if (!isBatchRunning && !isScanningFolder) {
                startBatch()
            }
        }
    }

    private fun openFolderPicker() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasLocalFolderAccess() -> {
                launchLocalFolderPicker()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                requestManageStorageAccess()
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> {
                launchLocalFolderPicker()
            }
            hasLegacyStoragePermissions() -> {
                launchLocalFolderPicker()
            }
            else -> {
                storagePermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            }
        }
    }

    private fun launchLocalFolderPicker() {
        folderPickerLauncher.launch(
            FolderPickerActivity.createIntent(this, selectedFolderDir?.absolutePath),
        )
    }

    private fun requestManageStorageAccess() {
        updateStatus(getString(R.string.digital_human_test_need_storage_access))
        Toast.makeText(this, R.string.digital_human_test_need_storage_access, Toast.LENGTH_SHORT).show()
        val appSpecificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        val intent = if (appSpecificIntent.resolveActivity(packageManager) != null) {
            appSpecificIntent
        } else {
            fallbackIntent
        }
        manageStorageAccessLauncher.launch(intent)
    }

    private fun handleFolderSelected(directory: File) {
        val normalizedDirectory = runCatching { directory.canonicalFile }.getOrElse { directory }
        selectedFolderDir = normalizedDirectory
        selectedFolderName = null
        selectedFolderPath = normalizedDirectory.absolutePath
        outputRootName = buildOutputRootName()
        batchJobs.clear()
        batchJobAdapter.submitList(emptyList())
        currentJobIndex = -1
        clearCurrentText()
        updateQueueSummary()
        updateFolderSummary()
        updateStatus(getString(R.string.digital_human_test_status_scanning_folder))
        updateUiState()
        isScanningFolder = true
        showLoadingDialog(getString(R.string.digital_human_test_status_scanning_folder))

        workerExecutor.execute {
            runCatching {
                LocalFileAudioStore.scanAudioFiles(normalizedDirectory)
            }.onSuccess { scanResult ->
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        return@post
                    }
                    isScanningFolder = false
                    dismissLoadingDialog()
                    selectedFolderName = scanResult.rootName
                    selectedFolderPath = scanResult.rootDir.absolutePath
                    outputRootName = buildOutputRootName()
                    batchJobs = scanResult.files.map { buildPendingJob(it) }.toMutableList()
                    rebuildBatchJobsForSelectedOutput()
                    updateFolderSummary()
                    updateQueueSummary()
                    updateStatus(
                        if (batchJobs.isEmpty()) {
                            getString(R.string.digital_human_test_no_audio_found)
                        } else {
                            buildReadyStatusText()
                        },
                    )
                    updateUiState()
                    if (batchJobs.isEmpty()) {
                        Toast.makeText(this, R.string.digital_human_test_no_audio_found, Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { error ->
                mainHandler.post {
                    isScanningFolder = false
                    dismissLoadingDialog()
                    batchJobs.clear()
                    batchJobAdapter.submitList(emptyList())
                    updateFolderSummary()
                    updateQueueSummary()
                    updateStatus(safeErrorMessage(error))
                    updateUiState()
                    Toast.makeText(this, safeErrorMessage(error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildPendingJob(entry: LocalFileAudioStore.AudioEntry): BatchAudioJob {
        val outputRelativePath = LocalFileAudioStore.buildOutputRelativePath(
            outputRootName,
            entry.relativePath,
        )
        return BatchAudioJob(
            sourceUri = entry.uri,
            sourceRelativePath = entry.relativePath,
            displayName = entry.displayName,
            outputRelativePath = outputRelativePath,
            status = BatchAudioJob.Status.PENDING,
            statusText = getString(R.string.digital_human_test_job_pending),
            detailText = getString(R.string.digital_human_test_job_output, outputRelativePath),
        )
    }

    private fun rebuildBatchJobsForSelectedOutput() {
        outputRootName = buildOutputRootName()
        val folderDir = selectedFolderDir
        batchJobs = batchJobs.map { job ->
            val newOutputRelativePath = LocalFileAudioStore.buildOutputRelativePath(
                outputRootName,
                job.sourceRelativePath,
            )
            val existingOutput = folderDir?.let {
                LocalFileAudioStore.resolveOutputFile(it, outputRootName, job.sourceRelativePath)
            }
            val shouldSkip = existingOutput?.isFile == true
            job.copy(
                outputRelativePath = newOutputRelativePath,
                attemptCount = 0,
                status = if (shouldSkip) BatchAudioJob.Status.SKIPPED else BatchAudioJob.Status.PENDING,
                statusText = if (shouldSkip) {
                    getString(R.string.digital_human_test_job_skipped)
                } else {
                    getString(R.string.digital_human_test_job_pending)
                },
                detailText = getString(R.string.digital_human_test_job_output, newOutputRelativePath),
                outputUri = if (shouldSkip && existingOutput != null) Uri.fromFile(existingOutput) else null,
            )
        }.toMutableList()
        batchJobAdapter.submitList(batchJobs)
    }

    private fun startBatch() {
        if (selectedFolderDir == null) {
            Toast.makeText(this, R.string.digital_human_test_need_folder, Toast.LENGTH_SHORT).show()
            return
        }
        if (batchJobs.isEmpty()) {
            Toast.makeText(this, R.string.digital_human_test_no_audio_found, Toast.LENGTH_SHORT).show()
            return
        }

        rebuildBatchJobsForSelectedOutput()
        if (pendingPreviewJobCount() == 0) {
            updateQueueSummary()
            updateStatus(getString(R.string.digital_human_test_all_outputs_exist))
            updateUiState()
            Toast.makeText(this, R.string.digital_human_test_all_outputs_exist, Toast.LENGTH_SHORT).show()
            return
        }

        isBatchRunning = true
        currentJobIndex = -1
        clearCurrentText()
        updateQueueSummary()
        updateStatus(getString(R.string.digital_human_test_status_preparing_resources, selectedAvatar.name))
        updatePreviewHint()
        updateUiState()

        ensureAvatarResources {
            initDuixAndStartBatch()
        }
    }

    private fun ensureAvatarResources(onReady: () -> Unit) {
        if (VirtualModelUtil.checkBaseConfig(this)) {
            ensureAvatarModel(onReady)
        } else {
            downloadBaseConfig {
                ensureAvatarModel(onReady)
            }
        }
    }

    private fun ensureAvatarModel(onReady: () -> Unit) {
        if (VirtualModelUtil.checkModel(this, selectedAvatar.modelUrl)) {
            onReady()
        } else {
            downloadAvatarModel(onReady)
        }
    }

    private fun downloadBaseConfig(onReady: () -> Unit) {
        showLoadingDialog(getString(R.string.digital_human_test_status_preparing_resources, selectedAvatar.name))
        VirtualModelUtil.baseConfigDownload(this, BASE_CONFIG_URL, object : VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                updateLoadingProgress(current, total) { progress ->
                    "基础资源下载中(${progress}%)"
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                updateLoadingProgress(current, total) { progress ->
                    "基础资源解压中(${progress}%)"
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    dismissLoadingDialog()
                    if (isBatchRunning) {
                        onReady()
                    }
                }
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                mainHandler.post {
                    stopBatchWithError(msg ?: getString(R.string.ai_call_error_retry))
                }
            }
        })
    }

    private fun downloadAvatarModel(onReady: () -> Unit) {
        showLoadingDialog(getString(R.string.digital_human_test_status_preparing_resources, selectedAvatar.name))
        VirtualModelUtil.modelDownload(this, selectedAvatar.modelUrl, object : VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                updateLoadingProgress(current, total) { progress ->
                    "${selectedAvatar.name} 下载中(${progress}%)"
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                updateLoadingProgress(current, total) { progress ->
                    "${selectedAvatar.name} 解压中(${progress}%)"
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    dismissLoadingDialog()
                    if (isBatchRunning) {
                        onReady()
                    }
                }
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                mainHandler.post {
                    stopBatchWithError(msg ?: getString(R.string.ai_call_error_retry))
                }
            }
        })
    }

    private fun initDuixAndStartBatch() {
        updateStatus(getString(R.string.digital_human_test_status_loading_avatar, selectedAvatar.name))
        updatePreviewHint()
        releaseDuix()

        duix = DUIX(this, selectedAvatar.modelUrl, renderSink) { event, msg, _ ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    mainHandler.post {
                        if (isBatchRunning && !isFinishing) {
                            startNextJob()
                        }
                    }
                }

                Constant.CALLBACK_EVENT_INIT_ERROR,
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    mainHandler.post {
                        stopBatchWithError(msg.ifBlank { getString(R.string.ai_call_error_retry) })
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    mainHandler.post {
                        avatarPlaybackEnded = true
                        if (isBatchRunning && assistantAudioDone && responseDone) {
                            finalizeCurrentJob()
                        }
                    }
                }
            }
        }
        duix?.init()
    }

    private fun startNextJob() {
        if (!isBatchRunning) {
            return
        }
        var nextIndex = currentJobIndex + 1
        while (nextIndex < batchJobs.size && batchJobs[nextIndex].status != BatchAudioJob.Status.PENDING) {
            nextIndex += 1
        }
        if (nextIndex >= batchJobs.size) {
            finishBatch()
            return
        }

        currentJobIndex = nextIndex
        prepareCurrentJob()
    }

    private fun prepareCurrentJob() {
        val job = currentJobOrNull() ?: return
        val folderDir = selectedFolderDir
        if (folderDir != null) {
            val existingOutput = LocalFileAudioStore.resolveOutputFile(folderDir, outputRootName, job.sourceRelativePath)
            if (existingOutput.isFile) {
                completeCurrentJobAsSkipped(existingOutput)
                return
            }
        }

        currentJobAttemptId = ++nextJobAttemptId
        assistantAudioDone = false
        responseDone = false
        avatarPlaybackEnded = false
        receivedAssistantAudio = false
        isFinalizingExport = false
        hasStartedPushForResponse = false
        clearCurrentText()
        duix?.stopAudio()

        currentTempOutputFile = createTempOutputFile(job)
        exporter = DigitalHumanMp4Exporter(currentTempOutputFile!!)

        updateCurrentJob(
            job.copy(
                status = BatchAudioJob.Status.RUNNING,
                statusText = getString(R.string.digital_human_test_job_decoding),
                detailText = getString(R.string.digital_human_test_job_output, job.outputRelativePath),
            ),
        )
        updateQueueSummary()
        updateStatus(
            getString(
                R.string.digital_human_test_status_batch_current,
                currentJobIndex + 1,
                batchJobs.size,
                job.sourceRelativePath,
            ),
        )
        updatePreviewHint()
        binding.rvBatchJobs.post {
            binding.rvBatchJobs.smoothScrollToPosition(currentJobIndex)
        }
        decodeCurrentAudioAndConnect(job, currentJobAttemptId)
    }

    private fun decodeCurrentAudioAndConnect(job: BatchAudioJob, attemptId: Long) {
        workerExecutor.execute {
            runCatching {
                AudioFileDecoder.decodeToPcm16Mono16k(applicationContext, job.sourceUri)
            }.onSuccess { decodedAudio ->
                mainHandler.post {
                    if (!isCurrentAttempt(attemptId) || currentJobOrNull()?.sourceUri != job.sourceUri || isFinishing) {
                        return@post
                    }
                    connectOmniAndSend(decodedAudio, attemptId)
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (!isCurrentAttempt(attemptId)) {
                        return@post
                    }
                    handleCurrentJobFailure(safeErrorMessage(error), allowRetry = false)
                }
            }
        }
    }

    private fun connectOmniAndSend(decodedAudio: AudioFileDecoder.DecodedAudio, attemptId: Long) {
        val currentJob = currentJobOrNull() ?: return
        updateCurrentJob(
            currentJob.copy(
                status = BatchAudioJob.Status.RUNNING,
                statusText = getString(R.string.digital_human_test_job_connecting),
            ),
        )
        assistantTextBuffer.clear()
        val connectionId = ++nextConnectionId
        lateinit var client: OmniRealtimeClient
        client = OmniRealtimeClient(
            OmniRealtimeClient.SessionOptions(
                DashScopeConfig.DEFAULT_API_KEY,
                DashScopeConfig.REALTIME_BASE_URL,
                OmniRealtimeClient.DEFAULT_MODEL,
                selectedAvatar.voice,
                OmniRealtimeClient.getDefaultInstructions(),
                false,
                false,
                true,
                OmniRealtimeClient.SessionOptions.OutputMode.AUDIO_AND_TEXT,
            ),
            object : OmniRealtimeClient.Callback {
                override fun onConnected() {
                    if (!isActiveConnection(attemptId, connectionId)) {
                        return
                    }
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        val job = currentJobOrNull() ?: return@post
                        updateCurrentJob(
                            job.copy(
                                status = BatchAudioJob.Status.RUNNING,
                                statusText = getString(R.string.digital_human_test_job_uploading),
                            ),
                        )
                    }
                    workerExecutor.execute {
                        runCatching {
                            sendDecodedAudio(client, decodedAudio, attemptId, connectionId)
                        }.onFailure { error ->
                            mainHandler.post {
                                if (!isActiveConnection(attemptId, connectionId)) {
                                    return@post
                                }
                                handleCurrentJobFailure(safeErrorMessage(error), allowRetry = true)
                            }
                        }
                    }
                }

                override fun onDisconnected() {
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        if (isFinalizingExport) {
                            return@post
                        }
                        if (isBatchRunning && !isFinishing) {
                            handleCurrentJobFailure(
                                getString(R.string.digital_human_test_connection_lost),
                                allowRetry = true,
                            )
                        }
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        if (isFinalizingExport) {
                            return@post
                        }
                        handleCurrentJobFailure(
                            message.ifBlank { getString(R.string.ai_call_error_retry) },
                            allowRetry = true,
                        )
                    }
                }

                override fun onUserSpeechStarted() = Unit

                override fun onUserSpeechStopped() = Unit

                override fun onUserTranscript(transcript: String) {
                    if (!isActiveConnection(attemptId, connectionId)) {
                        return
                    }
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        updateInputTranscript(transcript)
                    }
                }

                override fun onAssistantTextDelta(delta: String) {
                    if (!isActiveConnection(attemptId, connectionId)) {
                        return
                    }
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        assistantTextBuffer.append(delta)
                        binding.tvSubtitle.visibility = View.VISIBLE
                        binding.tvSubtitle.text = assistantTextBuffer.toString()
                    }
                }

                override fun onAssistantTranscriptDone(transcript: String) {
                    if (!isActiveConnection(attemptId, connectionId)) {
                        return
                    }
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        assistantTextBuffer.clear()
                        assistantTextBuffer.append(transcript)
                        binding.tvSubtitle.visibility = View.VISIBLE
                        binding.tvSubtitle.text = transcript
                    }
                }

                override fun onToolCallRequested(toolName: String, callId: String, argumentsJson: String) = Unit

                override fun onAssistantAudioDelta(pcmData: ByteArray) {
                    if (!isActiveConnection(attemptId, connectionId)) {
                        return
                    }
                    receivedAssistantAudio = true
                    exporter?.recordAudioChunk(pcmData)
                    if (!hasStartedPushForResponse) {
                        hasStartedPushForResponse = true
                        duix?.startPush()
                    }
                    val resampled = AudioResampler.resample24kTo16k(pcmData)
                    if (resampled.isNotEmpty()) {
                        duix?.pushPcm(resampled)
                    }
                    mainHandler.post {
                        val job = currentJobOrNull() ?: return@post
                        updateCurrentJob(
                            job.copy(
                                status = BatchAudioJob.Status.RUNNING,
                                statusText = getString(R.string.digital_human_test_job_generating),
                            ),
                        )
                        updatePreviewHint()
                    }
                }

                override fun onAssistantAudioDone() {
                    if (!isActiveConnection(attemptId, connectionId)) {
                        return
                    }
                    assistantAudioDone = true
                    if (hasStartedPushForResponse) {
                        duix?.stopPush()
                        hasStartedPushForResponse = false
                    }
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        if (responseDone && avatarPlaybackEnded) {
                            finalizeCurrentJob()
                        }
                    }
                }

                override fun onResponseDone() {
                    mainHandler.post {
                        if (!isActiveConnection(attemptId, connectionId)) {
                            return@post
                        }
                        responseDone = true
                        if (!receivedAssistantAudio) {
                            handleCurrentJobFailure(
                                getString(R.string.digital_human_test_no_audio_response),
                                allowRetry = true,
                            )
                            return@post
                        }
                        val job = currentJobOrNull() ?: return@post
                        updateCurrentJob(
                            job.copy(
                                status = BatchAudioJob.Status.RUNNING,
                                statusText = getString(R.string.digital_human_test_job_muxing),
                            ),
                        )
                        if (assistantAudioDone && avatarPlaybackEnded) {
                            finalizeCurrentJob()
                        }
                    }
                }
            },
        )
        activeConnectionId = connectionId
        omniClient = client
        client.connect()
    }

    private fun sendDecodedAudio(
        client: OmniRealtimeClient,
        decodedAudio: AudioFileDecoder.DecodedAudio,
        attemptId: Long,
        connectionId: Long,
    ) {
        if (!isActiveConnection(attemptId, connectionId)) {
            return
        }
        var offset = 0
        while (offset < decodedAudio.pcm16Mono16k.size) {
            if (!isActiveConnection(attemptId, connectionId)) {
                return
            }
            val end = (offset + OMNI_INPUT_CHUNK_BYTES).coerceAtMost(decodedAudio.pcm16Mono16k.size)
            client.appendAudio(decodedAudio.pcm16Mono16k.copyOfRange(offset, end))
            offset = end
        }
        if (!isActiveConnection(attemptId, connectionId)) {
            return
        }
        val committed = client.commitInputAudio()
        val requested = committed && client.requestResponse()
        if (!requested) {
            throw IllegalStateException(getString(R.string.ai_call_error_retry))
        }
    }

    private fun finalizeCurrentJob() {
        if (!isBatchRunning || isFinalizingExport) {
            return
        }
        val folderDir = selectedFolderDir ?: run {
            stopBatchWithError(getString(R.string.digital_human_test_need_folder))
            return
        }
        val activeExporter = exporter ?: run {
            handleCurrentJobFailure(getString(R.string.digital_human_test_no_audio_response), allowRetry = true)
            return
        }
        val currentJob = currentJobOrNull() ?: return
        val attemptId = currentJobAttemptId
        isFinalizingExport = true

        updateCurrentJob(
            currentJob.copy(
                status = BatchAudioJob.Status.RUNNING,
                statusText = getString(R.string.digital_human_test_job_muxing),
            ),
        )

        workerExecutor.execute {
            var localOutputFile: File? = null
            runCatching {
                val exportResult = activeExporter.finish()
                localOutputFile = exportResult.file
                if (!isCurrentAttempt(attemptId)) {
                    return@runCatching LocalFileAudioStore.WriteResult(exportResult.file, skipped = true)
                }
                mainHandler.post {
                    if (!isCurrentAttempt(attemptId)) {
                        return@post
                    }
                    val job = currentJobOrNull() ?: return@post
                    updateCurrentJob(
                        job.copy(
                            status = BatchAudioJob.Status.RUNNING,
                            statusText = getString(R.string.digital_human_test_job_copying),
                        ),
                    )
                    updateStatus(getString(R.string.digital_human_test_status_copying))
                }
                LocalFileAudioStore.writeVideoResult(
                    folderDir,
                    outputRootName,
                    currentJob.sourceRelativePath,
                    exportResult.file,
                )
            }.onSuccess { writeResult ->
                localOutputFile?.delete()
                mainHandler.post {
                    if (!isCurrentAttempt(attemptId)) {
                        return@post
                    }
                    if (writeResult.skipped) {
                        completeCurrentJobAsSkipped(writeResult.file)
                    } else {
                        completeCurrentJob(Uri.fromFile(writeResult.file))
                    }
                }
            }.onFailure { error ->
                localOutputFile?.delete()
                mainHandler.post {
                    if (!isCurrentAttempt(attemptId)) {
                        return@post
                    }
                    handleCurrentJobFailure(safeErrorMessage(error), allowRetry = false)
                }
            }
        }
    }

    private fun completeCurrentJob(outputUri: Uri) {
        val job = currentJobOrNull() ?: return
        clearCurrentJobRuntime(cancelExporter = false)
        updateCurrentJob(
            job.copy(
                status = BatchAudioJob.Status.COMPLETED,
                statusText = getString(R.string.digital_human_test_job_done),
                detailText = getString(R.string.digital_human_test_job_output, job.outputRelativePath),
                outputUri = outputUri,
            ),
        )
        updateQueueSummary()
        startNextJob()
    }

    private fun completeCurrentJobAsSkipped(outputFile: File) {
        val job = currentJobOrNull() ?: return
        clearCurrentJobRuntime(cancelExporter = false)
        updateCurrentJob(
            job.copy(
                status = BatchAudioJob.Status.SKIPPED,
                statusText = getString(R.string.digital_human_test_job_skipped),
                detailText = getString(R.string.digital_human_test_job_output, job.outputRelativePath),
                outputUri = Uri.fromFile(outputFile),
            ),
        )
        updateQueueSummary()
        startNextJob()
    }

    private fun handleCurrentJobFailure(message: String, allowRetry: Boolean) {
        if (!isBatchRunning) {
            return
        }
        val job = currentJobOrNull()
        if (job != null && allowRetry && job.attemptCount < MAX_RETRY_COUNT) {
            val nextRetryAttempt = job.attemptCount + 1
            clearCurrentJobRuntime(cancelExporter = true)
            updateCurrentJob(
                job.copy(
                    attemptCount = nextRetryAttempt,
                    status = BatchAudioJob.Status.RUNNING,
                    statusText = getString(
                        R.string.digital_human_test_job_retrying,
                        nextRetryAttempt,
                        MAX_RETRY_COUNT,
                    ),
                    detailText = message,
                ),
            )
            updateQueueSummary()
            updateStatus(
                getString(
                    R.string.digital_human_test_status_retrying,
                    nextRetryAttempt,
                    MAX_RETRY_COUNT,
                    job.sourceRelativePath,
                ),
            )
            mainHandler.postDelayed({
                if (!isBatchRunning) {
                    return@postDelayed
                }
                val pendingRetryJob = currentJobOrNull() ?: return@postDelayed
                if (pendingRetryJob.attemptCount != nextRetryAttempt || pendingRetryJob.status != BatchAudioJob.Status.RUNNING) {
                    return@postDelayed
                }
                prepareCurrentJob()
            }, RETRY_DELAY_MS)
            return
        }
        clearCurrentJobRuntime(cancelExporter = true)
        if (job != null) {
            updateCurrentJob(
                job.copy(
                    status = BatchAudioJob.Status.FAILED,
                    statusText = getString(R.string.digital_human_test_job_failed),
                    detailText = message,
                ),
            )
            updateQueueSummary()
        }
        startNextJob()
    }

    private fun finishBatch() {
        isBatchRunning = false
        currentJobIndex = -1
        updateQueueSummary()
        updateStatus(
            getString(
                R.string.digital_human_test_status_batch_done,
                completedJobCount(),
                skippedJobCount(),
                failedJobCount(),
            ),
        )
        updatePreviewHint()
        updateUiState()
    }

    private fun stopBatchWithError(message: String) {
        dismissLoadingDialog()
        isBatchRunning = false
        clearCurrentJobRuntime(cancelExporter = true)
        updateStatus(message)
        updatePreviewHint()
        updateUiState()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrentJobRuntime(cancelExporter: Boolean) {
        currentJobAttemptId = 0L
        disconnectOmni()
        if (hasStartedPushForResponse) {
            duix?.stopPush()
            hasStartedPushForResponse = false
        }
        duix?.stopAudio()
        if (cancelExporter) {
            runCatching { exporter?.cancel() }
        }
        exporter = null
        currentTempOutputFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        currentTempOutputFile = null
        isFinalizingExport = false
        assistantAudioDone = false
        responseDone = false
        avatarPlaybackEnded = false
        receivedAssistantAudio = false
        clearCurrentText()
    }

    private fun disconnectOmni() {
        activeConnectionId = 0L
        omniClient?.disconnect()
        omniClient = null
    }

    private fun releaseDuix() {
        duix?.stopAudio()
        duix?.release()
        duix = null
    }

    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun updateFolderSummary() {
        if (selectedFolderDir == null) {
            binding.tvFolderName.text = getString(R.string.digital_human_test_folder_none)
            binding.tvFolderMeta.text = getString(R.string.digital_human_test_folder_hint)
            return
        }

        binding.tvFolderName.text = selectedFolderName ?: selectedFolderPath ?: getString(R.string.digital_human_test_folder_none)
        binding.tvFolderMeta.text = if (batchJobs.isEmpty()) {
            if (isScanningFolder) {
                getString(R.string.digital_human_test_status_scanning_folder)
            } else {
                getString(R.string.digital_human_test_no_audio_found)
            }
        } else {
            getString(
                R.string.digital_human_test_folder_summary,
                batchJobs.size,
                selectedFolderPath ?: "",
                outputRootName,
            )
        }
    }

    private fun updateQueueSummary() {
        binding.tvProgressSummary.text = getString(
            R.string.digital_human_test_progress_summary,
            batchJobs.size,
            completedJobCount(),
            skippedJobCount(),
            failedJobCount(),
        )
        binding.tvProgressEmpty.visibility = if (batchJobs.isEmpty()) View.VISIBLE else View.GONE
        binding.rvBatchJobs.visibility = if (batchJobs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateInputTranscript(transcript: String?) {
        val visible = !transcript.isNullOrBlank()
        binding.tvInputTranscriptLabel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvInputTranscript.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvInputTranscript.text = transcript.orEmpty()
    }

    private fun clearCurrentText() {
        assistantTextBuffer.clear()
        binding.tvSubtitle.visibility = View.GONE
        binding.tvSubtitle.text = ""
        binding.tvInputTranscriptLabel.visibility = View.GONE
        binding.tvInputTranscript.visibility = View.GONE
        binding.tvInputTranscript.text = ""
    }

    private fun updatePreviewHint() {
        val showHint = !isBatchRunning || duix?.isReady() != true
        binding.tvPreviewHint.visibility = if (showHint) View.VISIBLE else View.GONE
        binding.tvPreviewHint.text = if (isBatchRunning) {
            getString(R.string.digital_human_test_preview_loading, selectedAvatar.name)
        } else {
            getString(R.string.digital_human_test_preview_idle, selectedAvatar.name)
        }
    }

    private fun updateUiState() {
        binding.btnPrimaryAction.text = if (isBatchRunning) {
            getString(R.string.digital_human_test_running)
        } else {
            getString(R.string.digital_human_test_generate)
        }
        binding.btnPrimaryAction.isEnabled = !isBatchRunning && !isScanningFolder && canStartBatch()
        binding.btnSelectFolder.isEnabled = !isBatchRunning && !isScanningFolder
        binding.layoutAvatarSection.visibility = if (isBatchRunning) View.GONE else View.VISIBLE
        binding.layoutProgressSection.visibility = if (shouldShowProgressSection()) View.VISIBLE else View.GONE
    }

    private fun shouldShowProgressSection(): Boolean {
        return isBatchRunning || batchJobs.any { it.status != BatchAudioJob.Status.PENDING }
    }

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null) {
            loadingDialog = LoadingDialog(this, message)
        }
        lastProgress = -1
        loadingDialog?.show()
        loadingDialog?.setContent(message)
    }

    private fun dismissLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
        lastProgress = -1
    }

    private fun updateLoadingProgress(current: Long, total: Long, messageBuilder: (Int) -> String) {
        if (total <= 0L) {
            return
        }
        val progress = ((current * 100L) / total).toInt()
        if (progress == lastProgress) {
            return
        }
        lastProgress = progress
        runOnUiThread {
            loadingDialog?.setContent(messageBuilder(progress))
        }
    }

    private fun updateCurrentJob(updatedJob: BatchAudioJob) {
        if (currentJobIndex !in batchJobs.indices) {
            return
        }
        batchJobs[currentJobIndex] = updatedJob
        batchJobAdapter.updateItem(currentJobIndex, updatedJob)
    }

    private fun currentJobOrNull(): BatchAudioJob? {
        return batchJobs.getOrNull(currentJobIndex)
    }

    private fun isCurrentAttempt(attemptId: Long): Boolean {
        return attemptId != 0L && currentJobAttemptId == attemptId && isBatchRunning
    }

    private fun isActiveConnection(attemptId: Long, connectionId: Long): Boolean {
        return isCurrentAttempt(attemptId) && activeConnectionId == connectionId
    }

    private fun hasLocalFolderAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun hasLegacyStoragePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return true
        }
        val permissions = arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun buildOutputRootName(): String {
        val normalizedVoice = selectedAvatar.voice
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return if (normalizedVoice.isBlank()) {
            DEFAULT_OUTPUT_ROOT
        } else {
            "${DEFAULT_OUTPUT_ROOT}_$normalizedVoice"
        }
    }

    private fun createTempOutputFile(job: BatchAudioJob): File {
        val tempDir = File(cacheDir, TEMP_OUTPUT_DIR_NAME).apply { mkdirs() }
        val safeName = sanitizeFileName(job.displayName)
        return File(tempDir, "${System.currentTimeMillis()}_${currentJobIndex + 1}_$safeName.mp4")
    }

    private fun sanitizeFileName(name: String): String {
        val baseName = name.substringBeforeLast('.', name)
        val sanitized = baseName.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_')
        return if (sanitized.isBlank()) "digital_human" else sanitized
    }

    private fun safeErrorMessage(error: Throwable): String {
        return error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.ai_call_error_retry)
    }

    private fun buildReadyStatusText(): String {
        return when {
            batchJobs.isEmpty() -> getString(R.string.digital_human_test_status_idle, selectedAvatar.name)
            pendingPreviewJobCount() == 0 -> getString(R.string.digital_human_test_all_outputs_exist)
            else -> getString(R.string.digital_human_test_status_batch_ready, pendingPreviewJobCount())
        }
    }

    private fun pendingPreviewJobCount(): Int {
        return batchJobs.count { it.status == BatchAudioJob.Status.PENDING }
    }

    private fun canStartBatch(): Boolean {
        val folderDir = selectedFolderDir ?: return false
        if (batchJobs.isEmpty()) {
            return false
        }
        return batchJobs.any { job ->
            !LocalFileAudioStore.resolveOutputFile(folderDir, outputRootName, job.sourceRelativePath).exists()
        }
    }

    private fun completedJobCount(): Int {
        return batchJobs.count { it.status == BatchAudioJob.Status.COMPLETED }
    }

    private fun skippedJobCount(): Int {
        return batchJobs.count { it.status == BatchAudioJob.Status.SKIPPED }
    }

    private fun failedJobCount(): Int {
        return batchJobs.count { it.status == BatchAudioJob.Status.FAILED }
    }

    companion object {
        private const val GL_CONTEXT_VERSION = 2
        private const val BASE_CONFIG_URL = "https://dl.xn--876a.net/models/gj_dh_res.zip"
        private const val OMNI_INPUT_CHUNK_BYTES = 3200
        private const val DEFAULT_OUTPUT_ROOT = "digital_human_output"
        private const val TEMP_OUTPUT_DIR_NAME = "digital_human_batch_temp"
        private const val MAX_RETRY_COUNT = 2
        private const val RETRY_DELAY_MS = 1500L
    }
}
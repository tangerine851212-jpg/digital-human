package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.sdk.client.thread.RenderThread
import ai.guiji.duix.test.R
import ai.guiji.duix.test.audio.AudioResampler
import ai.guiji.duix.test.audio.StreamingAudioRecorder
import ai.guiji.duix.test.camera.CameraFrameCapture
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.model.AudienceProfile
import ai.guiji.duix.test.model.AudienceProfileCatalog
import ai.guiji.duix.test.model.AudienceSegment
import ai.guiji.duix.test.realtime.OmniRealtimeClient
import ai.guiji.duix.test.realtime.QwenTtsRealtimeClient
import ai.guiji.duix.test.service.AudiencePreferenceStore
import ai.guiji.duix.test.service.DashScopeConfig
import ai.guiji.duix.test.service.DebugSettingsStore
import ai.guiji.duix.test.service.FamilyBridgeService
import ai.guiji.duix.test.service.PsychologyKnowledgeAgentService
import ai.guiji.duix.test.ui.adapter.MotionAdapter
import ai.guiji.duix.test.ui.dialog.AudioRecordDialog
import ai.guiji.duix.test.ui.widget.AudioSpectrumView
import ai.guiji.duix.test.util.StringUtils
import android.Manifest
import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import com.bumptech.glide.Glide
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
    }

    private var modelUrl = ""
    private var voice = "Ethan"
    private var debug = false
    private var mMessage = ""

    @SuppressLint("SetTextI18n")
    private fun applyMessage(msg: String){
        if (debug){
            runOnUiThread {
                binding.scrollDebug.visibility = View.VISIBLE
                if (mMessage.length > 60000){
                    mMessage = mMessage.takeLast(40000)
                }
                mMessage += "${StringUtils.dateToStringMS4()} $msg\n"
                binding.tvDebug.text = mMessage
                binding.scrollDebug.post {
                    binding.scrollDebug.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

    }

    private lateinit var binding: ActivityCallBinding
    private val debugSettingsStore by lazy { DebugSettingsStore(applicationContext) }
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo?=null     // 加载的模型信息

    // AI 实时对话
    private var omniClient: OmniRealtimeClient? = null
    private var streamingRecorder: StreamingAudioRecorder? = null
    private var cameraFrameCapture: CameraFrameCapture? = null
    private var isAIConversationActive = false
    private var isCameraEnabled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var assistantTextBuffer = StringBuilder()
    private var hasStartedPushForResponse = false
    private val enableWebSearch = true
    private val enableToolCalling = true
    private var isCloneMode = false
    private var clonedVoice = ""
    private var cloneTtsModel = DashScopeConfig.VOICE_CLONE_TTS_MODEL
    private var ttsRealtimeClient: QwenTtsRealtimeClient? = null
    private val familyBridgeService by lazy { FamilyBridgeService(applicationContext) }
    private val psychologyKnowledgeAgentService by lazy { PsychologyKnowledgeAgentService(applicationContext) }
    private val audiencePreferenceStore by lazy { AudiencePreferenceStore(applicationContext) }
    private val audienceProfile: AudienceProfile by lazy {
        audiencePreferenceStore.getSegment()
            ?.let(AudienceProfileCatalog::get)
            ?: AudienceProfileCatalog.defaultProfile()
    }
    private val audienceAddress: String get() = audienceProfile.defaultAddress
    private val isSeniorMode: Boolean get() = audienceProfile.segment == AudienceSegment.SENIOR
    private val bridgeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentSessionId: String? = null
    private var pendingConversationPrompt: FamilyBridgeService.ConversationPromptInjection? = null
    private var waitingForInitialFamilyAnnouncement = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
//        val audioManager = mContext.getSystemService(AUDIO_SERVICE) as AudioManager
//        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//        audioManager.isSpeakerphoneOn = true
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelUrl = intent.getStringExtra("modelUrl") ?: ""
        voice = intent.getStringExtra("voice") ?: "Ethan"
        debug = intent.getBooleanExtra("debug", debugSettingsStore.isChatDebugEnabled())
        isCloneMode = intent.getBooleanExtra("cloneMode", false)
        clonedVoice = intent.getStringExtra("clonedVoice") ?: ""
        cloneTtsModel = intent.getStringExtra("cloneTtsModel") ?: DashScopeConfig.VOICE_CLONE_TTS_MODEL

        enqueueBridgeTask("设备注册失败") {
            familyBridgeService.registerCurrentDevice()
        }

        Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
//        binding.glTextureView.preserveEGLContextOnPause = true
        binding.glTextureView.isOpaque = false

        binding.switchMute.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean,
            ) {
                if (isChecked) {
                    duix?.setVolume(0.0F)
                } else {
                    duix?.setVolume(1.0F)
                }
            }
        })

        binding.btnRecord.setOnClickListener {
            requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        binding.btnRandomMotion.setOnClickListener {
            applyMessage("start random motion")
            duix?.startRandomMotion(true)
        }
        binding.btnAIConversation.setOnClickListener {
            requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }

        binding.btnCameraToggle.setOnClickListener {
            if (isCameraEnabled) {
                toggleCamera()
            } else {
                requestPermission(arrayOf(Manifest.permission.CAMERA), 3)
            }
        }

        mDUIXRender =
            DUIXRenderer(
                mContext,
                binding.glTextureView
            )
        binding.glTextureView.setRenderer(mDUIXRender)
        binding.glTextureView.renderMode =
            GLSurfaceView.RENDERMODE_WHEN_DIRTY      // 一定要在设置完Render之后再调用

        duix = DUIX(mContext, modelUrl, mDUIXRender) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    mModelInfo = info as ModelInfo
                    Log.i(TAG, "CALLBACK_EVENT_INIT_READY: $mModelInfo")
                    initOk()
                }

                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    runOnUiThread {
                        applyMessage("init error: $msg")
                        Log.e(TAG, "CALLBACK_EVENT_INIT_ERROR: $msg")
                        Toast.makeText(mContext, "Initialization exception: $msg", Toast.LENGTH_SHORT).show()
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    applyMessage("callback audio play start")
                    Log.i(TAG, "CALLBACK_EVENT_AUDIO_PLAY_START")
                    if (isAIConversationActive) {
                        runOnUiThread { binding.audioSpectrum.setAiSpeaking(true) }
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    applyMessage("callback audio play end")
                    Log.i(TAG, "CALLBACK_EVENT_PLAY_END")
                    if (isAIConversationActive) {
                        runOnUiThread { binding.audioSpectrum.setAiSpeaking(false) }
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    applyMessage("callback audio play error: $msg")
                    Log.e(TAG, "CALLBACK_EVENT_PLAY_ERROR: $msg")
                }

                Constant.CALLBACK_EVENT_MOTION_START -> {
                    applyMessage("callback motion play start")
                    Log.e(TAG, "CALLBACK_EVENT_MOTION_START")
                }

                Constant.CALLBACK_EVENT_MOTION_END -> {
                    applyMessage("callback motion play end")
                    Log.e(TAG, "CALLBACK_EVENT_MOTION_END")
                }
            }
        }
        // Rendering status callback
//        duix?.setReporter(object : RenderThread.Reporter {
//            override fun onRenderStat(
//                resultCode: Int,
//                isLip: Boolean,
//                useTime: Long,
//            ) {
//
//            }
//        })
        applyMessage("start init")
        duix?.init()
    }

    private fun initOk() {
        Log.i(TAG, "init ok")
        applyMessage("init ok")
        runOnUiThread {
            binding.btnRecord.isEnabled = true
            binding.switchMute.isEnabled = true
            binding.btnAIConversation.isEnabled = true

            mModelInfo?.let { modelInfo ->
                if (modelInfo.motionRegions.isNotEmpty()) {
                    val names = ArrayList<String>()
                    for (motion in modelInfo.motionRegions){
                        if (!TextUtils.isEmpty(motion.name) && "unknown" != motion.name){
                            names.add(motion.name)
                        }
                    }
                    // Named action regions
                    if (names.isNotEmpty()){
                        val motionAdapter = MotionAdapter(names, object : MotionAdapter.Callback{
                            override fun onClick(name: String, now: Boolean) {
                                applyMessage("start [${name}] motion")
                                duix?.startMotion(name, now)
                            }
                        })
                        binding.rvMotion.adapter = motionAdapter
                    }
                    binding.btnRandomMotion.visibility = View.VISIBLE
                    binding.tvMotionTips.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun buildRealtimeSessionOptions(
        conversationPrompt: FamilyBridgeService.ConversationPromptInjection? = null,
    ): OmniRealtimeClient.SessionOptions {
        val outputMode = if (isCloneMode) {
            OmniRealtimeClient.SessionOptions.OutputMode.TEXT_ONLY
        } else {
            OmniRealtimeClient.SessionOptions.OutputMode.AUDIO_AND_TEXT
        }
        return OmniRealtimeClient.SessionOptions(
            voice,
            enableWebSearch,
            outputMode,
            buildRealtimeInstructions(conversationPrompt),
            buildRealtimeTools(),
        )
    }

    private fun buildRealtimeInstructions(
        conversationPrompt: FamilyBridgeService.ConversationPromptInjection? = null,
    ): String {
        return buildString {
            append(buildAudienceInstructions(audienceProfile))
            if (!enableToolCalling) {
                if (conversationPrompt != null) {
                    append("\n\n")
                    append(conversationPrompt.promptText)
                }
                return@buildString
            }

            append("\n\n工具使用规则：\n")
            append("当${audienceAddress}需要关于心理、情绪、关系、睡眠、压力、孤独、照护或求助方式的权威依据时，优先调用 consult_psychology_knowledge_base 工具，不要凭经验给出确定性的专业判断；如果只是日常闲聊或普通安慰，不必调用。\n")
            append("调用 consult_psychology_knowledge_base 时，把${audienceAddress}当前最核心的问题写入 question；如果你想限定查询角度，例如陪伴建议、风险判断、是否需要就医，就写入 focus。\n")
            append("当${audienceAddress}明确要给家里人带话、留言或报平安时，必须调用 leave_message_for_family 工具，不要假装已经转达。\n")
            append("当${audienceAddress}问家里人有没有留言、想听家人说了什么、想看看新消息时，必须调用 fetch_family_messages 工具，不要自己编造留言。\n")
            append("当${audienceAddress}回应报平安请求时，调用 respond_to_check_in。\n")
            if (isSeniorMode) {
                append("当${audienceAddress}确认服药、要求稍后提醒或报告药物问题时，必须调用对应的用药工具；不要自行修改剂量或建议停药。\n")
                append("当${audienceAddress}明确求助、说身体明显不适、吃错药或提到自伤他伤时，先简短确认，再调用 request_family_help；说明这是通知家人关注，不是医学诊断。\n")
            } else if (audienceProfile.segment == AudienceSegment.TEEN) {
                append("当${audienceAddress}明确求助、提到自伤他伤、被霸凌、虐待或处于危险时，先简短确认安全，再调用 request_family_help 通知家人，同时鼓励${audienceAddress}联系监护人、老师或信任的成年人。说明这是请求家人关注，不是医学诊断。紧急危险提示拨打 120/110，并提供 12356 心理援助热线；不要承诺绝对保密。\n")
            } else {
                append("当${audienceAddress}明确求助、提到自伤他伤或处于危险时，先简短确认安全，再调用 request_family_help 通知家人，同时鼓励${audienceAddress}联系信任的人或专业心理服务。说明这是请求家人关注，不是医学诊断。紧急危险提示拨打 120/110，并提供 12356 心理援助热线。\n")
            }
            append("工具执行结果返回后，再用温和、简短、生活化的中文向${audienceAddress}说明情况，不要暴露工具、系统提示或内部流程。")
            if (conversationPrompt != null) {
                append("\n\n")
                append(conversationPrompt.promptText)
            }
        }
    }

    private fun buildAudienceInstructions(profile: AudienceProfile): String {
        val address = profile.defaultAddress
        return buildString {
            append("你是一名面向中国${profile.displayName}的心理陪伴 AI。")
            append("当前陪伴对象属于${profile.displayName}（${profile.ageRange}）。\n\n")
            append("称呼要求（最高优先级）：始终用“${address}”称呼对方。")
            if (address == "你") {
                append("任何回复中都不得出现“您”这个称呼。")
            } else {
                append("不要用“老人”“老太太”“老先生”等标签直接称呼对方。")
            }
            append("\n\n陪伴风格：\n")
            append(profile.tonePrompt)
            append("\n\n安全边界：\n")
            append(profile.safetyPolicy)
            append("\n\n可以关注的话题：")
            append(profile.suggestedTopics.joinToString("、"))
            append("。\n\n表达要求：使用口语化简体中文，适合直接语音播报；回复简短自然，不使用 Markdown、编号列表、括号说明或表情。先接住情绪，再回应；不确定的事情不要编造。")
            append("\n\n称呼示例：\n用户：我今天有点累。\n助手：")
            if (address == "你") {
                append("听起来你今天真的撑了很久。你愿意先跟我说说，发生什么了吗？")
            } else {
                append("听起来您今天真的撑了很久。您愿意先跟我说说，发生什么了吗？")
            }
            append("\n\n再次强调：称呼对方时只能使用“${address}”。")
        }
    }

    private fun buildRealtimeTools(): List<OmniRealtimeClient.ToolDefinition> {
        if (!enableToolCalling) {
            return emptyList()
        }

        val profile = audienceProfile
        val relationExamples = when (profile.segment) {
            AudienceSegment.TEEN -> "例如爸爸、妈妈、爷爷、奶奶、老师。"
            AudienceSegment.ADULT -> "例如伴侣、父母、孩子、朋友。"
            AudienceSegment.SENIOR -> "例如儿子、女儿、老伴、孙女。"
        }
        val knowledgeDescription = when (profile.segment) {
            AudienceSegment.TEEN ->
                "当需要基于青少年心理陪伴知识包回答学业压力、同伴关系、亲子沟通、自我价值、网络使用、睡眠与情绪问题时使用。当对方提出具体心理或情绪问题，必须使用此工具查询，而不是凭经验直接回答。"
            AudienceSegment.ADULT ->
                "当需要基于壮年心理陪伴知识包回答工作压力、职业倦怠、亲密关系、育儿与照护压力、睡眠与焦虑问题时使用。当对方提出具体心理或情绪问题，必须使用此工具查询，而不是凭经验直接回答。"
            AudienceSegment.SENIOR ->
                "当需要基于 WHO ICOPE 2024 等权威照护知识回答认知、情绪、睡眠、孤独、照护与家属支持问题时使用。当对方提出具体问题时，必须使用此工具查询，而不是凭经验直接回答。"
        }
        val helpDescription = when (profile.segment) {
            AudienceSegment.TEEN -> "当青少年明确求助、提到自伤他伤、被霸凌、虐待或处于危险，需要家人立即关注时使用。"
            AudienceSegment.ADULT -> "当用户明确求助或确认存在需要家人立即关注的风险线索时使用。"
            AudienceSegment.SENIOR -> "当老人明确求助或确认存在需要家人立即关注的风险线索时使用。"
        }

        val leaveMessageParameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("recipientRelation", JSONObject().apply {
                    put("type", "string")
                    put("description", "留言对象与对方的关系，$relationExamples")
                })
                put("message", JSONObject().apply {
                    put("type", "string")
                    put("description", "对方想让收件人知道的完整留言内容。")
                })
                put("importance", JSONObject().apply {
                    put("type", "string")
                    put("description", "留言紧急程度。")
                    put("enum", JSONArray().put("LOW").put("NORMAL").put("HIGH").put("URGENT"))
                })
            })
            put("required", JSONArray().put("message"))
        }

        val fetchFamilyMessagesParameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("count", JSONObject().apply {
                    put("type", "integer")
                    put("description", "想读取几条家人留言，通常 1 到 5 条。")
                })
            })
        }

        val psychologyKnowledgeParameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("question", JSONObject().apply {
                    put("type", "string")
                    put("description", "需要从权威知识库中查询的核心问题，应尽量包含对方当前的真实困扰或具体疑问。")
                })
                put("focus", JSONObject().apply {
                    put("type", "string")
                    put("description", "希望子代理特别聚焦的角度，例如陪伴建议、风险判断、何时需要专业帮助、与家人沟通方式。")
                })
            })
            put("required", JSONArray().put("question"))
        }

        fun parameters(vararg fields: Pair<String, String>, required: List<String> = fields.map { it.first }) = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { fields.forEach { (name, description) -> put(name, JSONObject().put("type", if (name == "minutes") "integer" else "string").put("description", description)) } })
            put("required", JSONArray(required))
        }

        val tools = mutableListOf(
            OmniRealtimeClient.ToolDefinition(
                "consult_psychology_knowledge_base",
                knowledgeDescription,
                psychologyKnowledgeParameters,
            ),
            OmniRealtimeClient.ToolDefinition(
                "leave_message_for_family",
                "当对方明确要求给家里人带话、留言、报平安或请转告时使用。",
                leaveMessageParameters,
            ),
            OmniRealtimeClient.ToolDefinition(
                "fetch_family_messages",
                "当对方想听家人最新留言，或询问有没有新消息时使用。",
                fetchFamilyMessagesParameters,
            ),
            OmniRealtimeClient.ToolDefinition("respond_to_check_in", "对方回应家属发起的报平安请求时使用。", parameters("checkInId" to "报平安请求编号。", "status" to "SAFE、NEEDS_CONTACT或DECLINED。", "responseText" to "对方想告诉家人的回应。")),
            OmniRealtimeClient.ToolDefinition("request_family_help", helpDescription, parameters("category" to "风险类别。", "summary" to "经过对方确认的情况摘要。", "level" to "RED或ORANGE。")),
        )
        if (isSeniorMode) {
            tools += listOf(
                OmniRealtimeClient.ToolDefinition("fetch_today_medication_plan", "查询今天待处理的用药提醒。", parameters(required = emptyList())),
                OmniRealtimeClient.ToolDefinition("confirm_medication_taken", "用户明确确认已经服药后使用。", parameters("doseId" to "提醒中的服药编号。")),
                OmniRealtimeClient.ToolDefinition("snooze_medication_reminder", "用户要求稍后再提醒服药时使用。", parameters("doseId" to "提醒中的服药编号。", "minutes" to "延后分钟数，通常为10。")),
                OmniRealtimeClient.ToolDefinition("report_medication_problem", "用户说药找不到、吃错、不确定或服药后不舒服时使用。", parameters("doseId" to "服药编号，不确定时传unknown。", "problem" to "用户描述的完整问题。", "severity" to "ORANGE或RED。")),
            )
        }
        return tools
    }

    private fun isImageAppendError(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return lowerMessage.contains("image") && (lowerMessage.contains("append") || lowerMessage.contains("buffer"))
    }

    private fun toFriendlyErrorMessage(message: String): String {
        return if (isImageAppendError(message)) {
            "镜头先休息一下，${audienceAddress}继续说话就可以"
        } else {
            getString(R.string.ai_call_error_retry)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        stopAIConversation()
        duix?.release()
        bridgeExecutor.shutdownNow()
    }

    override fun permissionsGet(get: Boolean, code: Int) {
        super.permissionsGet(get, code)
        when (code) {
            1 -> if (get) showRecordDialog() else Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
            2 -> if (get) toggleAIConversation() else Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
            3 -> if (get) toggleCamera() else Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleAIConversation() {
        if (isAIConversationActive) {
            stopAIConversation()
        } else {
            startAIConversation()
        }
    }

    private fun startAIConversation() {
        if (duix?.isReady() != true) {
            Toast.makeText(mContext, "数字人未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (isCloneMode && clonedVoice.isBlank()) {
            Toast.makeText(mContext, R.string.voice_clone_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        applyMessage("AI对话: 连接中... audience=${audienceProfile.segment}, address=$audienceAddress, knowledge=${audienceProfile.knowledgeAsset}")
        Log.i(TAG, "Audience mode: ${audienceProfile.segment}, address=$audienceAddress, knowledge=${audienceProfile.knowledgeAsset}")
        binding.tvSubtitle.visibility = View.VISIBLE
        binding.tvSubtitle.text = "正在为${audienceAddress}接通，请稍候"
        binding.audioSpectrum.visibility = View.VISIBLE
        binding.audioSpectrum.clear()
        assistantTextBuffer.clear()
        hasStartedPushForResponse = false
        currentSessionId = UUID.randomUUID().toString()
        pendingConversationPrompt = null
        waitingForInitialFamilyAnnouncement = false
        binding.btnAIConversation.setImageResource(R.drawable.ic_mic_off)
        binding.btnAIConversation.setBackgroundResource(R.drawable.shape_fab_ai_active)

        isAIConversationActive = true

        enqueueBridgeTask("家属留言预取失败") {
            val initialConversationPrompt = runCatching {
                familyBridgeService.prepareConversationPrompt(
                    take = 3,
                    includeMedicationAgenda = isSeniorMode,
                    address = audienceAddress,
                )
            }.onFailure { error ->
                applyMessage("家属留言预取失败: ${error.message}")
            }.getOrNull()

            mainHandler.post {
                if (!isAIConversationActive || isFinishing) {
                    return@post
                }

                pendingConversationPrompt = initialConversationPrompt
                waitingForInitialFamilyAnnouncement = initialConversationPrompt != null

                omniClient = OmniRealtimeClient(
                    buildRealtimeSessionOptions(initialConversationPrompt),
                    object : OmniRealtimeClient.Callback {
            override fun onConnected() {
                mainHandler.post {
                    binding.tvSubtitle.text = if (initialConversationPrompt != null) {
                        "我先跟${audienceAddress}说说家里人的留言。"
                    } else {
                        "已经接通，${audienceAddress}可以慢慢说"
                    }
                    binding.btnCameraToggle.visibility = if (isCloneMode) View.GONE else View.VISIBLE
                    binding.btnCameraToggle.setBackgroundResource(R.drawable.shape_fab_camera_off)
                    binding.btnCameraToggle.contentDescription = getString(R.string.camera_off)
                    isCameraEnabled = false
                    applyMessage("AI对话: 已连接, mode=${audienceProfile.segment}, address=$audienceAddress, model=${OmniRealtimeClient.DEFAULT_MODEL}, search=${if (enableWebSearch) "on" else "off"}, tools=${if (enableToolCalling) "on" else "off"}, cloneMode=$isCloneMode")
                    if (enableWebSearch && enableToolCalling) {
                        // applyMessage("AI对话: 当前配置请求 search=on 且 tools=on，但 Qwen Omni Realtime 协议规定两者不兼容，实际下发时将由服务端连接配置决定仅生效其中一项")
                    }
                    if (initialConversationPrompt != null) {
                        applyMessage("AI对话: 检测到家属留言，准备主动播报")
                        val requested = omniClient?.requestResponse() == true
                        if (!requested) {
                            applyMessage("AI对话: 主动播报触发失败，直接进入普通对话")
                            cancelInitialFamilyAnnouncementIfNeeded()
                        }
                    } else {
                        startStreamingRecorderIfNeeded()
                    }
                }
                enqueueBridgeTask("AI会话启动记录失败") {
                    familyBridgeService.registerCurrentDevice()
                    val detail = JSONObject().apply {
                        put("sessionId", currentSessionId)
                        put("cloneMode", isCloneMode)
                        put("voice", voice)
                    }
                    familyBridgeService.recordUsageEvent(FamilyBridgeService.EVENT_AI_SESSION_STARTED, detail)
                }
            }

            override fun onDisconnected() {
                mainHandler.post { stopAIConversation() }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    if (isImageAppendError(message)) {
                        stopCamera()
                    }
                    if (waitingForInitialFamilyAnnouncement) {
                        cancelInitialFamilyAnnouncementIfNeeded()
                    }
                    val friendlyMessage = toFriendlyErrorMessage(message)
                    Toast.makeText(mContext, friendlyMessage, Toast.LENGTH_SHORT).show()
                    binding.tvSubtitle.text = friendlyMessage
                    applyMessage("AI错误: $message")
                }
            }

            override fun onUserSpeechStarted() {
                if (isCloneMode) {
                    stopCloneSpeech()
                }
                cameraFrameCapture?.requestImmediateCapture()
                mainHandler.post {
                    binding.tvSubtitle.text = "我在认真听${audienceAddress}说"
                }
            }

            override fun onUserSpeechStopped() {
                mainHandler.post {
                    binding.tvSubtitle.text = "我想一想，马上回答${audienceAddress}"
                }
            }

            override fun onUserTranscript(transcript: String) {
                applyMessage("用户语音转写: $transcript")
                mainHandler.post {
                    binding.tvSubtitle.text = "${audienceAddress}刚才说：$transcript"
                }
                uploadConversationTurnAsync(FamilyBridgeService.ROLE_USER, transcript)
            }

            override fun onAssistantTextDelta(delta: String) {
                mainHandler.post {
                    assistantTextBuffer.append(delta)
                    binding.tvSubtitle.text = assistantTextBuffer.toString()
                }
            }

            override fun onAssistantTranscriptDone(transcript: String) {
                applyMessage("助手回复: $transcript")
                mainHandler.post {
                    assistantTextBuffer.clear()
                    assistantTextBuffer.append(transcript)
                    binding.tvSubtitle.text = transcript
                }
                uploadConversationTurnAsync(FamilyBridgeService.ROLE_ASSISTANT, transcript)
            }

            override fun onToolCallRequested(toolName: String, callId: String, argumentsJson: String) {
                mainHandler.post {
                    binding.tvSubtitle.text = when (toolName) {
                        "consult_psychology_knowledge_base" -> "我来帮${audienceAddress}查一下更稳妥的心理资料。"
                        "leave_message_for_family" -> "我来帮${audienceAddress}把这句话记给家里人。"
                        "fetch_family_messages" -> "我来帮${audienceAddress}看看家里人的新留言。"
                        "fetch_today_medication_plan" -> "我来看看今天的用药安排。"
                        "confirm_medication_taken" -> "好的，我帮${audienceAddress}把这次服药记下来。"
                        "snooze_medication_reminder" -> "好的，稍后我再提醒${audienceAddress}。"
                        "report_medication_problem", "request_family_help" -> "我先确认一下，再通知家里人关注。"
                        "respond_to_check_in" -> "好的，我把${audienceAddress}的平安回复告诉家里人。"
                        else -> "我想一想，马上回答${audienceAddress}"
                    }
                }
                applyMessage("AI工具调用: $toolName, callId=$callId, arguments=$argumentsJson")
                handleRealtimeToolCall(toolName, callId, argumentsJson)
            }

            override fun onAssistantAudioDelta(pcmData: ByteArray) {
                if (isCloneMode) {
                    return
                }
                if (!hasStartedPushForResponse) {
                    hasStartedPushForResponse = true
                    duix?.startPush()
                }
                val resampled = AudioResampler.resample24kTo16k(pcmData)
                if (resampled.isNotEmpty()) {
                    duix?.pushPcm(resampled)
                }
            }

            override fun onAssistantAudioDone() {
                if (isCloneMode) {
                    return
                }
                if (hasStartedPushForResponse) {
                    duix?.stopPush()
                    hasStartedPushForResponse = false
                }
            }

            override fun onResponseDone() {
                mainHandler.post {
                    if (isCloneMode) {
                        val replyText = assistantTextBuffer.toString().trim()
                        if (replyText.isNotEmpty()) {
                            speakWithClonedVoice(replyText)
                        }
                    } else if (hasStartedPushForResponse) {
                        duix?.stopPush()
                        hasStartedPushForResponse = false
                    }
                    assistantTextBuffer.clear()
                    if (!isCloneMode) {
                        completeInitialFamilyAnnouncementIfNeeded()
                    }
                }
            }
        })

                omniClient?.connect()
            }
        }
    }

    private fun stopAIConversation() {
        val endedSessionId = currentSessionId
        currentSessionId = null
        isAIConversationActive = false
        stopCamera()
        stopCloneSpeech()
        streamingRecorder?.release()
        streamingRecorder = null
        omniClient?.disconnect()
        omniClient = null
        duix?.stopAudio()
        assistantTextBuffer.clear()
        hasStartedPushForResponse = false
        pendingConversationPrompt = null
        waitingForInitialFamilyAnnouncement = false

        mainHandler.post {
            if (!isFinishing) {
                binding.btnAIConversation.setImageResource(R.drawable.ic_mic)
                binding.btnAIConversation.setBackgroundResource(R.drawable.shape_fab_ai)
                binding.btnCameraToggle.visibility = View.GONE
                binding.tvSubtitle.visibility = View.GONE
                binding.tvSubtitle.text = ""
                binding.audioSpectrum.clear()
                binding.audioSpectrum.visibility = View.GONE
                applyMessage("AI对话: 已结束")
            }
        }

        if (!endedSessionId.isNullOrBlank()) {
            enqueueBridgeTask("AI会话结束记录失败") {
                val detail = JSONObject().apply {
                    put("sessionId", endedSessionId)
                    put("cloneMode", isCloneMode)
                }
                familyBridgeService.recordUsageEvent(FamilyBridgeService.EVENT_AI_SESSION_ENDED, detail)
            }
        }
    }

    private fun speakWithClonedVoice(text: String) {
        if (!isAIConversationActive || clonedVoice.isBlank()) {
            return
        }
        stopCloneSpeech()
        ttsRealtimeClient = QwenTtsRealtimeClient(
            QwenTtsRealtimeClient.SessionOptions(
                voice = clonedVoice,
                model = cloneTtsModel
            ),
            object : QwenTtsRealtimeClient.Callback {
                override fun onConnected() {
                    ttsRealtimeClient?.appendText(text)
                    ttsRealtimeClient?.finish()
                }

                override fun onDisconnected() {
                    ttsRealtimeClient = null
                }

                override fun onAudioDelta(pcmData: ByteArray) {
                    if (!hasStartedPushForResponse) {
                        hasStartedPushForResponse = true
                        duix?.startPush()
                    }
                    val resampled = AudioResampler.resample24kTo16k(pcmData)
                    if (resampled.isNotEmpty()) {
                        duix?.pushPcm(resampled)
                    }
                }

                override fun onAudioDone() {
                    if (hasStartedPushForResponse) {
                        duix?.stopPush()
                        hasStartedPushForResponse = false
                    }
                }

                override fun onResponseDone() {
                }

                override fun onSessionFinished() {
                    if (hasStartedPushForResponse) {
                        duix?.stopPush()
                        hasStartedPushForResponse = false
                    }
                    ttsRealtimeClient = null
                    mainHandler.post {
                        completeInitialFamilyAnnouncementIfNeeded()
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        if (hasStartedPushForResponse) {
                            duix?.stopPush()
                            hasStartedPushForResponse = false
                        }
                        Toast.makeText(mContext, toFriendlyErrorMessage(message), Toast.LENGTH_SHORT).show()
                        applyMessage("复刻声音错误: $message")
                    }
                }
            }
        )
        ttsRealtimeClient?.connect()
    }

    private fun stopCloneSpeech() {
        ttsRealtimeClient?.disconnect()
        ttsRealtimeClient = null
        if (hasStartedPushForResponse) {
            duix?.stopPush()
            hasStartedPushForResponse = false
        }
        duix?.stopAudio()
    }

    private fun toggleCamera() {
        if (!isAIConversationActive || omniClient == null) return
        isCameraEnabled = !isCameraEnabled
        if (isCameraEnabled) {
            binding.cameraPreview.visibility = View.VISIBLE
            cameraFrameCapture = CameraFrameCapture(mContext, object : CameraFrameCapture.FrameCallback {
                override fun onFrame(jpegData: ByteArray) {
                    omniClient?.appendImage(jpegData)
                }
                override fun onError(message: String) {
                    mainHandler.post {
                        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show()
                        stopCamera()
                    }
                }
            }, binding.cameraPreview)
            cameraFrameCapture?.start()
            binding.btnCameraToggle.setBackgroundResource(R.drawable.shape_fab_camera_on)
            binding.btnCameraToggle.contentDescription = getString(R.string.camera_on)
            enqueueBridgeTask("镜头开启记录失败") {
                val detail = JSONObject()
                currentSessionId?.let { detail.put("sessionId", it) }
                familyBridgeService.recordUsageEvent(FamilyBridgeService.EVENT_CAMERA_ENABLED, detail)
            }
        } else {
            stopCamera()
            binding.btnCameraToggle.setBackgroundResource(R.drawable.shape_fab_camera_off)
            binding.btnCameraToggle.contentDescription = getString(R.string.camera_off)
        }
    }

    private fun stopCamera() {
        val wasEnabled = isCameraEnabled || cameraFrameCapture != null
        cameraFrameCapture?.stop()
        cameraFrameCapture = null
        isCameraEnabled = false
        binding.cameraPreview.visibility = View.GONE
        if (wasEnabled) {
            enqueueBridgeTask("镜头关闭记录失败") {
                val detail = JSONObject()
                currentSessionId?.let { detail.put("sessionId", it) }
                familyBridgeService.recordUsageEvent(FamilyBridgeService.EVENT_CAMERA_DISABLED, detail)
            }
        }
    }

    private fun enqueueBridgeTask(label: String, task: () -> Unit) {
        if (bridgeExecutor.isShutdown) {
            return
        }
        bridgeExecutor.execute {
            try {
                task()
            } catch (throwable: Throwable) {
                applyMessage("$label: ${throwable.message}")
            }
        }
    }

    private fun startStreamingRecorderIfNeeded() {
        if (!isAIConversationActive || streamingRecorder != null) {
            return
        }

        streamingRecorder = StreamingAudioRecorder(mContext, object : StreamingAudioRecorder.StreamingCallback {
            override fun onAudioData(pcmData: ByteArray) {
                omniClient?.appendAudio(pcmData)
                mainHandler.post { binding.audioSpectrum.updateAudio(pcmData) }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        })
        streamingRecorder?.start()
    }

    private fun completeInitialFamilyAnnouncementIfNeeded() {
        if (!waitingForInitialFamilyAnnouncement) {
            return
        }

        val prompt = pendingConversationPrompt
        pendingConversationPrompt = null
        waitingForInitialFamilyAnnouncement = false

        if (prompt != null && prompt.messageIds.isNotEmpty()) {
            enqueueBridgeTask("家属留言已读回写失败") {
                familyBridgeService.acknowledgeFamilyMessages(prompt.messageIds)
            }
        }

        startStreamingRecorderIfNeeded()
    }

    private fun cancelInitialFamilyAnnouncementIfNeeded() {
        if (!waitingForInitialFamilyAnnouncement) {
            return
        }

        pendingConversationPrompt = null
        waitingForInitialFamilyAnnouncement = false
        startStreamingRecorderIfNeeded()
    }

    private fun uploadConversationTurnAsync(role: String, content: String, metaJson: JSONObject? = null) {
        enqueueBridgeTask("对话记录上传失败") {
            uploadConversationTurnInternal(role, content, metaJson)
        }
    }

    private fun uploadConversationTurnInternal(role: String, content: String, metaJson: JSONObject? = null) {
        val sessionId = currentSessionId
        val normalizedContent = content.trim()
        if (sessionId.isNullOrBlank() || normalizedContent.isEmpty()) {
            return
        }
        familyBridgeService.uploadConversationTurns(
            sessionId,
            listOf(
                FamilyBridgeService.ConversationTurnPayload(
                    role = role,
                    content = normalizedContent,
                    metaJson = metaJson?.toString(),
                )
            )
        )
    }

    private fun handleRealtimeToolCall(toolName: String, callId: String, argumentsJson: String) {
        enqueueBridgeTask("工具调用处理失败") {
            val arguments = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
            val result = executeRealtimeTool(toolName, callId, arguments)
            applyMessage("AI工具输出: $toolName, callId=$callId, status=${result.status}, output=${result.outputText}")
            uploadConversationTurnInternal(
                FamilyBridgeService.ROLE_TOOL,
                result.outputText,
                JSONObject().apply {
                    put("toolName", toolName)
                    put("callId", callId)
                    put("status", result.status)
                    put("arguments", arguments)
                }
            )
            val sent = omniClient?.sendFunctionCallOutput(callId, result.outputText) == true
            if (!sent) {
                applyMessage("工具结果回传失败: $toolName")
            }
        }
    }

    private fun executeRealtimeTool(
        toolName: String,
        callId: String,
        arguments: JSONObject,
    ): ToolExecutionResult {
        return when (toolName) {
            "consult_psychology_knowledge_base" -> executePsychologyKnowledgeTool(callId, arguments)
            "leave_message_for_family" -> executeLeaveMessageTool(callId, arguments)
            "fetch_family_messages" -> executeFetchFamilyMessagesTool(callId, arguments)
            "fetch_today_medication_plan", "confirm_medication_taken", "snooze_medication_reminder",
            "report_medication_problem", "respond_to_check_in", "request_family_help" -> executeFamilyCareTool(toolName, callId, arguments)
            else -> {
                val outputText = "这个请求我还不能直接处理，${audienceAddress}可以换一种说法再告诉我。"
                runCatching {
                    familyBridgeService.recordToolCall(
                        toolName = toolName,
                        arguments = arguments,
                        outputText = outputText,
                        status = FamilyBridgeService.STATUS_FAILURE,
                        callId = callId,
                        sessionId = currentSessionId,
                    )
                }
                ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
            }
        }
    }

    private fun executePsychologyKnowledgeTool(callId: String, arguments: JSONObject): ToolExecutionResult {
        val question = arguments.optString("question").trim()
        val focus = arguments.optString("focus").trim().ifBlank { null }

        if (question.isEmpty()) {
            val outputText = "还没有拿到要查询的具体问题，请先用一句话说明想查什么。"
            runCatching {
                familyBridgeService.recordToolCall(
                    toolName = "consult_psychology_knowledge_base",
                    arguments = arguments,
                    outputText = outputText,
                    status = FamilyBridgeService.STATUS_FAILURE,
                    callId = callId,
                    sessionId = currentSessionId,
                )
            }
            return ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
        }

        return runCatching {
            val result = psychologyKnowledgeAgentService.consultKnowledge(
                question = question,
                focus = focus,
                knowledgeAsset = audienceProfile.knowledgeAsset,
                audienceName = audienceProfile.displayName,
            )
            familyBridgeService.recordToolCall(
                toolName = "consult_psychology_knowledge_base",
                arguments = arguments,
                outputText = result.outputText,
                status = FamilyBridgeService.STATUS_SUCCESS,
                callId = callId,
                sessionId = currentSessionId,
            )
            applyMessage(
                "心理学子代理完成: model=${result.model}, requestId=${result.requestId ?: "-"}, inputTokens=${result.inputTokens}, outputTokens=${result.outputTokens}"
            )
            ToolExecutionResult(result.outputText, FamilyBridgeService.STATUS_SUCCESS)
        }.getOrElse { error ->
            val outputText = knowledgeQueryFallbackText()
            runCatching {
                familyBridgeService.recordToolCall(
                    toolName = "consult_psychology_knowledge_base",
                    arguments = arguments,
                    outputText = outputText,
                    status = FamilyBridgeService.STATUS_FAILURE,
                    callId = callId,
                    sessionId = currentSessionId,
                )
            }
            applyMessage("心理学子代理失败: ${error.message}")
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
        }
    }

    private fun knowledgeQueryFallbackText(): String {
        return when (audienceProfile.segment) {
            AudienceSegment.TEEN -> "权威资料查询暂时失败。请先保持温和陪伴，不要给出确定性诊断；如果对方提到自伤他伤、被霸凌、虐待或急性危险，鼓励其联系监护人、老师或信任的成年人，紧急情况提示拨打120/110，并可提供12356心理援助热线。"
            AudienceSegment.ADULT -> "权威资料查询暂时失败。请先保持温和陪伴，不要给出确定性诊断；如果涉及持续恶化、明显痛苦、自伤他伤或急性危险，建议其联系信任的人或专业心理服务，紧急情况拨打120/110，并可提供12356心理援助热线。"
            AudienceSegment.SENIOR -> "权威照护资料查询暂时失败。请先基于安全原则回应：保持温和陪伴，避免给出确定性的医学结论；如果涉及持续恶化、明显痛苦、自伤他伤或急性危险，请建议尽快联系家属、医生或急救。"
        }
    }

    private fun executeLeaveMessageTool(callId: String, arguments: JSONObject): ToolExecutionResult {
        val message = arguments.optString("message").trim()
        val relation = arguments.optString("recipientRelation").trim().ifBlank { "家里人" }

        if (message.isEmpty()) {
            val outputText = "我还没听清${audienceAddress}想转告的话，${audienceAddress}再慢慢说一遍，我帮${audienceAddress}记下来。"
            runCatching {
                familyBridgeService.recordToolCall(
                    toolName = "leave_message_for_family",
                    arguments = arguments,
                    outputText = outputText,
                    status = FamilyBridgeService.STATUS_FAILURE,
                    callId = callId,
                    sessionId = currentSessionId,
                )
            }
            return ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
        }

        return runCatching {
            val outputText = "已记下要转告给${relation}的话：$message"
            familyBridgeService.recordToolCall(
                toolName = "leave_message_for_family",
                arguments = arguments,
                outputText = outputText,
                status = FamilyBridgeService.STATUS_SUCCESS,
                callId = callId,
                sessionId = currentSessionId,
            )
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_SUCCESS)
        }.getOrElse { error ->
            val outputText = "暂时还没连上家人的留言通道，请稍后再试一次。"
            runCatching {
                familyBridgeService.recordToolCall(
                    toolName = "leave_message_for_family",
                    arguments = arguments,
                    outputText = outputText,
                    status = FamilyBridgeService.STATUS_FAILURE,
                    callId = callId,
                    sessionId = currentSessionId,
                )
            }
            applyMessage("留言工具失败: ${error.message}")
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
        }
    }

    private fun executeFetchFamilyMessagesTool(callId: String, arguments: JSONObject): ToolExecutionResult {
        val requestedCount = arguments.optInt("count", 3).coerceIn(1, 5)

        return runCatching {
            val result = familyBridgeService.fetchFamilyMessages(requestedCount)
            if (result.messages.isNotEmpty()) {
                familyBridgeService.markFamilyMessagesRead(result.messages.map { it.id })
            }
            val outputText = if (result.messages.isEmpty()) {
                "家里人暂时还没有新的留言。"
            } else {
                buildString {
                    append(result.summaryText.ifBlank { "我帮${audienceAddress}看了看，家里人有新的留言。" })
                    result.messages.take(requestedCount).forEachIndexed { index, message ->
                        append(" 第${index + 1}条：${message.content}")
                    }
                }
            }
            familyBridgeService.recordToolCall(
                toolName = "fetch_family_messages",
                arguments = arguments,
                outputText = outputText,
                status = FamilyBridgeService.STATUS_SUCCESS,
                callId = callId,
                sessionId = currentSessionId,
            )
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_SUCCESS)
        }.getOrElse { error ->
            val outputText = "暂时还没取到家人的新留言，请稍后再试一次。"
            runCatching {
                familyBridgeService.recordToolCall(
                    toolName = "fetch_family_messages",
                    arguments = arguments,
                    outputText = outputText,
                    status = FamilyBridgeService.STATUS_FAILURE,
                    callId = callId,
                    sessionId = currentSessionId,
                )
            }
            applyMessage("读取家人留言失败: ${error.message}")
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
        }
    }

    private fun executeFamilyCareTool(toolName: String, callId: String, arguments: JSONObject): ToolExecutionResult {
        return runCatching {
            val outputText = when (toolName) {
                "fetch_today_medication_plan" -> {
                    val agenda = familyBridgeService.fetchTodayAgenda()
                    if (agenda == null || agenda.doses.length() == 0) "今天暂时没有待确认的用药提醒。" else buildString {
                        append("今天有${agenda.doses.length()}次待确认用药。")
                        for (index in 0 until agenda.doses.length()) { val item = agenda.doses.optJSONObject(index) ?: continue; append(" 编号${item.optString("id")}：${item.optString("name")}，${item.optString("dosage")}。") }
                    }
                }
                "confirm_medication_taken" -> { familyBridgeService.updateMedication(arguments.optString("doseId"), "TAKEN", "用户语音确认已服"); "已记录这次服药。请不要重复服用；如有不适，请立即告诉我或联系家人。" }
                "snooze_medication_reminder" -> { val minutes = arguments.optInt("minutes", 10).coerceIn(1, 60); familyBridgeService.updateMedication(arguments.optString("doseId"), "SNOOZED", snoozeMinutes = minutes); "好的，已延后${minutes}分钟提醒。" }
                "report_medication_problem" -> {
                    val problem = arguments.optString("problem").ifBlank { "用户报告用药问题" }
                    val doseId = arguments.optString("doseId")
                    if (doseId.isNotBlank() && doseId != "unknown") familyBridgeService.updateMedication(doseId, "NEEDS_FAMILY_CONFIRMATION", problem)
                    familyBridgeService.reportSafetyAlert("MEDICATION", "用药需要家人确认", problem, if (arguments.optString("severity") == "RED") "RED" else "ORANGE")
                    "已经通知家里人关注这次用药问题。我不能代替医生判断；如果情况紧急，请立即联系120或请身边的人帮助。"
                }
                "respond_to_check_in" -> { val status = arguments.optString("status").takeIf { it in listOf("SAFE", "NEEDS_CONTACT", "DECLINED") } ?: "NEEDS_CONTACT"; familyBridgeService.respondCheckIn(arguments.optString("checkInId"), status, arguments.optString("responseText")); "已经把${audienceAddress}的回复告诉家里人。" }
                "request_family_help" -> {
                    val summary = arguments.optString("summary").ifBlank {
                        if (isSeniorMode) "长辈请求家人联系" else "用户请求家人联系"
                    }
                    val title = when (audienceProfile.segment) {
                        AudienceSegment.TEEN -> "青少年请求家人关注"
                        AudienceSegment.ADULT -> "用户请求家人关注"
                        AudienceSegment.SENIOR -> "长辈请求家人关注"
                    }
                    familyBridgeService.reportSafetyAlert(arguments.optString("category").ifBlank { "HELP_REQUEST" }, title, summary, if (arguments.optString("level") == "RED") "RED" else "ORANGE", summary)
                    "已经通知家里人关注。这个提醒不是医学诊断；如果情况紧急，请立即联系120、110或请身边的人帮助。"
                }
                else -> error("不支持的照护工具")
            }
            familyBridgeService.recordToolCall(toolName, arguments, outputText, FamilyBridgeService.STATUS_SUCCESS, callId, currentSessionId)
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_SUCCESS)
        }.getOrElse { error ->
            val outputText = "家庭信息暂时没有同步成功，请稍后再试；如果情况紧急，请直接联系家人或急救服务。"
            runCatching { familyBridgeService.recordToolCall(toolName, arguments, outputText, FamilyBridgeService.STATUS_FAILURE, callId, currentSessionId) }
            applyMessage("家庭照护工具失败: ${error.message}")
            ToolExecutionResult(outputText, FamilyBridgeService.STATUS_FAILURE)
        }
    }

    private data class ToolExecutionResult(
        val outputText: String,
        val status: String,
    )

    private fun showRecordDialog(){
        val audioRecordDialog = AudioRecordDialog(mContext, object : AudioRecordDialog.Listener{
            override fun onFinish(path: String) {
                val thread = Thread {
                    duix?.startPush()
                    val inputStream = FileInputStream(path)
                    val buffer = ByteArray(320)
                    var length: Int
                    while (inputStream.read(buffer).also { length = it } > 0){
                        val data = buffer.copyOfRange(0, length)
                        duix?.pushPcm(data)
                    }
                    duix?.stopPush()
                    inputStream.close()
                }
                thread.start()
            }
        })
        audioRecordDialog.show()
    }
}

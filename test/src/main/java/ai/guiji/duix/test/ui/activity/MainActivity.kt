package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.VirtualModelUtil
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityMainBinding
import ai.guiji.duix.test.model.AvatarCatalog
import ai.guiji.duix.test.model.AvatarModel
import ai.guiji.duix.test.service.DebugSettingsStore
import ai.guiji.duix.test.service.FamilyBridgeService
import ai.guiji.duix.test.ui.adapter.AvatarAdapter
import ai.guiji.duix.test.ui.dialog.LoadingDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import org.json.JSONObject
import java.io.File


class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val debugSettingsStore by lazy { DebugSettingsStore(applicationContext) }
    private val familyBridgeService by lazy { FamilyBridgeService(applicationContext) }
    private var mLoadingDialog: LoadingDialog? = null
    private var mLastProgress = 0

    private val baseConfigUrl = "https://dl.xn--876a.net/models/gj_dh_res.zip"

    private var mSelectedModel: AvatarModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnVoiceClone.setOnClickListener {
            startActivity(Intent(mContext, VoiceCloneSetupActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(mContext, SettingsActivity::class.java))
        }

        binding.rvAvatars.layoutManager = GridLayoutManager(this, 2)
        binding.rvAvatars.adapter = AvatarAdapter(AvatarCatalog.models, object : AvatarAdapter.Callback {
            override fun onSelect(model: AvatarModel) {
                mSelectedModel = model
                play(model)
            }
        })

        bootstrapFamilyBridge()
    }

    private fun bootstrapFamilyBridge() {
        Thread {
            runCatching { familyBridgeService.registerCurrentDevice() }
            runCatching {
                familyBridgeService.recordUsageEvent(
                    FamilyBridgeService.EVENT_APP_OPEN,
                    JSONObject().put("screen", "main"),
                )
            }
        }.start()
    }

    private fun play(model: AvatarModel) {
        mSelectedModel = model
        checkBaseConfig()
    }

    private fun checkBaseConfig() {
        if (VirtualModelUtil.checkBaseConfig(mContext)) {
            checkModel()
        } else {
            baseConfigDownload()
        }
    }

    private fun checkModel() {
        val model = mSelectedModel ?: return
        if (VirtualModelUtil.checkModel(mContext, model.modelUrl)) {
            jumpPlayPage()
        } else {
            modelDownload()
        }
    }

    private fun jumpPlayPage() {
        val model = mSelectedModel ?: return
        val intent = Intent(mContext, CallActivity::class.java)
        intent.putExtra("modelUrl", model.modelUrl)
        intent.putExtra("voice", model.voice)
        intent.putExtra("debug", debugSettingsStore.isChatDebugEnabled())
        startActivity(intent)
    }

    private fun baseConfigDownload() {
        mLoadingDialog?.dismiss()
        mLoadingDialog = LoadingDialog(mContext, "正在准备资源...")
        mLoadingDialog?.show()
        VirtualModelUtil.baseConfigDownload(mContext, baseConfigUrl, object :
            VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setContent("基础配置下载中(${progress}%)")
                        }
                    }
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setContent("基础配置解压中(${progress}%)")
                        }
                    }
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    checkModel()
                }
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    Toast.makeText(mContext, "资源下载失败: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun modelDownload() {
        val model = mSelectedModel ?: return
        mLoadingDialog?.dismiss()
        mLoadingDialog = LoadingDialog(mContext, "正在下载 ${model.name} ...")
        mLoadingDialog?.show()
        VirtualModelUtil.modelDownload(mContext, model.modelUrl, object : VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setContent("${model.name} 下载中(${progress}%)")
                        }
                    }
                }
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                val progress = (current * 100 / total).toInt()
                if (progress != mLastProgress) {
                    mLastProgress = progress
                    runOnUiThread {
                        if (mLoadingDialog?.isShowing == true) {
                            mLoadingDialog?.setContent("${model.name} 解压中(${progress}%)")
                        }
                    }
                }
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    jumpPlayPage()
                }
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                runOnUiThread {
                    mLoadingDialog?.dismiss()
                    Toast.makeText(mContext, "下载失败: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}

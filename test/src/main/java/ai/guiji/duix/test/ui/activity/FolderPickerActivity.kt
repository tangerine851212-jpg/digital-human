package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityFolderPickerBinding
import ai.guiji.duix.test.ui.adapter.FolderBrowserAdapter
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File

class FolderPickerActivity : BaseActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private lateinit var folderAdapter: FolderBrowserAdapter
    private lateinit var browseRoot: File
    private lateinit var currentDirectory: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        browseRoot = resolveBrowseRoot()
        currentDirectory = resolveInitialDirectory(intent.getStringExtra(EXTRA_INITIAL_PATH))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canNavigateUp()) {
                    navigateUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setupList()
        setupActions()
        renderCurrentDirectory()
    }

    private fun setupList() {
        folderAdapter = FolderBrowserAdapter { folder ->
            currentDirectory = folder
            renderCurrentDirectory()
        }
        binding.rvFolders.layoutManager = LinearLayoutManager(this)
        binding.rvFolders.adapter = folderAdapter
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnNavigateUp.setOnClickListener { navigateUp() }
        binding.btnSelectCurrent.setOnClickListener {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_SELECTED_PATH, currentDirectory.absolutePath),
            )
            finish()
        }
    }

    private fun renderCurrentDirectory() {
        val subFolders = currentDirectory.listFiles()
            ?.filter { it.isDirectory && it.canRead() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        binding.tvCurrentFolderName.text = currentDirectory.name.ifBlank { currentDirectory.absolutePath }
        binding.tvCurrentPath.text = currentDirectory.absolutePath
        binding.btnNavigateUp.isEnabled = canNavigateUp()
        binding.tvEmpty.visibility = if (subFolders.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFolders.visibility = if (subFolders.isEmpty()) View.GONE else View.VISIBLE
        folderAdapter.submitList(subFolders)
    }

    private fun navigateUp() {
        if (!canNavigateUp()) {
            return
        }
        currentDirectory.parentFile?.let { parent ->
            currentDirectory = parent
            renderCurrentDirectory()
        }
    }

    private fun canNavigateUp(): Boolean {
        val rootPath = browseRoot.canonicalPath
        val currentPath = currentDirectory.canonicalPath
        return currentPath != rootPath && currentDirectory.parentFile != null && currentPath.startsWith(rootPath)
    }

    private fun resolveBrowseRoot(): File {
        return Environment.getExternalStorageDirectory().canonicalFile
    }

    private fun resolveInitialDirectory(initialPath: String?): File {
        val candidates = listOfNotNull(
            initialPath?.takeIf { it.isNotBlank() }?.let(::File),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            browseRoot,
        )
        for (candidate in candidates) {
            val file = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
            if (!file.exists() || !file.isDirectory || !file.canRead()) {
                continue
            }
            if (file.canonicalPath.startsWith(browseRoot.canonicalPath)) {
                return file
            }
        }
        return browseRoot
    }

    companion object {
        const val EXTRA_INITIAL_PATH = "extra_initial_path"
        const val EXTRA_SELECTED_PATH = "extra_selected_path"

        fun createIntent(context: Context, initialPath: String?): Intent {
            return Intent(context, FolderPickerActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_PATH, initialPath)
            }
        }
    }
}
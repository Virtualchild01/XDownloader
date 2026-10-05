package com.example.xdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.xdownloader.api.XApiService
import com.example.xdownloader.api.XFormatOption
import com.example.xdownloader.api.XMediaItem
import com.example.xdownloader.api.XMediaType
import com.example.xdownloader.api.XPostInfo
import com.example.xdownloader.databinding.ActivityMainBinding
import com.example.xdownloader.utils.DownloadUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val apiService = XApiService()

    private var currentPostInfo: XPostInfo? = null
    private var selectedMediaItem: XMediaItem? = null
    private var selectedFormat: XFormatOption? = null
    private var currentUrl: String? = null
    private var lastSavedFileUri: Uri? = null

    companion object {
        private const val PREFS_NAME = "x_downloader_prefs"
        private const val KEY_THEME = "key_theme_mode"
        private const val THEME_AUTO = 0
        private const val THEME_DARK = 1
        private const val THEME_LIGHT = 2

        const val CLOUDTIPS_URL = "https://pay.cloudtips.ru/p/9206d8ca"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startDownloadProcess()
        } else {
            Toast.makeText(this, "Требуется разрешение на запись для сохранения файлов", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupThemeToggle()
        setupListeners()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun applySavedTheme() {
        when (prefs.getInt(KEY_THEME, THEME_AUTO)) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setupThemeToggle() {
        when (prefs.getInt(KEY_THEME, THEME_AUTO)) {
            THEME_DARK -> binding.toggleThemeGroup.check(R.id.btnThemeDark)
            THEME_LIGHT -> binding.toggleThemeGroup.check(R.id.btnThemeLight)
            else -> binding.toggleThemeGroup.check(R.id.btnThemeAuto)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = when (checkedId) {
                    R.id.btnThemeDark -> THEME_DARK
                    R.id.btnThemeLight -> THEME_LIGHT
                    else -> THEME_AUTO
                }

                if (newTheme != prefs.getInt(KEY_THEME, THEME_AUTO)) {
                    prefs.edit().putInt(KEY_THEME, newTheme).apply()
                    when (newTheme) {
                        THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        // Paste button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                binding.etUrl.setText(text)
                if (text.isNotBlank()) {
                    fetchPostMedia(text)
                }
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        }

        // Find media button
        binding.btnFind.setOnClickListener {
            val url = binding.etUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_empty_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchPostMedia(url)
        }

        // Download button
        binding.btnDownload.setOnClickListener {
            checkPermissionsAndDownload()
        }

        // Action: Open Downloads / Gallery
        binding.btnViewVideo.setOnClickListener {
            val uri = lastSavedFileUri
            if (uri != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/gif")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    return@setOnClickListener
                } catch (_: Exception) {}
            }
            openDownloadsFolder()
        }

        // Action: Share link or downloaded file
        binding.btnShare.setOnClickListener {
            val uri = lastSavedFileUri
            if (uri != null) {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/gif"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Поделиться файлом GIF"))
                    return@setOnClickListener
                } catch (_: Exception) {}
            }

            val url = currentUrl ?: return@setOnClickListener
            val post = currentPostInfo
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Медиафайл из X")
                putExtra(Intent.EXTRA_TEXT, "${post?.tweetText?.take(60) ?: "Пост в X"}\n$url")
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться ссылкой"))
        }

        // Support Developer buttons
        binding.btnSupport.setOnClickListener {
            showSupportDialog()
        }
        binding.btnSupportHeader.setOnClickListener {
            showSupportDialog()
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (sharedText.isNotBlank()) {
                binding.etUrl.setText(sharedText)
                fetchPostMedia(sharedText)
            }
        }
    }

    private fun fetchPostMedia(url: String) {
        currentUrl = url
        val tweetId = apiService.extractTweetId(url)
        if (tweetId == null) {
            Toast.makeText(this, "Не удалось распознать ссылку на пост X / Twitter", Toast.LENGTH_LONG).show()
            return
        }

        binding.llLoading.visibility = View.VISIBLE
        binding.cardResult.visibility = View.GONE
        binding.btnFind.isEnabled = false

        lifecycleScope.launch {
            val result = apiService.getPostInfo(tweetId)
            binding.llLoading.visibility = View.GONE
            binding.btnFind.isEnabled = true

            result.onSuccess { postInfo ->
                currentPostInfo = postInfo
                displayPostInfo(postInfo)
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_error, error.message ?: "Неизвестная ошибка"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun displayPostInfo(postInfo: XPostInfo) {
        binding.tvAuthorName.text = postInfo.authorName
        binding.tvAuthorHandle.text = postInfo.authorHandle
        binding.tvTweetText.text = postInfo.tweetText.ifEmpty { "Медиафайл из публикации X" }

        val firstMedia = postInfo.mediaItems.firstOrNull() ?: return
        selectedMediaItem = firstMedia

        // Badge styling
        when (firstMedia.type) {
            XMediaType.GIF -> {
                binding.tvBadgeType.text = getString(R.string.badge_gif)
                binding.tvBadgeType.setBackgroundColor(Color.parseColor("#F91880"))
            }
            XMediaType.VIDEO -> {
                binding.tvBadgeType.text = getString(R.string.badge_video)
                binding.tvBadgeType.setBackgroundColor(Color.parseColor("#1D9BF0"))
            }
            XMediaType.PHOTO -> {
                binding.tvBadgeType.text = getString(R.string.badge_photo)
                binding.tvBadgeType.setBackgroundColor(Color.parseColor("#00BA7C"))
            }
        }

        // Load preview
        Glide.with(this)
            .load(firstMedia.previewUrl)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .into(binding.ivPreview)

        // Setup format options
        populateFormats(firstMedia.availableFormats)

        // Reset status below action buttons
        binding.layoutStatus.visibility = View.GONE
        binding.progressDownload.visibility = View.GONE
        binding.tvStatus.text = ""

        binding.cardResult.visibility = View.VISIBLE
    }

    private fun populateFormats(formats: List<XFormatOption>) {
        binding.rgFormats.removeAllViews()

        for ((index, format) in formats.withIndex()) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = format.label
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                tag = format
                isChecked = (index == 0)
            }
            binding.rgFormats.addView(rb)

            if (index == 0) {
                selectedFormat = format
                updateGifNotice(format)
            }
        }

        binding.rgFormats.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            val format = checkedRb?.tag as? XFormatOption
            selectedFormat = format
            format?.let { updateGifNotice(it) }
        }
    }

    private fun updateGifNotice(format: XFormatOption) {
        if (format.isGifConversion) {
            binding.tvGifNotice.visibility = View.VISIBLE
            binding.btnDownload.text = "Скачать как GIF"
        } else {
            binding.tvGifNotice.visibility = View.GONE
            binding.btnDownload.text = getString(R.string.btn_download)
        }
    }

    private fun checkPermissionsAndDownload() {
        if (selectedFormat == null || currentPostInfo == null) {
            Toast.makeText(this, "Пожалуйста, выберите формат", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(permission)
                return
            }
        }

        startDownloadProcess()
    }

    private fun startDownloadProcess() {
        val format = selectedFormat ?: return
        val post = currentPostInfo ?: return
        val title = post.tweetText.take(30).ifEmpty { "X_Post_${post.tweetId}" }

        // Показываем индикатор прогресса строго ПОД кнопками «В галерею» и «Поделиться»
        binding.layoutStatus.visibility = View.VISIBLE
        binding.progressDownload.visibility = View.VISIBLE

        if (format.isGifConversion) {
            // GIF conversion in-app
            binding.progressDownload.isIndeterminate = false
            binding.progressDownload.progress = 0
            binding.btnDownload.isEnabled = false
            binding.tvStatus.text = "Подготовка к конвертации GIF..."

            lifecycleScope.launch {
                val uri = DownloadUtil.downloadAndConvertToGif(
                    context = this@MainActivity,
                    videoUrl = format.downloadUrl,
                    title = title,
                    onStatusUpdate = { status ->
                        binding.tvStatus.text = status
                    },
                    onProgress = { progress ->
                        binding.progressDownload.progress = progress
                        binding.tvStatus.text = "Создание GIF: $progress%"
                    }
                )

                binding.btnDownload.isEnabled = true
                binding.progressDownload.visibility = View.GONE

                if (uri != null) {
                    lastSavedFileUri = uri
                    binding.tvStatus.text = "Готово! Анимация сохранена в «Загрузки»"
                    Toast.makeText(this@MainActivity, getString(R.string.toast_gif_saved), Toast.LENGTH_LONG).show()
                } else {
                    binding.tvStatus.text = "Ошибка при создании GIF"
                    Toast.makeText(this@MainActivity, "Ошибка при создании GIF. Попробуйте формат MP4.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // Standard direct download via DownloadManager
            binding.progressDownload.isIndeterminate = true
            binding.tvStatus.text = "Загрузка запущена в фоновом режиме через менеджер загрузок..."

            DownloadUtil.enqueueDownload(
                context = this,
                url = format.downloadUrl,
                title = title,
                formatLabel = format.label,
                extension = format.extension
            )
            Toast.makeText(this, getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDownloadsFolder() {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
            }
            try {
                startActivity(Intent.createChooser(fallbackIntent, "Открыть папку Загрузки"))
            } catch (ex: Exception) {
                Toast.makeText(this, "Файлы сохранены в системную папку «Загрузки»", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSupportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_support_title))
            .setMessage(getString(R.string.dialog_support_message))
            .setIcon(R.drawable.ic_heart)
            .setPositiveButton(getString(R.string.btn_open_link)) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CLOUDTIPS_URL))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }
}

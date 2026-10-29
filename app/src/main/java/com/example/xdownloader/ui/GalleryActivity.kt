package com.example.xdownloader.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.xdownloader.R
import com.example.xdownloader.data.DownloadStorage
import com.example.xdownloader.data.DownloadedMedia
import com.example.xdownloader.data.MediaSharer
import com.example.xdownloader.databinding.ActivityGalleryBinding
import com.example.xdownloader.databinding.SheetFileActionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Встроенная галерея: сетка скачанных файлов из папок приложения. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = GalleryAdapter(
            onOpen = { open(it) },
            onMore = { showActions(it) }
        )

        binding.rvGallery.layoutManager = GridLayoutManager(this, 2)
        binding.rvGallery.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { DownloadStorage.list(applicationContext) }
            adapter.submit(items)
            binding.swipeRefresh.isRefreshing = false
            binding.layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvGallery.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            binding.tvCount.text = if (items.isEmpty()) "" else items.size.toString()
        }
    }

    /** Видео открываем во внешнем плеере, GIF/JPG — в системном просмотрщике. */
    private fun open(media: DownloadedMedia) {
        if (!MediaSharer.open(this, media)) {
            Toast.makeText(this, R.string.no_player_for_format, Toast.LENGTH_LONG).show()
        }
    }

    /** Шторка с действиями над файлом: поделиться или удалить. */
    private fun showActions(media: DownloadedMedia) {
        val sheetBinding = SheetFileActionsBinding.inflate(layoutInflater)
        val sheet = BottomSheetDialog(this).apply { setContentView(sheetBinding.root) }

        sheetBinding.tvSheetTitle.text = media.displayName

        sheetBinding.rowShare.setOnClickListener {
            sheet.dismiss()
            share(media)
        }

        sheetBinding.rowDelete.setOnClickListener {
            sheet.dismiss()
            confirmDelete(media)
        }

        sheet.show()
    }

    private fun share(media: DownloadedMedia) {
        if (!MediaSharer.share(this, media, getString(R.string.share_file))) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(media: DownloadedMedia) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(media.displayName)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_action) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        DownloadStorage.delete(applicationContext, media)
                    }
                    if (!ok) {
                        Toast.makeText(this@GalleryActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                    load()
                }
            }
            .show()
    }
}

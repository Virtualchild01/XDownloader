package com.example.xdownloader.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.xdownloader.R
import com.example.xdownloader.data.DownloadedMedia
import com.example.xdownloader.databinding.ItemGalleryBinding
import java.util.Locale

/**
 * Сетка скачанных файлов: превью, размер и бейдж формата.
 * Открытие и меню действий вынесены наружу колбэками.
 */
class GalleryAdapter(
    private val onOpen: (DownloadedMedia) -> Unit,
    private val onMore: (DownloadedMedia) -> Unit
) : ListAdapter<DownloadedMedia, GalleryAdapter.VH>(DIFF) {

    fun submit(items: List<DownloadedMedia>) = submitList(items)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemGalleryBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: DownloadedMedia) {
            val context = b.root.context
            b.tvName.text = item.displayName
            b.tvMeta.text = formatSize(item.sizeBytes)

            // Бейдж показывает расширение: GIF, MP4, JPG
            b.tvBadge.visibility = View.VISIBLE
            b.tvBadge.text = item.extensionLabel.uppercase(Locale.US)

            Glide.with(b.ivThumb)
                .load(item.uri)
                .centerCrop()
                .placeholder(ColorDrawable(Color.DKGRAY))
                .error(ColorDrawable(Color.DKGRAY))
                .into(b.ivThumb)

            b.root.setOnClickListener { onOpen(item) }
            b.btnMore.setOnClickListener { onMore(item) }
        }

        /** Размер как в системном проводнике: КБ до мегабайта, дальше МБ/ГБ. */
        private fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return b.root.context.getString(R.string.size_unknown)
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f %s", gb, b.root.context.getString(R.string.unit_gb))
                mb >= 1.0 -> String.format(Locale.US, "%.1f %s", mb, b.root.context.getString(R.string.unit_mb))
                else -> String.format(Locale.US, "%.0f %s", kb, b.root.context.getString(R.string.unit_kb))
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadedMedia>() {
            override fun areItemsTheSame(a: DownloadedMedia, b: DownloadedMedia) = a.uri == b.uri
            override fun areContentsTheSame(a: DownloadedMedia, b: DownloadedMedia) = a == b
        }
    }
}

package com.corlebell.aabinstaller.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.corlebell.aabinstaller.MainViewModel
import com.corlebell.aabinstaller.databinding.ItemDownloadBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadListAdapter(
    private val onSelectionChanged: () -> Unit,
    private val onInstall: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadListAdapter.VH>() {

    private var items: List<DownloadRecord> = emptyList()
    private val selected = mutableSetOf<String>()

    fun submit(list: List<DownloadRecord>) {
        items = list
        selected.retainAll(list.map { it.id }.toSet())
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedIds(): Set<String> = selected.toSet()

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) {
            binding.tvFileName.text = record.fileName
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(record.createdAt))
            val statusText = when (record.status) {
                DownloadStatus.DOWNLOADING -> "下载中"
                DownloadStatus.COMPLETED -> "完成 · ${MainViewModel.formatSize(record.size)}"
                DownloadStatus.FAILED -> "失败 · ${record.errorMessage}"
            }
            binding.tvMeta.text = "$statusText · $time"
            binding.tvUrl.text = record.url
            binding.check.setOnCheckedChangeListener(null)
            binding.check.isChecked = record.id in selected
            binding.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(record.id) else selected.remove(record.id)
                onSelectionChanged()
            }
            val canInstall = record.status == DownloadStatus.COMPLETED
            binding.btnInstall.visibility = if (canInstall) View.VISIBLE else View.GONE
            binding.btnInstall.setOnClickListener { if (canInstall) onInstall(record) }
            binding.root.setOnClickListener {
                binding.check.isChecked = !binding.check.isChecked
            }
        }
    }
}

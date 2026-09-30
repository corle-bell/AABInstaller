package com.corlebell.aabinstaller.download

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.corlebell.aabinstaller.R
import com.corlebell.aabinstaller.databinding.ActivityDownloadRecordsBinding
import java.io.File

class DownloadRecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadRecordsBinding
    private lateinit var downloadRepo: DownloadRepository
    private lateinit var adapter: DownloadListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        downloadRepo = DownloadRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = DownloadListAdapter(
            onSelectionChanged = { updateSelectionActions() },
            onInstall = { record ->
                if (!File(record.localPath).exists()) {
                    Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
                    return@DownloadListAdapter
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_INSTALL_ID, record.id))
                finish()
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnSelectAll.setOnClickListener {
            if (adapter.isAllSelected()) adapter.clearSelection() else adapter.selectAll()
        }
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }
        refreshList()
    }

    private fun updateSelectionActions() {
        val count = adapter.selectedIds().size
        binding.btnDeleteSelected.isEnabled = count > 0
        binding.btnSelectAll.isEnabled = adapter.itemCount > 0
        binding.btnSelectAll.text = getString(
            if (adapter.isAllSelected()) R.string.url_select_none else R.string.url_select_all
        )
    }

    private fun deleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.url_delete_selected)
            .setMessage("确定删除选中的 ${ids.size} 条下载记录及本地文件？")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                downloadRepo.delete(ids)
                adapter.clearSelection()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshList() {
        val records = downloadRepo.getAll()
        adapter.submit(records)
        val empty = records.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        updateSelectionActions()
    }

    companion object {
        const val EXTRA_INSTALL_ID = "install_id"
    }
}

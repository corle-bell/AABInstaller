package com.corlebell.aabinstaller.download

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.corlebell.aabinstaller.databinding.ActivityDownloadSettingsBinding

class DownloadSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadSettingsBinding
    private lateinit var store: DownloadSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = DownloadSettingsStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val current = store.get()
        binding.switchParallel.isChecked = current.parallelEnabled
        binding.switchLargeBuffer.isChecked = current.enlargeSingleBuffer
        when (current.segmentCount) {
            2 -> binding.segment2.isChecked = true
            8 -> binding.segment8.isChecked = true
            else -> binding.segment4.isChecked = true
        }
        setSegmentsEnabled(current.parallelEnabled)

        binding.switchParallel.setOnCheckedChangeListener { _, checked ->
            setSegmentsEnabled(checked)
            persist()
        }
        binding.segmentGroup.setOnCheckedChangeListener { _, _ -> persist() }
        binding.switchLargeBuffer.setOnCheckedChangeListener { _, _ -> persist() }
    }

    private fun setSegmentsEnabled(enabled: Boolean) {
        for (i in 0 until binding.segmentGroup.childCount) {
            binding.segmentGroup.getChildAt(i).isEnabled = enabled
        }
    }

    private fun persist() {
        val segments = when (binding.segmentGroup.checkedRadioButtonId) {
            binding.segment2.id -> 2
            binding.segment8.id -> 8
            else -> 4
        }
        store.save(
            DownloadSettings(
                parallelEnabled = binding.switchParallel.isChecked,
                segmentCount = segments,
                enlargeSingleBuffer = binding.switchLargeBuffer.isChecked
            )
        )
    }
}

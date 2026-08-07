package com.corlebell.aabinstaller.signing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.corlebell.aabinstaller.databinding.ItemSigningProfileBinding

class SigningProfileAdapter(
    private val onSelect: (SigningProfile) -> Unit,
    private val onEdit: (SigningProfile) -> Unit,
    private val onDelete: (SigningProfile) -> Unit
) : RecyclerView.Adapter<SigningProfileAdapter.VH>() {

    private var items: List<SigningProfile> = emptyList()
    private var selectedId: String = SigningRepository.BUILTIN_ID

    fun submit(list: List<SigningProfile>, selectedId: String) {
        items = list
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSigningProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemSigningProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: SigningProfile) {
            binding.tvName.text = profile.name
            binding.tvDetail.text = if (profile.builtIn) {
                "内置 · 别名 ${profile.alias}"
            } else {
                "别名 ${profile.alias} · ${profile.storeType} · ${profile.keystoreFileName}"
            }
            binding.radioSelect.isChecked = profile.id == selectedId
            binding.btnDelete.isEnabled = !profile.builtIn
            binding.btnDelete.alpha = if (profile.builtIn) 0.3f else 1f
            binding.btnEdit.isEnabled = !profile.builtIn
            binding.btnEdit.alpha = if (profile.builtIn) 0.3f else 1f

            binding.radioSelect.setOnClickListener { onSelect(profile) }
            binding.root.setOnClickListener { onSelect(profile) }
            binding.btnEdit.setOnClickListener { if (!profile.builtIn) onEdit(profile) }
            binding.btnDelete.setOnClickListener { if (!profile.builtIn) onDelete(profile) }
        }
    }
}

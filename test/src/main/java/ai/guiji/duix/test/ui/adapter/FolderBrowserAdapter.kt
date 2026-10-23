package ai.guiji.duix.test.ui.adapter

import ai.guiji.duix.test.databinding.ItemFolderEntryBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FolderBrowserAdapter(
    private val onFolderSelected: (File) -> Unit,
) : RecyclerView.Adapter<FolderBrowserAdapter.ViewHolder>() {

    private val items = mutableListOf<File>()

    class ViewHolder(val binding: ItemFolderEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvFolderName.text = item.name.ifBlank { item.absolutePath }
        holder.binding.tvFolderPath.text = item.absolutePath
        holder.binding.root.setOnClickListener {
            onFolderSelected(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<File>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
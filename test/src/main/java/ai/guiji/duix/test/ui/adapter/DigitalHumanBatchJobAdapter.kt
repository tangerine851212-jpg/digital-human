package ai.guiji.duix.test.ui.adapter

import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ItemDigitalHumanBatchJobBinding
import ai.guiji.duix.test.model.BatchAudioJob
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class DigitalHumanBatchJobAdapter : RecyclerView.Adapter<DigitalHumanBatchJobAdapter.ViewHolder>() {

    private val items = mutableListOf<BatchAudioJob>()

    class ViewHolder(val binding: ItemDigitalHumanBatchJobBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDigitalHumanBatchJobBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.binding.root.context
        holder.binding.tvIndex.text = String.format(Locale.getDefault(), "%02d", position + 1)
        holder.binding.tvPath.text = item.sourceRelativePath
        holder.binding.tvStatus.text = item.statusText
        holder.binding.tvDetail.text = item.detailText
        holder.binding.tvDetail.alpha = if (item.detailText.isBlank()) 0.55f else 1f

        val statusColor = when (item.status) {
            BatchAudioJob.Status.PENDING -> R.color.warm_status_pending
            BatchAudioJob.Status.RUNNING -> R.color.warm_status_running
            BatchAudioJob.Status.COMPLETED -> R.color.warm_status_success
            BatchAudioJob.Status.FAILED -> R.color.warm_status_error
            BatchAudioJob.Status.SKIPPED -> R.color.warm_status_skipped
        }
        holder.binding.tvStatus.setTextColor(ContextCompat.getColor(context, statusColor))
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<BatchAudioJob>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateItem(index: Int, item: BatchAudioJob) {
        if (index !in items.indices) {
            return
        }
        items[index] = item
        notifyItemChanged(index)
    }
}
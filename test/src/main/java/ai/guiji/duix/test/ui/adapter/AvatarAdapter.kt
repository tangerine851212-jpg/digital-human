package ai.guiji.duix.test.ui.adapter

import ai.guiji.duix.test.databinding.ItemAvatarCardBinding
import ai.guiji.duix.test.model.AvatarModel
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class AvatarAdapter(
    private val list: List<AvatarModel>,
    private val callback: Callback,
    private val enableSelection: Boolean = false
) : RecyclerView.Adapter<AvatarAdapter.ViewHolder>() {

    private var selectedModelUrl: String? = null

    class ViewHolder(val binding: ItemAvatarCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAvatarCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = list[position]
        holder.binding.ivAvatar.setImageResource(model.avatarRes)
        holder.binding.tvName.text = model.name
        val selected = enableSelection && model.modelUrl == selectedModelUrl
        holder.binding.cardAvatar.strokeWidth = if (selected) 3 else 0
        holder.binding.cardAvatar.cardElevation = if (selected) 8f else 3f
        holder.binding.cardAvatar.alpha = if (selected) 1f else 0.96f
        holder.binding.cardAvatar.setStrokeColor(
            if (selected) {
                ContextCompat.getColor(holder.binding.root.context, ai.guiji.duix.test.R.color.warm_accent)
            } else {
                ContextCompat.getColor(holder.binding.root.context, android.R.color.transparent)
            }
        )
        holder.binding.cardAvatar.setOnClickListener {
            if (enableSelection) {
                setSelectedModel(model)
            }
            callback.onSelect(model)
        }
    }

    override fun getItemCount(): Int = list.size

    fun setSelectedModel(model: AvatarModel?) {
        selectedModelUrl = model?.modelUrl
        notifyDataSetChanged()
    }

    interface Callback {
        fun onSelect(model: AvatarModel)
    }
}

package ai.gizmo.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class ServerAdapter(
    private var servers: List<Server>,
    private val onClick: (Server) -> Unit,
    private val onLongClick: (Server, View) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    private class ServerDiffCallback(
        private val old: List<Server>,
        private val new: List<Server>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.serverName)
        val url: TextView = view.findViewById(R.id.serverUrl)
        val defaultDot: View = view.findViewById(R.id.defaultDot)
        val httpsIcon: ImageView = view.findViewById(R.id.httpsIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.name.text = server.name
        holder.url.text = server.url
        holder.defaultDot.visibility = if (server.isDefault) View.VISIBLE else View.GONE

        val isHttps = server.url.startsWith("https://")
        holder.httpsIcon.setImageResource(
            if (isHttps) android.R.drawable.ic_secure else android.R.drawable.ic_partial_secure
        )

        holder.itemView.setOnClickListener { onClick(server) }
        holder.itemView.setOnLongClickListener { view ->
            onLongClick(server, view)
            true
        }
    }

    override fun getItemCount() = servers.size

    fun updateServers(newServers: List<Server>) {
        val diff = DiffUtil.calculateDiff(ServerDiffCallback(servers, newServers))
        servers = newServers
        diff.dispatchUpdatesTo(this)
    }
}

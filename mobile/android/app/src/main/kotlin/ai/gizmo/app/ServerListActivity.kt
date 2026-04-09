package ai.gizmo.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ServerListActivity : AppCompatActivity() {

    private lateinit var manager: ServerManager
    private lateinit var adapter: ServerAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout

    private val addServerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_list)

        manager = ServerManager(this)
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        val fab = findViewById<FloatingActionButton>(R.id.fab)

        adapter = ServerAdapter(
            servers = emptyList(),
            onClick = { server -> connectToServer(server) },
            onLongClick = { server, view -> showPopupMenu(server, view) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fab.setOnClickListener { launchAddServer() }
        findViewById<View>(R.id.btnEmptyAdd).setOnClickListener { launchAddServer() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun connectToServer(server: Server) {
        startActivity(Intent(this, MainActivity::class.java).putServerExtras(server))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun showPopupMenu(server: Server, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.edit))
        popup.menu.add(0, 2, 1, getString(R.string.delete))
        if (!server.isDefault) {
            popup.menu.add(0, 3, 2, getString(R.string.set_as_default))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val intent = Intent(this, AddServerActivity::class.java).apply {
                        putExtra(Server.EXTRA_EDIT_ID, server.id)
                    }
                    addServerLauncher.launch(intent)
                    true
                }
                2 -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.delete_confirm_title))
                        .setMessage(getString(R.string.delete_confirm_message, server.name))
                        .setPositiveButton(getString(R.string.delete)) { _, _ ->
                            manager.deleteServer(server.id)
                            val remaining = manager.getServers()
                            if (remaining.isEmpty()) {
                                startActivity(Intent(this, OnboardingActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                })
                            } else {
                                refreshList()
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                    true
                }
                3 -> {
                    manager.setDefault(server.id)
                    refreshList()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun launchAddServer() {
        addServerLauncher.launch(Intent(this, AddServerActivity::class.java))
    }

    private fun refreshList() {
        val servers = manager.getServers()
        adapter.updateServers(servers)
        emptyState.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
    }
}

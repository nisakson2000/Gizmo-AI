package ai.gizmo.app

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class OnboardingActivity : AppCompatActivity() {

    private val addServerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // If a server was added during onboarding, AddServerActivity handles navigation
        // But if user pressed back, check if we should still finish
        if (ServerManager(this).getServers().isNotEmpty()) {
            val server = ServerManager(this).getDefault() ?: return@registerForActivityResult
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra("server_id", server.id)
                putExtra("server_url", server.url)
                putExtra("server_name", server.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val icon = findViewById<ImageView>(R.id.icon)
        val title = findViewById<TextView>(R.id.title)
        val subtitle = findViewById<TextView>(R.id.subtitle)
        val btnGetStarted = findViewById<MaterialButton>(R.id.btnGetStarted)

        // Sequential entrance animation
        icon.animate().alpha(1f).setDuration(300).setStartDelay(100).start()
        title.animate().alpha(1f).setDuration(300).setStartDelay(250).start()
        subtitle.animate().alpha(1f).setDuration(300).setStartDelay(250).start()
        btnGetStarted.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setStartDelay(450)
            .start()

        btnGetStarted.setOnClickListener {
            val intent = Intent(this, AddServerActivity::class.java).apply {
                putExtra("onboarding", true)
            }
            addServerLauncher.launch(intent)
        }
    }
}

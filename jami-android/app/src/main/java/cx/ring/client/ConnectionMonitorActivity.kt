/*
 *  shiroikuma.jami fork: standalone Connection-monitor activity. Hosting the monitor in its own
 *  activity (rather than as a Settings sub-fragment) means opening it from the account-dot dialog
 *  returns to the conversation list on Back, not into Settings.
 */
package cx.ring.client

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import cx.ring.R
import cx.ring.application.JamiApplication
import cx.ring.fragments.ConnectionMonitorFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ConnectionMonitorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JamiApplication.instance?.startDaemon(this)
        setContentView(R.layout.activity_connection_monitor)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Connection monitor"
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction()
                .replace(R.id.monitor_container, ConnectionMonitorFragment())
                .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

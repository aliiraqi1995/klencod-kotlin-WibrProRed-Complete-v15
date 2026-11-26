package com.alialanbaki.wibrprored

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var lvQueuedNetworks: ListView
    private lateinit var adapter: QueuedAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lvQueuedNetworks = findViewById(R.id.lvQueuedNetworks)
        adapter = QueuedAdapter()
        lvQueuedNetworks.adapter = adapter

        findViewById<Button>(R.id.btnHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAffinity()
        }

        findViewById<Button>(R.id.btnAddNetwork).setOnClickListener {
            startActivity(Intent(this, NetworkScanActivity::class.java))
        }

        lvQueuedNetworks.setOnItemClickListener { _, _, position, _ ->
            openMonitorAt(position)
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    private fun openMonitorAt(position: Int) {
        if (position in QueueStore.items.indices) {
            AppState.activeQueuedIndex = position
            startActivity(Intent(this, MonitorActivity::class.java))
        }
    }

    private inner class QueuedAdapter : BaseAdapter() {
        override fun getCount(): Int = QueueStore.items.size
        override fun getItem(position: Int): Any = QueueStore.items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.row_queued_network, parent, false)

            val item = QueueStore.items[position]
            val btnDelete = view.findViewById<ImageButton>(R.id.btnDeleteQueued)
            val infoContainer = view.findViewById<View>(R.id.rowInfoContainer)
            val tvSsid = view.findViewById<TextView>(R.id.tvRowSsid)
            val tvSubline = view.findViewById<TextView>(R.id.tvRowSubline)
            val btnTogglePause = view.findViewById<ImageButton>(R.id.btnToggleQueuedPause)

            tvSsid.text = item.ssid

            val total = item.passwords.size.coerceAtLeast(1)
            val pct = if (item.isConnected) {
                100
            } else {
                ((item.currentIndex.coerceAtMost(total) * 100) / total).coerceIn(0, 100)
            }
            val secShort = extractSecurityLabel(item.capabilities)
            val statusTag = when {
                item.isConnected -> "متصل ($pct%)"
                item.isPaused -> "متوقف ($pct%)"
                else -> "$pct%"
            }
            tvSubline.text = "$secShort  •  قوة: ${item.signalLevel}%  •  $statusTag"

            btnDelete.setOnClickListener {
                QueueStore.removeAt(position)
                notifyDataSetChanged()
            }

            if (item.isConnected) {
                btnTogglePause.setImageResource(android.R.drawable.ic_lock_idle_lock)
                btnTogglePause.alpha = 0.5f
                btnTogglePause.setOnClickListener { openMonitorAt(position) }
            } else if (item.isPaused) {
                btnTogglePause.setImageResource(android.R.drawable.ic_media_play)
                btnTogglePause.alpha = 1.0f
                btnTogglePause.setOnClickListener {
                    val cur = QueueStore.items.getOrNull(position) ?: return@setOnClickListener
                    QueueStore.items[position] = cur.copy(isPaused = false)
                    notifyDataSetChanged()
                }
            } else {
                btnTogglePause.setImageResource(android.R.drawable.ic_media_pause)
                btnTogglePause.alpha = 1.0f
                btnTogglePause.setOnClickListener {
                    val cur = QueueStore.items.getOrNull(position) ?: return@setOnClickListener
                    QueueStore.items[position] = cur.copy(isPaused = true)
                    notifyDataSetChanged()
                }
            }

            infoContainer.setOnClickListener { openMonitorAt(position) }
            view.setOnClickListener { openMonitorAt(position) }

            return view
        }

        private fun extractSecurityLabel(caps: String): String {
            val upper = caps.uppercase()
            return when {
                upper.contains("WPA3") -> "WPA3"
                upper.contains("WPA2") -> "WPA2"
                upper.contains("WPA") -> "WPA"
                upper.contains("WEP") -> "WEP"
                upper.contains("WPS") -> "WPS"
                upper.isBlank() || upper.contains("OPEN") -> "OPEN"
                else -> caps.substringBefore("]").removePrefix("[").take(14)
            }
        }
    }
}
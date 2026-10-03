package ghostlink.lab.android

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ghostlink.lab.benchmark.SessionMeta
import ghostlink.lab.benchmark.TransmitterSession

/**
 * GHOSTLINK LINK LAB — one app, two roles (brief §13):
 * TRANSMITTER (phone A) shows the AUTO BENCHMARK frames; RECEIVER (phone B) records everything.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: LabPrefs
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LabPrefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }
        col.addView(TextView(this).apply { text = "GHOSTLINK LINK LAB  (M1)"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply { text = "Airplane mode ON, Wi-Fi/BT/NFC OFF. Phone A = TRANSMITTER, phone B = RECEIVER."; setPadding(0, 8, 0, 24) })

        col.addView(button("TRANSMITTER") { startActivity(Intent(this, TransmitterActivity::class.java)) })
        col.addView(button("RECEIVER") { startActivity(Intent(this, ReceiverActivity::class.java)) })
        col.addView(button("EXPORT RESULTS (latest session)") { exportLatest() })
        col.addView(button("SCAN PLAN FROM RECEIVER (stage 2 → 3)") {
            startActivity(Intent(this, ReceiverActivity::class.java).putExtra(ReceiverActivity.EXTRA_PLAN_SCAN, true))
        })
        col.addView(button("SAVE DEVICE PROFILE") {
            val dir = LabFiles.newSessionDir(this, "profile_${DeviceProfile.deviceId()}")
            LabFiles.writeJson(dir, "device_profile.json", DeviceProfile.collect(this))
            toast("Saved ${dir.absolutePath}/device_profile.json")
        })

        col.addView(header("Transmitter plan"))
        col.addView(spinner(LabPrefs.PLANS, prefs.plan) { prefs.plan = it; refreshInfo() })
        col.addView(header("Session labels (set on the RECEIVER phone)"))
        col.addView(labeled("Distance cm", SessionMeta.DISTANCES_CM.map { it.toString() } + listOf("10", "30", "50", "80", "300"), prefs.distanceCm.toString()) { prefs.distanceCm = it.toInt() })
        col.addView(labeled("Angle °", SessionMeta.ANGLES_DEG.map { it.toString() }, prefs.angleDeg.toString()) { prefs.angleDeg = it.toInt() })
        col.addView(labeled("Light", SessionMeta.LIGHT_CLASSES, prefs.lightClass) { prefs.lightClass = it })
        col.addView(labeled("Motion", SessionMeta.MOTION_CLASSES, prefs.motion) { prefs.motion = it })
        col.addView(header("Receiver camera / decoder"))
        col.addView(labeled("QR decoder", LabPrefs.QR_DECODERS, prefs.qrDecoder) { prefs.qrDecoder = it })
        col.addView(labeled("Exposure", LabPrefs.EXPOSURES, prefs.exposure) { prefs.exposure = it })
        col.addView(labeled("Analysis res", LabPrefs.RESOLUTIONS, prefs.resolution) { prefs.resolution = it })
        col.addView(labeled("Zoom", LabPrefs.ZOOMS.map { it.toString() }, prefs.zoom.toString()) { prefs.zoom = it.toFloat() })
        col.addView(EditText(this).apply {
            hint = "notes (optional)"
            setText(prefs.notes)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { prefs.notes = s?.toString() ?: "" }
            })
        })
        info = TextView(this).apply { setPadding(0, 24, 0, 0) }
        col.addView(info)
        setContentView(ScrollView(this).apply { addView(col) })
        refreshInfo()
    }

    override fun onResume() { super.onResume(); refreshInfo() }

    private fun refreshInfo() {
        if (!::info.isInitialized) return
        val top = prefs.topConfigs().joinToString("\n") { "  • ${it.configKey()}" }
        val plan = TransmitterActivity.buildPlan(prefs)
        info.text = "Plan: ${TransmitterSession.describe(plan)} — ${plan.size} trials, ~${(plan.sumOf { it.maxDurationMs } / 60000.0).let { "%.1f".format(it) }} min\n" +
            "Top configs (${if (prefs.topConfigsHex.isEmpty()) "defaults" else "from receiver plan QR"}):\n$top\n" +
            "Device: ${DeviceProfile.model()}"
    }

    private fun exportLatest() {
        val dir = LabFiles.latestSession(this) ?: return toast("No session yet")
        try {
            toast("Exported to ${LabFiles.export(this, dir)}")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun header(s: String) = TextView(this).apply { text = s; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 32, 0, 8) }

    private fun labeled(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = label; minWidth = 260 })
        row.addView(spinner(options.distinct(), selected, onSelect))
        return row
    }

    private fun spinner(options: List<String>, selected: String, onSelect: (String) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(options.indexOf(selected).coerceAtLeast(0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(options[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

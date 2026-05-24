package com.marchsnow.midibridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.midi.MidiDeviceInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.marchsnow.midibridge.R
import com.marchsnow.midibridge.databinding.ActivityMainBinding
import com.marchsnow.midibridge.service.MidiBridgeService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MidiBridgeViewModel

    // Prevent TextWatcher from firing when ViewModel pushes values back to form fields
    private var suppressConfigWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[MidiBridgeViewModel::class.java]

        // Start the foreground service (service auto-starts the bridge)
        ContextCompat.startForegroundService(
            this, Intent(this, MidiBridgeService::class.java).apply {
                action = MidiBridgeService.ACTION_START
            }
        )

        setupActionButtons()
        setupConfigInputs()
        setupKeepAliveControls()
        setupDeviceSpinner()
        setupIpClickCopy()
        observeViewModel()
    }

    // ─── Action buttons ───

    private fun setupActionButtons() {
        binding.btnStart.setOnClickListener {
            val isRunning = viewModel.uiState.value?.isRunning == true
            if (isRunning) {
                viewModel.stopServer()
            } else {
                viewModel.startServer()
            }
        }

        // Save button: initially disabled, activated when there are unsaved changes
        binding.btnSave.isEnabled = false
        binding.btnSave.setOnClickListener {
            viewModel.saveAndRestart()
        }
    }

    // ─── Config input listeners ───

    private fun setupConfigInputs() {
        // WS Port
        binding.etPort.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!suppressConfigWatchers) viewModel.onPortChanged(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // IP allowlist
        binding.etAllowedIPs.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!suppressConfigWatchers) viewModel.onAllowedIPsChanged(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Password
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!suppressConfigWatchers) viewModel.onPasswordChanged(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // MIDI verbose toggle
        binding.switchMidiVerbose.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressConfigWatchers) viewModel.onMidiVerboseChanged(isChecked)
        }
    }

    // ─── Keep-alive controls ───

    private fun setupKeepAliveControls() {
        binding.switchKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleVideoKeepAlive(isChecked)
        }
    }

    // ─── Device spinner ───

    private fun setupDeviceSpinner() {
        binding.spinnerDevice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val devices = viewModel.uiState.value?.availableDevices ?: return
                    if (position in devices.indices) {
                        viewModel.selectDevice(devices[position])
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    // ─── IP click-to-copy ───

    private fun setupIpClickCopy() {
        binding.tvIpAddresses.setOnClickListener {
            val ip = binding.tvIpAddresses.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", ip))
            Toast.makeText(this, "IP copied", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Observe ViewModel ───

    private fun observeViewModel() {
        // Runtime state
        viewModel.uiState.observe(this) { state ->
            binding.statusDot.setColorFilter(
                ContextCompat.getColor(
                    this,
                    if (state.isRunning) R.color.status_green else R.color.status_gray
                )
            )
            binding.tvStatus.text = if (state.isRunning) "Running" else "Stopped"
            binding.tvPortClients.text = "Port ${state.wsPort} | ${state.clientCount} clients"
            binding.tvIpAddresses.text = state.localIPs.joinToString("  ")
            binding.tvMidiStatus.text = if (state.midiConnected) "♫ MIDI connected" else "✗ No MIDI device"

            // Start/Stop button label
            binding.btnStart.text = if (state.isRunning) "Stop" else "Start"

            // Client list
            val clientText = state.clients.joinToString("\n") { client ->
                val prefix = if (client.authenticated) "✓" else "⏳"
                "$prefix ${client.ip}"
            }
            binding.tvClients.text = clientText

            // Device spinner — only rebuild adapter when list actually changed
            val currentAdapter = binding.spinnerDevice.adapter
            val deviceNames = state.availableDevices.map {
                it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown Device"
            }
            val needRebuild = currentAdapter == null ||
                    currentAdapter.count != deviceNames.size ||
                    (0 until currentAdapter.count).any { currentAdapter.getItem(it) != deviceNames[it] }

            if (needRebuild) {
                // Temporarily clear listener to avoid triggering selectDevice on adapter rebuild
                binding.spinnerDevice.onItemSelectedListener = null
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerDevice.adapter = adapter
                // Restore selected device position
                val selectedIdx = state.availableDevices.indexOf(state.selectedDevice)
                if (selectedIdx >= 0) {
                    binding.spinnerDevice.setSelection(selectedIdx)
                }
                setupDeviceSpinner() // re-attach listener
            }

            // Log panel — only refresh when content changed
            if (state.logs != binding.tvLogs.text.toString()) {
                binding.tvLogs.text = state.logs
                binding.scrollLogs.post {
                    binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }

            // Keep-alive toggle
            if (binding.switchKeepAlive.isChecked != state.keepAliveOn) {
                binding.switchKeepAlive.isChecked = state.keepAliveOn
            }
        }

        // Config edit state
        viewModel.configEdit.observe(this) { editState ->
            // Use suppressConfigWatchers to prevent TextWatcher feedback loop
            suppressConfigWatchers = true
            if (binding.etPort.text.toString() != editState.wsPort) {
                binding.etPort.setText(editState.wsPort)
            }
            if (binding.etAllowedIPs.text.toString() != editState.allowedIPs) {
                binding.etAllowedIPs.setText(editState.allowedIPs)
            }
            if (binding.etPassword.text.toString() != editState.password) {
                binding.etPassword.setText(editState.password)
            }
            if (binding.switchMidiVerbose.isChecked != editState.midiVerbose) {
                binding.switchMidiVerbose.isChecked = editState.midiVerbose
            }
            suppressConfigWatchers = false

            // Save button enabled ↔ hasUnsavedChanges
            binding.btnSave.isEnabled = editState.hasUnsavedChanges
            binding.btnSave.alpha = if (editState.hasUnsavedChanges) 1.0f else 0.4f
        }

        // One-shot events
        viewModel.uiEvent.observe(this) { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                }
                is UiEvent.ValidationError -> {
                    Toast.makeText(this, "${event.field}: ${event.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop the service — it's a foreground service that outlives the Activity
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // USB device attached — refresh MIDI device list
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.refreshDeviceList()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh device list each time Activity comes to foreground
        viewModel.refreshDeviceList()
    }
}

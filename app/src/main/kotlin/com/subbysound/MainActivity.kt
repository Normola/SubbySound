package com.subbysound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.subbysound.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = AudioEngine()
    private lateinit var recordingFile: File

    private val PERM_REQUEST = 1001
    private val SHIFT_CENTER = 10000   // center of seekbar = 0 Hz shift
    private val SHIFT_MAX_HZ = 20000f  // ±20 kHz shift range

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordingFile = File(filesDir, "recording.wav")

        setupSpectrogram()
        setupButtons()
        setupShiftSeekBar()
        updateUiForState(AudioEngine.State.IDLE)
    }

    private fun setupSpectrogram() {
        binding.spectrogramView.setSampleRate(AudioEngine.SAMPLE_RATE)

        engine.onFFTData = { mags ->
            binding.spectrogramView.addFrame(mags)
        }

        engine.onPlaybackComplete = {
            runOnUiThread {
                engine.stop()
                updateUiForState(AudioEngine.State.IDLE)
                Toast.makeText(this, "Playback complete", Toast.LENGTH_SHORT).show()
            }
        }

        binding.spectrogramView.onSelectionChanged = { lowHz, highHz ->
            engine.filterLowHz = lowHz
            engine.filterHighHz = highHz
            engine.filterEnabled = true
            val lo = if (lowHz >= 1000) "%.1f kHz".format(lowHz / 1000) else "%.0f Hz".format(lowHz)
            val hi = if (highHz >= 1000) "%.1f kHz".format(highHz / 1000) else "%.0f Hz".format(highHz)
            binding.selectionText.text = "Selected: $lo – $hi"
        }

        binding.spectrogramView.onSelectionCleared = {
            engine.filterEnabled = false
            binding.selectionText.text = getString(R.string.drag_to_select)
        }
    }

    private fun setupButtons() {
        binding.recordButton.setOnClickListener {
            if (checkAudioPermission()) startRecording()
        }
        binding.stopButton.setOnClickListener {
            val wasPlaying = engine.state == AudioEngine.State.PLAYING
            engine.stop()
            updateUiForState(AudioEngine.State.IDLE)
            if (!wasPlaying) {
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show()
            }
        }
        binding.playButton.setOnClickListener {
            if (recordingFile.exists() && recordingFile.length() > 44) {
                startPlayback()
            } else {
                Toast.makeText(this, "No recording yet", Toast.LENGTH_SHORT).show()
            }
        }
        binding.clearSelectionButton.setOnClickListener {
            binding.spectrogramView.clearSelection()
        }
    }

    private fun setupShiftSeekBar() {
        binding.shiftSeekBar.max = SHIFT_CENTER * 2
        binding.shiftSeekBar.progress = SHIFT_CENTER

        binding.shiftSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val shiftHz = (progress - SHIFT_CENTER).toFloat() / SHIFT_CENTER * SHIFT_MAX_HZ
                engine.freqShiftHz = shiftHz
                val sign = if (shiftHz >= 0) "+" else ""
                val label = if (kotlin.math.abs(shiftHz) >= 1000)
                    "$sign%.1f kHz".format(shiftHz / 1000)
                else
                    "$sign%.0f Hz".format(shiftHz)
                binding.shiftValueText.text = "Shift: $label"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun startRecording() {
        binding.spectrogramView.clearSpectrogram()
        engine.startRecording(recordingFile)
        updateUiForState(AudioEngine.State.RECORDING)
    }

    private fun startPlayback() {
        binding.spectrogramView.clearSpectrogram()
        engine.startPlayback(recordingFile)
        updateUiForState(AudioEngine.State.PLAYING)
    }

    private fun updateUiForState(state: AudioEngine.State) {
        when (state) {
            AudioEngine.State.IDLE -> {
                binding.recordButton.visibility = View.VISIBLE
                binding.stopButton.visibility = View.GONE
                binding.playButton.visibility = View.VISIBLE
                binding.filterControls.visibility = View.GONE
                binding.statusText.text = if (recordingFile.exists() && recordingFile.length() > 44)
                    getString(R.string.status_idle_has_recording)
                else
                    getString(R.string.status_idle)
            }
            AudioEngine.State.RECORDING -> {
                binding.recordButton.visibility = View.GONE
                binding.stopButton.visibility = View.VISIBLE
                binding.playButton.visibility = View.GONE
                binding.filterControls.visibility = View.GONE
                binding.statusText.text = getString(R.string.status_recording)
            }
            AudioEngine.State.PLAYING -> {
                binding.recordButton.visibility = View.GONE
                binding.stopButton.visibility = View.VISIBLE
                binding.playButton.visibility = View.GONE
                binding.filterControls.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.status_playing)
            }
        }
    }

    private fun checkAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQUEST)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}

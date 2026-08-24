package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ir.factory.entryexit.data.Checkpoint
import ir.factory.entryexit.data.Session
import ir.factory.entryexit.databinding.ActivityCheckpointPickerBinding
import ir.factory.entryexit.util.AnimUtils

/**
 * Shown once right after a guard signs in (see [LoginActivity]). Admins skip this screen
 * entirely — only guards need to say which post they're covering, since only their home menu
 * and their logged events get filtered/tagged by it (see [MainActivity]'s home-menu filtering
 * and [ir.factory.entryexit.viewmodel.FactoryViewModel]'s checkpoint-tagging of every write).
 */
class CheckpointPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheckpointPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckpointPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardGate.setOnClickListener { proceed(Checkpoint.GATE) }
        binding.cardParking.setOnClickListener { proceed(Checkpoint.PARKING) }
        AnimUtils.startCoinSpin(binding.ivCheckpointLogo)
        AnimUtils.applyPressFeedback(binding.cardGate)
        AnimUtils.applyPressFeedback(binding.cardParking)
    }

    private fun proceed(checkpoint: Checkpoint) {
        Session.setCheckpoint(checkpoint)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

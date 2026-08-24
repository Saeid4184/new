package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import ir.factory.entryexit.R
import ir.factory.entryexit.data.AuthRepository
import ir.factory.entryexit.data.Checkpoint
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.Session
import ir.factory.entryexit.databinding.ActivityMainBinding
import ir.factory.entryexit.util.AnimUtils
import ir.factory.entryexit.util.NetworkMonitor
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Home screen: a menu of the app's 7 sections (Personnel, Machinery, Visitors, Drivers,
 *  Weekly Inspection, Goods entry/exit, Security Reports). Tapping a card opens that section
 *  full screen in [CategoryDetailActivity] — replaces the old swipeable tab pager with an
 *  explicit menu-first navigation model. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authRepository = AuthRepository(this)

        // Defensive: if this Activity is somehow reached without a signed-in session
        // (session cleared, process restarted oddly), bounce back to the login screen.
        if (!Session.isSignedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // Title/subtitle are drawn by our own TextViews inside the Toolbar now (see
        // activity_main.xml) instead of supportActionBar.title/logo, so the logo can be a real
        // View we keep spinning — supportActionBar is still set above purely so the options
        // menu (⋮) keeps working.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.tvToolbarTitle.text = getString(R.string.app_name)
        AnimUtils.startCoinSpin(binding.ivToolbarLogo)

        Session.currentUser?.let { user ->
            binding.tvToolbarSubtitle.text = getString(R.string.signed_in_as_format, user.name, user.role.displayName)
            binding.tvToolbarSubtitle.visibility = android.view.View.VISIBLE
        }

        binding.rvHomeMenu.layoutManager = LinearLayoutManager(this)
        binding.rvHomeMenu.adapter = HomeMenuAdapter(homeMenuItems) { item ->
            CategoryDetailActivity.start(this, item.destination)
        }
        AnimUtils.runLayoutAnimation(binding.rvHomeMenu)

        // Jump straight into a section, e.g. from a launcher shortcut or global search.
        intent?.getStringExtra(EXTRA_JUMP_TO_TYPE)?.let { typeName ->
            runCatching { PersonType.valueOf(typeName) }.getOrNull()?.let { type ->
                CategoryDetailActivity.start(this, CategoryDetailActivity.destinationFor(type))
            }
        }

        observeConnectivity()
    }

    /** Gate guards control every person/vehicle entering the factory; parking guards only deal
     *  with what happens inside the internal parking area. Admins aren't filtered — they pick
     *  no checkpoint at login, so [Session.currentCheckpoint] is null for them and every card
     *  shows. Machinery and Report show for both posts since a vehicle can be logged at either
     *  boundary and incident reports aren't tied to a location. Weekly machinery inspection
     *  ("بازدید ماشین آلات") only shows at the internal parking post, since that's where the
     *  fleet actually sits. */
    private val homeMenuItems: List<HomeMenuItem> by lazy {
        val all = listOf(
            HomeMenuItem(
                HomeDestination.PERSONNEL,
                R.string.category_personnel, R.string.home_subtitle_personnel,
                R.drawable.ic_personnel, R.color.accent_personnel, R.color.accent_personnel_bg
            ),
            HomeMenuItem(
                HomeDestination.MACHINERY,
                R.string.category_machinery,
                if (Session.currentCheckpoint == Checkpoint.PARKING) R.string.home_subtitle_machinery_parking else R.string.home_subtitle_machinery,
                R.drawable.ic_machinery, R.color.accent_machinery, R.color.accent_machinery_bg
            ),
            HomeMenuItem(
                HomeDestination.VISITOR,
                R.string.category_visitor,
                if (Session.currentCheckpoint == Checkpoint.PARKING) R.string.home_subtitle_visitor_parking else R.string.home_subtitle_visitor,
                R.drawable.ic_visitor, R.color.accent_visitor, R.color.accent_visitor_bg
            ),
            HomeMenuItem(
                HomeDestination.DRIVER,
                R.string.category_driver, R.string.home_subtitle_driver,
                R.drawable.ic_driver, R.color.accent_driver, R.color.accent_driver_bg
            ),
            HomeMenuItem(
                HomeDestination.INSPECTION,
                R.string.category_inspection, R.string.home_subtitle_inspection,
                R.drawable.ic_inspection, R.color.accent_inspection, R.color.accent_inspection_bg
            ),
            HomeMenuItem(
                HomeDestination.ITEM_LOG,
                R.string.category_item_log,
                if (Session.currentCheckpoint == Checkpoint.PARKING) R.string.home_subtitle_item_log_parking else R.string.home_subtitle_item_log,
                R.drawable.ic_inventory, R.color.accent_item_log, R.color.accent_item_log_bg
            ),
            HomeMenuItem(
                HomeDestination.REPORT,
                R.string.report_tab_title, R.string.home_subtitle_report,
                R.drawable.ic_report, R.color.accent_report, R.color.accent_report_bg
            )
        )

        when (Session.currentCheckpoint) {
            Checkpoint.GATE -> all.filter {
                it.destination in setOf(
                    HomeDestination.PERSONNEL, HomeDestination.VISITOR, HomeDestination.DRIVER,
                    HomeDestination.MACHINERY, HomeDestination.REPORT
                )
            }
            Checkpoint.PARKING -> all.filter {
                it.destination in setOf(
                    HomeDestination.MACHINERY, HomeDestination.VISITOR, HomeDestination.INSPECTION,
                    HomeDestination.ITEM_LOG, HomeDestination.REPORT
                )
            }
            null -> all // admin, or a guard session somehow without a checkpoint — show everything
        }
    }

    /**
     * Shows the offline banner whenever there's no connection, so guards know that check-ins,
     * check-outs, item logs, and inspections they record right now are being saved on the
     * device (see [ir.factory.entryexit.data.Repository]) and will reach the server on their
     * own once the connection is back — nothing needs to be redone or resent manually.
     */
    private fun observeConnectivity() {
        lifecycleScope.launch {
            NetworkMonitor.observe(applicationContext)
                .drop(1) // the first emission is just the state at launch; no need to announce it
                .collect { online ->
                    binding.tvOfflineBanner.visibility = if (online) android.view.View.GONE else android.view.View.VISIBLE
                    if (online) {
                        // Just reconnected — confirm that whatever was saved offline is now
                        // going up to the server.
                        Toast.makeText(this@MainActivity, R.string.online_synced_toast, Toast.LENGTH_SHORT).show()
                        FirebaseFirestore.getInstance().waitForPendingWrites()
                    }
                }
        }
        binding.tvOfflineBanner.visibility =
            if (NetworkMonitor.isOnline(applicationContext)) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Dashboard/Reports/Setup/Settings/Web-panel are admin-only; guards only get
        // check-in/out + roster management (the 4 categories) and search.
        val isAdmin = Session.isAdmin()
        menu.findItem(R.id.action_dashboard)?.isVisible = isAdmin
        menu.findItem(R.id.action_setup)?.isVisible = isAdmin
        menu.findItem(R.id.action_settings)?.isVisible = isAdmin
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_dashboard -> {
                startActivity(Intent(this, AdminDashboardActivity::class.java))
                true
            }
            R.id.action_search -> {
                startActivity(Intent(this, GlobalSearchActivity::class.java))
                true
            }
            R.id.action_setup -> {
                startActivity(Intent(this, SetupActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_my_logs -> {
                startActivity(Intent(this, MyLogsActivity::class.java))
                true
            }
            R.id.action_sign_out -> {
                confirmSignOut()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_confirm_title)
            .setMessage(R.string.sign_out_confirm_message)
            .setPositiveButton(R.string.menu_sign_out) { _, _ ->
                authRepository.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_JUMP_TO_TYPE = "extra_jump_to_type"
    }
}

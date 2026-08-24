package ir.factory.entryexit.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import ir.factory.entryexit.R
import ir.factory.entryexit.data.Checkpoint
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.Session
import ir.factory.entryexit.databinding.ActivityCategoryDetailBinding
import ir.factory.entryexit.ui.fragments.CategoryFragment
import ir.factory.entryexit.ui.fragments.InspectionListFragment
import ir.factory.entryexit.ui.fragments.ItemLogListFragment
import ir.factory.entryexit.ui.fragments.ParkingMachineryFragment
import ir.factory.entryexit.ui.fragments.ReportListFragment

/** Opened from the home-menu grid (or a search/shortcut jump) to show exactly one section full
 *  screen — the same fragments that used to live inside MainActivity's tab pager, now reached
 *  through a menu instead of a swipe. */
class CategoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val destination = intent?.getStringExtra(EXTRA_DESTINATION)
            ?.let { runCatching { HomeDestination.valueOf(it) }.getOrNull() }
            ?: run { finish(); return }

        binding.toolbar.title = getString(titleFor(destination))
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragmentFor(destination))
                .commit()
        }
    }

    private fun titleFor(destination: HomeDestination): Int = when (destination) {
        HomeDestination.PERSONNEL -> R.string.category_personnel
        HomeDestination.MACHINERY -> R.string.category_machinery
        HomeDestination.VISITOR -> R.string.category_visitor
        HomeDestination.DRIVER -> R.string.category_driver
        HomeDestination.INSPECTION -> R.string.category_inspection
        HomeDestination.ITEM_LOG -> R.string.category_item_log
        HomeDestination.REPORT -> R.string.report_tab_title
    }

    private fun fragmentFor(destination: HomeDestination): Fragment = when (destination) {
        HomeDestination.PERSONNEL -> CategoryFragment.newInstance(PersonType.PERSONNEL)
        HomeDestination.MACHINERY ->
            if (Session.currentCheckpoint == Checkpoint.PARKING) ParkingMachineryFragment()
            else CategoryFragment.newInstance(PersonType.MACHINERY)
        HomeDestination.VISITOR -> CategoryFragment.newInstance(PersonType.VISITOR)
        HomeDestination.DRIVER -> CategoryFragment.newInstance(PersonType.DRIVER)
        HomeDestination.INSPECTION -> InspectionListFragment()
        HomeDestination.ITEM_LOG -> ItemLogListFragment()
        HomeDestination.REPORT -> ReportListFragment()
    }

    companion object {
        const val EXTRA_DESTINATION = "extra_destination"

        fun start(context: Context, destination: HomeDestination) {
            context.startActivity(
                Intent(context, CategoryDetailActivity::class.java)
                    .putExtra(EXTRA_DESTINATION, destination.name)
            )
        }

        fun destinationFor(type: PersonType): HomeDestination = when (type) {
            PersonType.PERSONNEL -> HomeDestination.PERSONNEL
            PersonType.MACHINERY -> HomeDestination.MACHINERY
            PersonType.VISITOR -> HomeDestination.VISITOR
            PersonType.DRIVER -> HomeDestination.DRIVER
        }
    }
}

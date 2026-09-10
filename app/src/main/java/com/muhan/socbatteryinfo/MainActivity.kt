package com.muhan.socbatteryinfo

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.muhan.socbatteryinfo.fragment.BatteryFragment
import com.muhan.socbatteryinfo.fragment.OptimizeFragment
import com.muhan.socbatteryinfo.fragment.OverviewFragment
import com.muhan.socbatteryinfo.fragment.SatelliteFragment
import com.muhan.socbatteryinfo.fragment.ScreenFragment
import com.muhan.socbatteryinfo.fragment.SocFragment
import com.muhan.socbatteryinfo.fragment.StorageFragment
import com.muhan.socbatteryinfo.fragment.SystemFragment
import com.muhan.socbatteryinfo.util.Prefs
import com.muhan.socbatteryinfo.util.RootShell
import com.muhan.socbatteryinfo.util.ShizukuShell

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = ViewPagerAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_optimize)
                1 -> getString(R.string.overview_tab)
                2 -> getString(R.string.tab_soc)
                3 -> getString(R.string.tab_system)
                4 -> getString(R.string.tab_battery)
                5 -> getString(R.string.tab_screen)
                6 -> getString(R.string.tab_storage)
                else -> getString(R.string.satellite_tab)
            }
        }.attach()

        requestRoot()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 每次启动自动申请 Root，并按 Root / Shizuku 缺失组合给出提示 */
    private fun requestRoot() {
        Thread {
            val root = RootShell.requestRoot()
            runOnUiThread {
                Prefs.setRootGranted(this@MainActivity, root)
                val shizuku = ShizukuShell.isGranted()
                val msgRes = when {
                    !root && !shizuku -> R.string.missing_both_toast
                    !root && shizuku -> R.string.missing_root_toast
                    root && !shizuku -> R.string.missing_shizuku_toast
                    else -> null
                }
                if (msgRes != null) {
                    Toast.makeText(this@MainActivity, msgRes, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private class ViewPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 8

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> OptimizeFragment()
                1 -> OverviewFragment()
                2 -> SocFragment()
                3 -> SystemFragment()
                4 -> BatteryFragment()
                5 -> ScreenFragment()
                6 -> StorageFragment()
                else -> SatelliteFragment()
            }
        }
    }
}

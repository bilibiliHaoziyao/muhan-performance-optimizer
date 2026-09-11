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
import com.google.android.material.bottomnavigation.BottomNavigationView
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
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        viewPager.adapter = ViewPagerAdapter(this)
        // 禁用左右滑动切换Tab，只允许通过底部导航点击切换
        viewPager.isUserInputEnabled = false

        // 底部导航切换页面（所有8个页面都在底部导航里）
        bottomNav.setOnItemSelectedListener { item ->
            viewPager.setCurrentItem(
                when (item.itemId) {
                    R.id.nav_optimize -> 0
                    R.id.nav_overview -> 1
                    R.id.nav_storage -> 2
                    R.id.nav_system -> 3
                    R.id.nav_screen -> 4
                    R.id.nav_soc -> 5
                    R.id.nav_battery -> 6
                    R.id.nav_satellite -> 7
                    else -> 0
                },
                false
            )
            true
        }

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

    /** 每次启动自动申请 Root；权限缺失提示仅首次展示，避免每次进入都打扰 */
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
                if (msgRes != null && !Prefs.isPermHintShown(this@MainActivity)) {
                    Prefs.setPermHintShown(this@MainActivity)
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
                2 -> StorageFragment()
                3 -> SystemFragment()
                4 -> ScreenFragment()
                5 -> SocFragment()
                6 -> BatteryFragment()
                7 -> SatelliteFragment()
                else -> OptimizeFragment()
            }
        }
    }
}

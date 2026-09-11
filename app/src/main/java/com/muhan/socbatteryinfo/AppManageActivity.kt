package com.muhan.socbatteryinfo

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.muhan.socbatteryinfo.util.RootShell
import com.muhan.socbatteryinfo.util.ShellExec
import com.muhan.socbatteryinfo.util.ShizukuShell
import java.util.concurrent.Executors

/**
 * 应用管理（卸载）：
 * 列出所有第三方应用（不显示系统应用）。
 * 已授权 Root / Shizuku 时使用 Shell 权限执行 pm uninstall（带二次确认）；
 * 否则跳转系统卸载界面。
 */
class AppManageActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_app_manage)

        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.app_manage_title)

        container = findViewById(R.id.llAppContainer)
        val hint = TextView(this).apply {
            text = getString(R.string.app_manage_loading)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, dp(24f), 0, dp(24f))
        }
        container.addView(hint)

        loadApps()
    }

    private fun loadApps() {
        executor.execute {
            val apps = queryThirdPartyApps()
            runOnUiThread {
                container.removeAllViews()
                if (apps.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = getString(R.string.app_manage_empty)
                        setTextColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant
                            )
                        )
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setPadding(0, dp(32f), 0, dp(32f))
                    }
                    container.addView(empty)
                    return@runOnUiThread
                }
                for (app in apps) {
                    container.addView(createRow(app))
                }
            }
        }
    }

    private fun queryThirdPartyApps(): List<AppEntry> {
        return try {
            val pm = packageManager
            pm.getInstalledApplications(android.content.pm.ApplicationInfo.FLAG_INSTALLED or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
                .filter { app ->
                    // 必须有安装路径（排除未完成安装的）
                    app.sourceDir != null &&
                    app.packageName != packageName &&
                    // 第三方应用：非系统应用或更新过的系统应用
                    ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                }
                .map { app ->
                    AppEntry(
                        app.packageName,
                        pm.getApplicationLabel(app).toString(),
                        try {
                            pm.getApplicationIcon(app.packageName)
                        } catch (_: Exception) {
                            null
                        }
                    )
                }
                .sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun createRow(app: AppEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            setImageDrawable(app.icon)
        }
        row.addView(icon)

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(12f)
            }
        }

        val label = TextView(this).apply {
            text = app.label
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        textBox.addView(label)

        val pkg = TextView(this).apply {
            text = app.pkg
            textSize = 12f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        textBox.addView(pkg)
        row.addView(textBox)

        val btn = Button(this).apply {
            text = getString(R.string.app_manage_uninstall)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8f)
            }
            setOnClickListener { onUninstallClick(app) }
        }
        row.addView(btn)

        return row
    }

    private fun onUninstallClick(app: AppEntry) {
        val shellGranted = RootShell.granted || ShizukuShell.isGranted()
        if (!shellGranted) {
            // 无 Shell 权限：跳转系统卸载界面
            Toast.makeText(this, R.string.app_manage_no_perm, Toast.LENGTH_SHORT).show()
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_DELETE,
                        Uri.parse("package:${app.pkg}")
                    )
                )
            } catch (_: Exception) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${app.pkg}")
                        )
                    )
                } catch (_: Exception) {
                }
            }
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.app_manage_confirm_title)
            .setMessage(getString(R.string.app_manage_confirm_message, app.label))
            .setPositiveButton(R.string.app_manage_uninstall) { _, _ ->
                uninstallWithShell(app)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uninstallWithShell(app: AppEntry) {
        val toast = Toast.makeText(this, R.string.app_manage_uninstalling, Toast.LENGTH_LONG)
        toast.show()
        executor.execute {
            val result = ShellExec.exec("pm uninstall --user 0 ${app.pkg}")
                ?: ShellExec.exec("pm uninstall ${app.pkg}")
            val ok = result?.contains("Success", ignoreCase = true) == true
            runOnUiThread {
                toast.cancel()
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.app_manage_success, app.label)
                    else getString(R.string.app_manage_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) {
                    loadApps()
                }
            }
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

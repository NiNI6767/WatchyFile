package com.example.testyfile

import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<String>()
    private lateinit var webViewApp: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ініціалізація елементів інтерфейсу
        val etRepoUrl = findViewById<EditText>(R.id.etRepoUrl)
        val btnAddApp = findViewById<Button>(R.id.btnAddApp)
        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        webViewApp = findViewById(R.id.webViewApp)

        // Налаштування WebView
        webViewApp.webViewClient = WebViewClient()
        webViewApp.settings.javaScriptEnabled = true
        webViewApp.settings.domStorageEnabled = true

        // Налаштування списку репозиторіїв
        loadApps()
        adapter = AppAdapter(appList) { url -> startRepoAnalysis(url) }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        // Додавання нового посилання
        btnAddApp.setOnClickListener {
            val url = etRepoUrl.text.toString().trim().removeSuffix("/")
            if (url.contains("github.com")) {
                if (!appList.contains(url)) {
                    appList.add(url)
                    saveApps()
                    adapter.notifyDataSetChanged()
                }
                etRepoUrl.text.clear()
            } else {
                Toast.makeText(this, "Будь ласка, введіть коректне посилання GitHub", Toast.LENGTH_SHORT).show()
            }
        }

        // Обробка кнопки "Назад" (закриття WebView або додатка)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webViewApp.visibility == View.VISIBLE) {
                    webViewApp.visibility = View.GONE
                    webViewApp.loadUrl("about:blank")
                } else {
                    finish()
                }
            }
        })
    }

    // КРОК 1: Отримуємо список гілок
    private fun startRepoAnalysis(repoUrl: String) {
        val path = repoUrl.substringAfter("github.com/")
        val branchesUrl = "https://api.github.com/repos/$path/branches"

        thread {
            try {
                val response = makeApiRequest(branchesUrl)
                val jsonArray = JSONArray(response)
                val branches = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    branches.add(jsonArray.getJSONObject(i).getString("name"))
                }

                runOnUiThread {
                    when {
                        branches.isEmpty() -> Toast.makeText(this, "Гілок не знайдено", Toast.LENGTH_SHORT).show()
                        branches.size == 1 -> scanAllFiles(repoUrl, branches[0])
                        else -> showBranchChoiceDialog(repoUrl, branches)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Помилка доступу до GitHub API", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showBranchChoiceDialog(repoUrl: String, branches: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Виберіть гілку")
            .setItems(branches.toTypedArray()) { _, which ->
                scanAllFiles(repoUrl, branches[which])
            }
            .show()
    }

    // КРОК 2: Рекурсивне сканування ВСІХ папок та файлів у вибраній гілці
    private fun scanAllFiles(repoUrl: String, branch: String) {
        val path = repoUrl.substringAfter("github.com/")
        // recursive=1 дозволяє отримати список усіх файлів у всіх папках одним запитом
        val treeUrl = "https://api.github.com/repos/$path/git/trees/$branch?recursive=1"

        thread {
            try {
                val response = makeApiRequest(treeUrl)
                val jsonObject = JSONObject(response)
                val treeArray = jsonObject.getJSONArray("tree")
                val filesList = mutableListOf<String>()

                for (i in 0 until treeArray.length()) {
                    val item = treeArray.getJSONObject(i)
                    if (item.getString("type") == "blob") { // blob означає файл
                        filesList.add(item.getString("path"))
                    }
                }

                runOnUiThread {
                    if (filesList.isEmpty()) {
                        Toast.makeText(this, "У цій гілці немає файлів", Toast.LENGTH_SHORT).show()
                    } else {
                        showFileChoiceDialog(repoUrl, branch, filesList)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Помилка сканування файлів", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showFileChoiceDialog(repoUrl: String, branch: String, files: List<String>) {
        val sortedFiles = files.sorted() // Сортуємо для зручності
        AlertDialog.Builder(this)
            .setTitle("Виберіть файл для відкриття")
            .setItems(sortedFiles.toTypedArray()) { _, which ->
                launchFile(repoUrl, branch, sortedFiles[which])
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    // КРОК 3: Відкриття обраного файлу через Raw посилання
    private fun launchFile(baseUrl: String, branch: String, filePath: String) {
        val rawUrl = baseUrl.replace("github.com", "raw.githubusercontent.com") + "/$branch/$filePath"
        webViewApp.visibility = View.VISIBLE
        webViewApp.loadUrl(rawUrl)
        Toast.makeText(this, "Завантаження: $filePath", Toast.LENGTH_SHORT).show()
    }

    // Допоміжна функція для запитів
    private fun makeApiRequest(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Android-App-Tester")
        return connection.inputStream.bufferedReader().readText()
    }

    // Збереження списку в пам'ять телефону
    private fun saveApps() {
        val prefs = getSharedPreferences("AppTesterPrefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("saved_repos", appList.toSet()).apply()
    }

    private fun loadApps() {
        val prefs = getSharedPreferences("AppTesterPrefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("saved_repos", emptySet())
        appList.clear()
        appList.addAll(saved!!)
    }
}
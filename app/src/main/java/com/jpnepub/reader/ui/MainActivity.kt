package com.jpnepub.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jpnepub.reader.databinding.ActivityMainBinding
import com.jpnepub.reader.epub.EpubBook
import com.jpnepub.reader.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bookList = mutableListOf<BookEntry>()
    private lateinit var adapter: BookshelfAdapter

    data class BookEntry(
        val title: String,
        val author: String,
        val uri: Uri
    )

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { openEpub(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = BookshelfAdapter(bookList) { entry ->
            launchReader(entry.uri)
        }
        binding.bookshelfRecycler.layoutManager = LinearLayoutManager(this)
        binding.bookshelfRecycler.adapter = adapter

        binding.fabOpen.setOnClickListener {
            openFileLauncher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
        }

        updateEmptyState()

        // Handle intent (e.g., "Open with")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                launchReader(uri)
            }
        }
    }

    private fun openEpub(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        lifecycleScope.launch {
            try {
                val book = withContext(Dispatchers.IO) {
                    EpubParser(this@MainActivity).parse(uri)
                }

                bookList.add(BookEntry(book.title, book.author, uri))
                adapter.notifyItemInserted(bookList.size - 1)
                updateEmptyState()

                launchReader(uri)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "${getString(com.jpnepub.reader.R.string.error_open_file)}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchReader(uri: Uri) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun updateEmptyState() {
        binding.emptyText.visibility = if (bookList.isEmpty()) View.VISIBLE else View.GONE
        binding.bookshelfRecycler.visibility = if (bookList.isEmpty()) View.GONE else View.VISIBLE
    }
}

package com.jpnepub.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jpnepub.reader.R
import com.jpnepub.reader.databinding.ItemBookBinding

class BookshelfAdapter(
    private val books: List<MainActivity.BookEntry>,
    private val onClick: (MainActivity.BookEntry) -> Unit
) : RecyclerView.Adapter<BookshelfAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: MainActivity.BookEntry) {
            binding.tvBookTitle.text = entry.title
            binding.tvBookAuthor.text = entry.author.ifEmpty {
                binding.root.context.getString(R.string.author_unknown)
            }
            binding.tvBookPath.text = entry.uri.lastPathSegment ?: ""
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount() = books.size
}

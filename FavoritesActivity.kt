package com.gpswalker.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gpswalker.app.R
import com.gpswalker.app.data.local.FavoritePlace
import com.gpswalker.app.data.local.FavoritesManager

class FavoritesActivity : AppCompatActivity() {
    
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var adapter: FavoritesAdapter
    private var allPlaces = listOf<FavoritePlace>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        
        favoritesManager = FavoritesManager(this)
        
        val recyclerView = findViewById<RecyclerView>(R.id.rvFavorites)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = FavoritesAdapter(
            onItemClick = { place -> onFavoriteClick(place) },
            onEditClick = { place -> showEditDialog(place) }, // 支援點擊編輯名稱與座標 (解決問題 2)
            onDeleteClick = { place -> onDeleteClick(place) }
        )
        recyclerView.adapter = adapter
        
        // Search
        findViewById<EditText>(R.id.etSearchFavorites)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    adapter.submitList(allPlaces)
                } else {
                    adapter.submitList(favoritesManager.search(query))
                }
            }
        })
        
        // Back button
        findViewById<ImageButton>(R.id.btnBack)?.setOnClickListener { finish() }
        
        // Add button
        findViewById<View>(R.id.fabAdd)?.setOnClickListener { showAddDialog() }
        
        loadFavorites()
    }
    
    private fun loadFavorites() {
        allPlaces = favoritesManager.getAll()
        adapter.submitList(allPlaces)
    }
    
    private fun onFavoriteClick(place: FavoritePlace) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("navigate_lat", place.latitude)
            putExtra("navigate_lng", place.longitude)
            putExtra("navigate_name", place.name)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
    
    private fun onDeleteClick(place: FavoritePlace) {
        AlertDialog.Builder(this)
            .setTitle("刪除")
            .setMessage("確定要刪除 \"${place.name}\" 嗎？")
            .setPositiveButton("刪除") { _, _ ->
                favoritesManager.remove(place.id)
                loadFavorites()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_favorite, null)
        val etName = view.findViewById<EditText>(R.id.etPlaceName)
        val etCoords = view.findViewById<EditText>(R.id.etCoordinates)
        
        AlertDialog.Builder(this)
            .setTitle("新增最愛地點")
            .setView(view)
            .setPositiveButton("儲存") { _, _ ->
                val name = etName.text.toString().ifEmpty { "未命名地點" }
                val coordStr = etCoords.text.toString()
                val coords = parseCoordinateInput(coordStr)
                if (coords == null) {
                    Toast.makeText(this, "座標格式錯誤，請輸入如：25.033964, 121.564468", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                
                favoritesManager.add(FavoritePlace(name = name, latitude = coords.first, longitude = coords.second))
                loadFavorites()
                Toast.makeText(this, "已新增：$name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 支援編輯現有最愛地點的名稱與座標 (解決問題 2)
    private fun showEditDialog(place: FavoritePlace) {
        val view = layoutInflater.inflate(R.layout.dialog_add_favorite, null)
        val etName = view.findViewById<EditText>(R.id.etPlaceName)
        val etCoords = view.findViewById<EditText>(R.id.etCoordinates)
        
        etName.setText(place.name)
        etCoords.setText("${place.latitude}, ${place.longitude}")

        AlertDialog.Builder(this)
            .setTitle("編輯最愛地點")
            .setView(view)
            .setPositiveButton("更新") { _, _ ->
                val name = etName.text.toString().ifEmpty { "未命名地點" }
                val coordStr = etCoords.text.toString()
                val coords = parseCoordinateInput(coordStr)
                if (coords == null) {
                    Toast.makeText(this, "座標格式錯誤！", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                
                // 刪除舊的並新增更新後的資料，或直接調用 update
                favoritesManager.remove(place.id)
                favoritesManager.add(FavoritePlace(name = name, latitude = coords.first, longitude = coords.second))
                loadFavorites()
                Toast.makeText(this, "已更新：$name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // 強效座標解析器：自動拆分各種黏在一起或奇怪格式的經緯度 (解決問題 3)
    private fun parseCoordinateInput(input: String): Pair<Double, Double>? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null

        // 支援利用逗號、空格、分號、甚至是多種空白混合隔開的字串拆分
        val parts = cleaned.split(Regex("[,，\\s;]+"))
        if (parts.size >= 2) {
            val lat = parts[0].toDoubleOrNull()
            val lng = parts[1].toDoubleOrNull()
            if (lat != null && lng != null) {
                if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                    return Pair(lat, lng)
                }
            }
        }
        return null
    }
    
    // RecyclerView Adapter
    inner class FavoritesAdapter(
        private val onItemClick: (FavoritePlace) -> Unit,
        private val onEditClick: (FavoritePlace) -> Unit,
        private val onDeleteClick: (FavoritePlace) -> Unit
    ) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {
        
        private var items = listOf<FavoritePlace>()
        
        fun submitList(list: List<FavoritePlace>) {
            items = list
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }
        
        override fun getItemCount() = items.size
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvName: TextView = view.findViewById(R.id.tvFavName)
            private val tvCoords: TextView = view.findViewById(R.id.tvFavCoords)
            private val btnDelete: ImageButton = view.findViewById(R.id.btnFavDelete)
            
            fun bind(place: FavoritePlace) {
                tvName.text = place.name
                tvCoords.text = place.coordinateString()
                
                // 點擊項目本身可以導航
                itemView.setOnClickListener { onItemClick(place) }
                // 長按項目或點擊旁邊可以進行編輯座標 (解決問題 2)
                itemView.setOnLongClickListener {
                    onEditClick(place)
                    true
                }
                btnDelete.setOnClickListener { onDeleteClick(place) }
            }
        }
    }
}

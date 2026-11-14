package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Global search: tanan nga filter (query, category, lungsod, presyo, sort) sa
 * usa ka screen, kauban ang search history.
 */
@Composable
fun GlobalSearchScreen(
    uid: String,
    listings: List<Listing>,
    favorites: Set<String>,
    onOpen: (Listing) -> Unit,
    onFavorite: (Listing) -> Unit,
    onAddToCart: (Listing) -> Unit
) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(categories[0]) }
    var town by remember { mutableStateOf("Tanan") }
    var priceFilter by remember { mutableStateOf("Tanan") }
    var sort by remember { mutableStateOf("Bag-o") }
    var recent by remember { mutableStateOf(loadRecentSearches(ctx)) }

    val results = listings.filter {
        (category == "Tanan" || it.category == category) &&
            (town == "Tanan" || it.town == town) &&
            matchesPrice(it.price, priceFilter) &&
            (q.isBlank() || it.title.contains(q, true) || it.description.contains(q, true))
    }.let { list ->
        when (sort) {
            "Presyo ↑" -> list.sortedBy { it.price }
            "Presyo ↓" -> list.sortedByDescending { it.price }
            else -> list
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(
                "Search",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        SearchBar(
            query = q,
            onQuery = { q = it },
            onSubmit = {
                addRecentSearch(ctx, q)
                recent = loadRecentSearches(ctx)
            }
        )
        Spacer(Modifier.height(6.dp))
        FilterBar(priceFilter, { priceFilter = it }, sort, { sort = it })
        TownChips(town) { town = it }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categories) { c ->
                FilterChip(
                    selected = c == category,
                    onClick = { category = c },
                    label = { Text(c, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (q.isBlank() && recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bag-ong gipangita", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    clearRecentSearches(ctx)
                    recent = emptyList()
                }) { Text("Klarohon") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(recent) { r ->
                    FilterChip(
                        selected = false,
                        onClick = { q = r },
                        label = { Text(r) }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("${results.size} ka resulta", style = MaterialTheme.typography.labelMedium)

        if (results.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Walay listing nga mohaom. Sulayi og usab ang filter.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id }) { l ->
                    ListingCard(
                        listing = l,
                        isFavorite = favorites.contains(l.id),
                        onOpen = { onOpen(l) },
                        onFavorite = { onFavorite(l) },
                        onAddToCart = { onAddToCart(l) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

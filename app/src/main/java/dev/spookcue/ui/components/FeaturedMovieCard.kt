package dev.spookcue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import dev.spookcue.R
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.ui.theme.CrimsonPrimary
import dev.spookcue.ui.theme.LunarBorder
import dev.spookcue.ui.theme.LunarSlateDim
import dev.spookcue.ui.theme.LunarSlateMuted
import dev.spookcue.ui.theme.LunarWhite
import dev.spookcue.ui.theme.PureBlack
import dev.spookcue.ui.theme.TheaterAmber

@Composable
fun FeaturedMovieCard(
    movie: MovieCatalogItem,
    isNew: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }

    val imageRequest = remember(posterUrl) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .crossfade(false)
            .allowRgb565(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .placeholder(R.drawable.ic_film_reel_spooky)
            .error(R.drawable.ic_film_reel_spooky)
            .build()
    }

    Column(
        modifier = modifier
            .width(114.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(114.dp)
                .height(168.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PureBlack)
                .border(1.dp, LunarBorder, RoundedCornerShape(10.dp))
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_film_reel_spooky),
                        contentDescription = "No Poster",
                        tint = LunarSlateMuted,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Tag Badge on Top Corner (NEW only, HOT removed)
            if (isNew) {
                Surface(
                    color = CrimsonPrimary,
                    shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .shadow(4.dp, RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                ) {
                    Text(
                        text = "NEW",
                        color = LunarWhite,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = movie.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp
            ),
            color = LunarWhite,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${movie.releaseYear?.let { if (it > 0) "$it • " else "" } ?: ""}${movie.scareCount} scares",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = LunarSlateDim,
            maxLines = 1
        )
    }
}

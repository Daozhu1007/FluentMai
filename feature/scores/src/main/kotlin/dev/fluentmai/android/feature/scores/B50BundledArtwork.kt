package dev.fluentmai.android.feature.scores

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Small, immutable game UI assets work offline, including on first launch. */
internal object B50BundledArtwork {
    val ratings = mapOf(
        "normal" to R.drawable.b50_rating_normal, "blue" to R.drawable.b50_rating_blue,
        "green" to R.drawable.b50_rating_green, "orange" to R.drawable.b50_rating_orange,
        "red" to R.drawable.b50_rating_red, "purple" to R.drawable.b50_rating_purple,
        "bronze" to R.drawable.b50_rating_bronze, "silver" to R.drawable.b50_rating_silver,
        "gold" to R.drawable.b50_rating_gold, "platinum" to R.drawable.b50_rating_platinum,
        "rainbow" to R.drawable.b50_rating_rainbow,
    )
    val trophies = mapOf(
        "normal" to R.drawable.b50_trophy_normal, "bronze" to R.drawable.b50_trophy_bronze,
        "silver" to R.drawable.b50_trophy_silver, "gold" to R.drawable.b50_trophy_gold,
        "rainbow" to R.drawable.b50_trophy_rainbow,
    )

    fun load(resources: Resources, rating: Int, player: B50PlayerProfile?, options: B50DisplayOptions): Map<String, Bitmap> = buildMap {
        put(B50Assets.rating(rating), requireNotNull(BitmapFactory.decodeResource(resources, ratings.getValue(posterRatingColor(rating)))))
        if (options.trophy && player?.trophy != null) {
            put(B50Assets.trophy(player.trophyColor), requireNotNull(BitmapFactory.decodeResource(resources, trophies.getValue(B50Assets.trophyColor(player.trophyColor)))))
        }
    }
}
